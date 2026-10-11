package com.jamespark.millie_auto_player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

class MainActivity : Activity() {

    private val MILLIE_PACKAGE = "kr.co.millie.millieshelf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 핵심 권한 확인: '알림 접근 허용 (MediaSession 및 알림 제어)'
        if (!MillieMediaSessionService.isNotificationAccessGranted(this)) {
            Toast.makeText(
                this,
                "밀리의 서재 오토 플레이어를 위해\n'알림 접근 허용'에서 앱 권한을 켜주세요.",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
            return
        }

        // 2. 미디어 세션 서비스 실행 요청
        val service = MillieMediaSessionService.instance
        if (service != null) {
            Toast.makeText(this, "▶ 밀리의 서재 오디오북 자동 탐색 및 재생 중...", Toast.LENGTH_SHORT).show()
            service.startAutoPlaySequence()
        } else {
            // 시스템에 서비스가 바인딩되는 과도기 상태일 경우의 안전한 폴백 처리
            Toast.makeText(this, "▶ 밀리의 서재 앱 실행 중...", Toast.LENGTH_SHORT).show()
            val launchIntent = packageManager.getLaunchIntentForPackage(MILLIE_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }

            // 접근성 서비스 트리거도 활성화
            MillieAccessibilityService.activateTrigger()

            // 1.8초 뒤 시스템 미디어 키 전송
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                } catch (_: Exception) {}
            }, 1800)
        }

        // 0.3초 후 본 액티비티 종료 (백그라운드에서 백그라운드 서비스가 비동기 처리)
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 300)
    }
}
