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
        private const val TAG = "MillieAccessibility"
        const val MILLIE_PACKAGE = "kr.co.millie.millieshelf"

        @Volatile
        var isTriggerActive = false
        private var lastTriggerTime = 0L

        fun activateTrigger() {
            isTriggerActive = true
            lastTriggerTime = System.currentTimeMillis()
        }

        fun deactivateTrigger() {
            isTriggerActive = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var hasSuccessfullyPlayed = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()
        // 트리거 요청 후 15초 동안만 감시
        if (!isTriggerActive || (now - lastTriggerTime > 15000)) {
            isTriggerActive = false
            return
        }

        if (event.packageName?.toString() != MILLIE_PACKAGE) return
        if (isProcessing) return

        // [중요] 앱 실행 직후 1.5초 동안은 스플래시 화면이므로 헛터치 방지를 위해 대기
        if (now - lastTriggerTime < 1500) {
            return
        }

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
        // 1. 이미 재생 중인지 확인 (일시정지 버튼 발견 시 완료 처리)
        if (findPauseButton(root) != null) {
            Log.d(TAG, "이미 재생 중 (일시정지 버튼 감지) -> 성공 완료")
            onPlaybackConfirmed()
            return
        }

        // 2. 팝업 / 공지 모달 창이 화면을 가리고 있다면 우선 닫기
        if (dismissPopups(root)) {
            Log.d(TAG, "방해되는 팝업창을 닫았습니다. 다음 이벤트 대기...")
            return
        }

        // 3. 재생(Play) 버튼 노드 직접 검색
        val playButton = findPlayButton(root)
        if (playButton != null) {
            val rect = Rect()
            playButton.getBoundsInScreen(rect)
            Log.d(TAG, "재생 버튼 발견: bounds=$rect, desc=${playButton.contentDescription}, text=${playButton.text}")

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

        // 4. 하단 오디오북 미니 플레이어 바 검색
        val miniBar = findMiniPlayerBar(root)
        if (miniBar != null) {
            val rect = Rect()
            miniBar.getBoundsInScreen(rect)
            Log.d(TAG, "하단 미니 플레이어 바 발견: bounds=$rect -> 터치하여 재생 열기")
            val clicked = clickNode(miniBar)
            if (!clicked || rect.width() > 0) {
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            miniBar.recycle()
            return
        }

        // 5. 콜드 스타트 시 홈 화면에 미니바가 없는 경우: '내 서재' 탭 탐색
        val myLibraryNode = findMyLibraryTab(root)
        if (myLibraryNode != null) {
            Log.d(TAG, "'내 서재' 탭 발견 -> 내 서재로 이동하여 최근 읽은 책 탐색")
            clickNode(myLibraryNode)
            myLibraryNode.recycle()
            return
        }

        // 6. 폴백 스마트 터치 (앱 로딩이 완전히 끝난 후 2.5초 이후에만 제한적으로 시도)
        val elapsed = System.currentTimeMillis() - lastTriggerTime
        if (elapsed > 2500) {
            fallbackSmartTouch()
        }
    }

    private fun dismissPopups(root: AccessibilityNodeInfo): Boolean {
        val popupKeywords = listOf("오늘 하루 보지 않기", "다시 보지 않기", "닫기", "취소")

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            for (kw in popupKeywords) {
                if (text.contains(kw) || desc.contains(kw)) {
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

        val target = search(root) ?: return false
        Log.d(TAG, "팝업 닫기 버튼 감지: text=${target.text}, desc=${target.contentDescription}")
        clickNode(target)
        target.recycle()
        return true
    }

    private fun findMyLibraryTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            if (text == "내서재" || text == "내 서재" || desc == "내서재" || desc == "내 서재") {
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

    private var lastFallbackTime = 0L
    private fun fallbackSmartTouch() {
        val now = System.currentTimeMillis()
        if (now - lastFallbackTime < 2500) return
        lastFallbackTime = now

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // 미니바 우측 재생 위치 (X: 88%, Y: 87%)
        val miniPlayX = width * 0.88f
        val miniPlayY = height * 0.87f
        tapCoordinates(miniPlayX, miniPlayY)

        // 0.6초 뒤 미니바 중앙 (X: 50%, Y: 87%)
        handler.postDelayed({
            val miniBarCenterX = width * 0.50f
            tapCoordinates(miniBarCenterX, miniPlayY)
        }, 600)
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

        Toast.makeText(this, "▶ 밀리의 서재 오디오북 자동 재생 확인!", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            hasSuccessfullyPlayed = false
        }, 700)
    }

    override fun onInterrupt() {
        isTriggerActive = false
    }
}
