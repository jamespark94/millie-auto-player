package com.jamespark.millie_auto_player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast

class MainActivity : Activity() {
    private val MILLIE_PACKAGE = "kr.co.millie.millieshelf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 접근성 서비스 켜져 있는지 확인
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "밀리의 서재 오토 플레이어를 위해\n'설치된 앱 > 밀리의 서재 오토 플레이어'를 켜주세요.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        // 2. 접근성 서비스 감지 트리거 활성화
        MillieAccessibilityService.activateTrigger()

        // 3. 밀리의 서재 실행
        val launchIntent = packageManager.getLaunchIntentForPackage(MILLIE_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
        }

        Toast.makeText(this, "▶ 밀리의 서재 오디오북 자동 탐색 중...", Toast.LENGTH_SHORT).show()

        // 0.3초 후 본 런처 액티비티 닫기 (접근성 서비스가 백그라운드에서 감지 및 클릭 처리)
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 300)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${packageName}/${MillieAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
