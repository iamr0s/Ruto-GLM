package com.rosan.ruto.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import com.rosan.installer.ext.util.pendingActivity

class ActivityManagerService @Keep constructor(private val context: Context) :
    IActivityManager.Stub() {
    // package manager func
    private val packageManager by lazy {
        context.packageManager
    }

    override fun startLabel(label: String, displayId: Int) {
        val allApps = packageManager.getInstalledApplications(0)

        // 使用 Sequence 避免内存抖动，提升大量 App 时的解析速度
        val targetApp = allApps.asSequence()
            .map { app ->
                // 预先获取 Label 避免多次 IPC 调用
                val appName = app.loadLabel(packageManager).toString()
                app to appName
            }
            .mapNotNull { (app, name) ->
                // 计算匹配权重：值越小，优先级越高
                val weight = when {
                    // 1. 包名完全一致（最高优先级）
                    app.packageName.equals(label, ignoreCase = true) -> 1
                    // 2. 名称完全一致
                    name.equals(label, ignoreCase = true) -> 2
                    // 3. 名称以输入词开头（例如输入"抖音"，匹配"抖音极速版"）
                    name.startsWith(label, ignoreCase = true) -> 3
                    // 4. 名称包含输入词
                    name.contains(label, ignoreCase = true) -> 4
                    // 5. 包名包含输入词 (例如输入"tencent"，匹配所有腾讯系 App)
                    app.packageName.contains(label, ignoreCase = true) -> 5
                    else -> null // 完全不相关
                }
                if (weight != null) Triple(app, name, weight) else null
            }
            // 按权重排序，权重相同时按名称长度排序（越短越精准）
            .sortedWith(compareBy({ it.third }, { it.second.length }))
            .firstOrNull()?.first

        targetApp?.let { startApp(it.packageName, displayId) }
    }

    override fun startApp(packageName: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(intent, displayId)
    }

    override fun startActivity(intent: Intent, displayId: Int) {
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        intent.pendingActivity(
            context.createDisplayContext(
                context.getSystemService<DisplayManager>()!!.getDisplay(displayId)
            ),
            requestCode = intent.hashCode(),
            options = options.toBundle()
        ).send()
    }
}