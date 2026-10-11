package com.jamespark.millie_auto_player

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

class MillieMediaSessionService : NotificationListenerService() {

    companion object {
        const val TAG = "MillieMediaSession"
        const val MILLIE_PACKAGE = "kr.co.millie.millieshelf"

        @Volatile
        var instance: MillieMediaSessionService? = null
            private set

        /**
         * '알림 접근 허용(Notification Listener)' 권한이 부여되어 있는지 확인
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val colonSplitter = flat.split(":")
            for (name in colonSplitter) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
            return false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isWatchingForPlayback = false
    private var watchStartTime = 0L
    private val WATCH_TIMEOUT_MS = 10000L // 10초 동안 세션/알림 감시

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed, count=${controllers?.size ?: 0}")
        if (isWatchingForPlayback) {
            checkAndPlayMillieSession(controllers)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "NotificationListenerService connected")

        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, MillieMediaSessionService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register active sessions changed listener: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove session listener: ${e.message}")
        }
        instance = null
        Log.d(TAG, "NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName != MILLIE_PACKAGE) return

        Log.d(TAG, "Millie notification posted: id=${sbn.id}, tag=${sbn.tag}")
        if (isWatchingForPlayback) {
            handleMillieNotification(sbn)
        }
    }

    /**
     * 메인 자동 재생 시퀀스 실행
     */
    fun startAutoPlaySequence() {
        Log.d(TAG, "Starting auto-play sequence...")
        isWatchingForPlayback = true
        watchStartTime = System.currentTimeMillis()

        // 1단계: 이미 백그라운드에 활성 세션(MediaSession)이 있는지 확인
        if (checkAndPlayActiveSession()) {
            Log.d(TAG, "1단계 성공: 활성 MediaSession을 통해 즉각 재생 시작")
            return
        }

        // 2단계: 상단바 알림창에 밀리의 서재 미디어 알림이 있는지 확인
        if (checkAndPlayFromActiveNotifications()) {
            Log.d(TAG, "2단계 성공: 기존 알림의 미디어 토큰/재생 액션을 통해 재생 시작")
            return
        }

        // 3단계: 세션/알림이 없는 경우 (Cold Start) -> 앱 실행 후 세션 생성 대기 및 시스템 미디어 키 전송
        Log.d(TAG, "3단계: 콜드 스타트 감지 -> 밀리의 서재 앱 실행 및 백그라운드 폴링 시작")
        launchMillieApp()

        // 보조 접근성 서비스 트리거도 활성화 (UI 클릭 폴백)
        MillieAccessibilityService.activateTrigger()

        // 4단계: 주기적 감시 및 타임라인별 폴백 실행
        scheduleWatchdog()
    }

    private fun checkAndPlayActiveSession(): Boolean {
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
            val component = ComponentName(this, MillieMediaSessionService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(component)
            return checkAndPlayMillieSession(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active sessions: ${e.message}")
            return false
        }
    }

    private fun checkAndPlayMillieSession(controllers: List<MediaController>?): Boolean {
        if (controllers.isNullOrEmpty()) return false

        val millieController = controllers.firstOrNull { it.packageName == MILLIE_PACKAGE } ?: return false

        val state = millieController.playbackState?.state
        Log.d(TAG, "Found Millie MediaController, state=$state")

        if (state == PlaybackState.STATE_PLAYING) {
            Log.d(TAG, "Millie is already playing")
            onPlaybackSuccess("밀리의 서재 오디오북이 이미 재생 중입니다.")
            return true
        }

        // 재생 명령 실행
        millieController.transportControls.play()

        // 컨트롤러 대상 미디어 키 전송 (보강)
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
        millieController.dispatchMediaButtonEvent(down)
        millieController.dispatchMediaButtonEvent(up)

        onPlaybackSuccess("▶ 밀리의 서재 미디어 세션 재생 시작!")
        return true
    }

    private fun checkAndPlayFromActiveNotifications(): Boolean {
        try {
            val activeNotifs = activeNotifications ?: return false
            for (sbn in activeNotifs) {
                if (sbn.packageName == MILLIE_PACKAGE) {
                    if (handleMillieNotification(sbn)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active notifications: ${e.message}")
        }
        return false
    }

    private fun handleMillieNotification(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false

        // A. 미디어 세션 토큰 확인
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notif.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            notif.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }

        if (token != null) {
            try {
                val controller = MediaController(this, token)
                val state = controller.playbackState?.state
                Log.d(TAG, "Found MediaSession token in notification, state=$state")
                if (state != PlaybackState.STATE_PLAYING) {
                    controller.transportControls.play()
                    onPlaybackSuccess("▶ 알림 미디어 세션을 통해 재생 시작!")
                    return true
                } else {
                    onPlaybackSuccess("밀리의 서재가 이미 재생 중입니다.")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to control token from notification: ${e.message}")
            }
        }

        // B. 알림 액션 버튼에서 재생 액션 찾기
        val actions = notif.actions
        if (!actions.isNullOrEmpty()) {
            for (action in actions) {
                val title = action.title?.toString()?.lowercase() ?: ""
                if (title.contains("재생") || title.contains("play") || title.contains("듣기") || title.contains("이어")) {
                    Log.d(TAG, "Found play action in notification: title=$title")
                    try {
                        action.actionIntent.send()
                        onPlaybackSuccess("▶ 알림 재생 버튼 액션 실행 완료!")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send notification actionIntent: ${e.message}")
                    }
                }
            }
        }

        return false
    }

    private fun launchMillieApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(MILLIE_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
            Log.d(TAG, "Millie app launched")
        } else {
            Log.e(TAG, "Millie app not installed or launch intent null")
        }
    }

    private fun scheduleWatchdog() {
        var attempts = 0
        val maxAttempts = 16 // 500ms * 16 = 8초 동안 감시

        val runnable = object : Runnable {
            override fun run() {
                if (!isWatchingForPlayback) return
                attempts++

                val elapsed = System.currentTimeMillis() - watchStartTime
                if (elapsed > WATCH_TIMEOUT_MS || attempts >= maxAttempts) {
                    Log.d(TAG, "Watchdog timeout reached")
                    isWatchingForPlayback = false
                    return
                }

                // 1) 세션 감지 재시도
                if (checkAndPlayActiveSession()) return

                // 2) 알림 감지 재시도
                if (checkAndPlayFromActiveNotifications()) return

                // 3) 앱 실행 2초 시점에 시스템 미디어 키 전송 (오디오 포커스 선점용)
                if (attempts == 4) { // 약 2초 경과 시점
                    Log.d(TAG, "Attempting system-wide MEDIA_PLAY dispatch after app warm-up...")
                    dispatchSystemMediaPlayKey()
                }

                // 다음 주기 실행
                handler.postDelayed(this, 500)
            }
        }

        handler.postDelayed(runnable, 500)
    }

    private fun dispatchSystemMediaPlayKey() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(down)
                audioManager.dispatchMediaKeyEvent(up)
                Log.d(TAG, "Dispatched system KEYCODE_MEDIA_PLAY")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching system media key: ${e.message}")
        }
    }

    private fun onPlaybackSuccess(message: String) {
        isWatchingForPlayback = false
        Log.d(TAG, "Playback success: $message")
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
