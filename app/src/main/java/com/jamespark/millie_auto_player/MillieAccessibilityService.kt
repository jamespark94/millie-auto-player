package com.jamespark.millie_auto_player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class MillieAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MillieAutoPlayer"
        const val MILLIE_PACKAGE = "kr.co.millie.millieshelf"

        @Volatile
        var isTriggerActive = false
        private var lastTriggerTime = 0L

        fun activateTrigger() {
            isTriggerActive = true
            lastTriggerTime = System.currentTimeMillis()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var hasSuccessfullyPlayed = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 트리거 요청 후 15초 동안만 집중 감지
        val now = System.currentTimeMillis()
        if (!isTriggerActive || (now - lastTriggerTime > 15000)) {
            isTriggerActive = false
            return
        }

        if (event.packageName?.toString() != MILLIE_PACKAGE) return
        if (isProcessing) return

        val rootNode = rootInActiveWindow ?: return

        try {
            isProcessing = true
            analyzeAndHandleMillieScreen(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen: ${e.message}")
        } finally {
            rootNode.recycle()
            handler.postDelayed({ isProcessing = false }, 300)
        }
    }

    private fun analyzeAndHandleMillieScreen(root: AccessibilityNodeInfo) {
        // 1. 이미 재생 중인지 확인 (일시정지 버튼 발견 시)
        if (findPauseButton(root) != null) {
            Log.d(TAG, "이미 재생 중 (일시정지 버튼 감지) -> 완료 처리")
            onPlaybackConfirmed()
            return
        }

        // 2. [상태 2 또는 3] 재생(Play) 버튼 노드 직접 검색
        val playButton = findPlayButton(root)
        if (playButton != null) {
            val rect = Rect()
            playButton.getBoundsInScreen(rect)
            Log.d(TAG, "재생 버튼 발견: bounds=$rect, desc=${playButton.contentDescription}, text=${playButton.text}")

            // 노드 클릭 시도 후 제스처 터치(dispatchGesture)도 병행하여 확실하게 클릭
            val clicked = clickNode(playButton)
            if (!clicked || rect.width() > 0) {
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            playButton.recycle()

            handler.postDelayed({
                checkAndConfirmPlayback()
            }, 800)
            return
        }

        // 3. [상태 1] 콜드 부팅 등으로 재생 버튼은 없지만 하단에 오디오북 미니바가 존재하는 경우
        val miniBar = findMiniPlayerBar(root)
        if (miniBar != null) {
            val rect = Rect()
            miniBar.getBoundsInScreen(rect)
            Log.d(TAG, "하단 미니 플레이어 바 발견: bounds=$rect -> 터치하여 오디오북 전체화면 열기")
            val clicked = clickNode(miniBar)
            if (!clicked || rect.width() > 0) {
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            miniBar.recycle()
            return
        }

        // 4. [폴백 제스처] 뷰 노드가 웹뷰/커스텀뷰로 숨겨져 있을 경우 화면 비율 기반 스마트 터치
        // 하단 탭바(화면 맨밑 약 8%) 바로 위(약 83%~90% 지점)가 오디오북 미니바 영역
        fallbackSmartTouch()
    }

    private var lastFallbackTime = 0L
    private fun fallbackSmartTouch() {
        val now = System.currentTimeMillis()
        if (now - lastFallbackTime < 2000) return
        lastFallbackTime = now

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // 1) 상태 2 시도: 미니바 우측의 재생 버튼 위치 (X: 88%, Y: 87%)
        val miniPlayX = width * 0.88f
        val miniPlayY = height * 0.87f
        tapCoordinates(miniPlayX, miniPlayY)

        // 2) 0.5초 뒤 상태 1 시도: 미니바 중앙 (X: 50%, Y: 87%)
        handler.postDelayed({
            val miniBarCenterX = width * 0.50f
            tapCoordinates(miniBarCenterX, miniPlayY)
        }, 500)

        // 3) 1.2초 뒤 상태 3 시도: 전체 화면 하단 중앙 재생 버튼 위치 (X: 50%, Y: 75%~80%)
        handler.postDelayed({
            val fullPlayX = width * 0.50f
            val fullPlayY = height * 0.78f
            tapCoordinates(fullPlayX, fullPlayY)
            checkAndConfirmPlayback()
        }, 1200)
    }

    private fun checkAndConfirmPlayback() {
        val currentRoot = rootInActiveWindow ?: return
        try {
            if (findPauseButton(currentRoot) != null) {
                onPlaybackConfirmed()
            }
        } finally {
            currentRoot.recycle()
        }
    }

    /**
     * 물리적 화면 터치(제스처) 실행
     */
    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "제스처 터치 실행: ($x, $y)")
    }

    private fun findPlayButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf("재생", "play", "play_arrow", "오디오북 재생", "이어듣기", "듣기", "다시듣기")

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (desc.contains("일시정지") || desc.contains("pause") || text.contains("일시정지")) {
                return null
            }

            for (kw in keywords) {
                if (desc.contains(kw) || text.contains(kw) || viewId.contains("play")) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
                child.recycle()
            }
            return null
        }

        return search(root)
    }

    private fun findPauseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (desc.contains("일시정지") || desc.contains("pause") || text.contains("일시정지") || viewId.contains("pause")) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
                child.recycle()
            }
            return null
        }
        return search(root)
    }

    private fun findMiniPlayerBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // 화면 하단 네비바 위 영역 (대략 75% ~ 93% 높이)
            val isAtBottomZone = rect.top > (screenHeight * 0.70) && rect.bottom < (screenHeight * 0.95)
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (isAtBottomZone && (node.isClickable || node.isCheckable)) {
                if (viewId.contains("player") || viewId.contains("mini") || 
                    desc.contains("오디오") || desc.contains("플레이어") ||
                    text.contains("오디오") || text.contains("%")) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
                child.recycle()
            }
            return null
        }

        return search(root)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun onPlaybackConfirmed() {
        if (hasSuccessfullyPlayed) return
        hasSuccessfullyPlayed = true
        isTriggerActive = false

        Toast.makeText(this, "▶ 밀리의 서재 오디오북 자동 재생 성공!", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            hasSuccessfullyPlayed = false
        }, 700)
    }

    override fun onInterrupt() {
        isTriggerActive = false
    }
}
