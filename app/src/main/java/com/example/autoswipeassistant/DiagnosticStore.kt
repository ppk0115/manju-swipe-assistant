package com.example.autoswipeassistant

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Bounded, opt-in diagnostics. No non-target page text is captured. */
object DiagnosticStore {
    const val KEY_ENABLED = "diagnostics_enabled"
    private val events = ArrayDeque<String>()
    private val snapshots = ArrayDeque<String>()
    private val recent = mutableMapOf<String, Pair<String, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var storage: File? = null
    private var pendingSave = false
    private var lastSnapshotKey = ""
    private var lastSnapshotAt = 0L
    var connected = false
        private set
    private var heartbeatAt = 0L

    fun enabled(context: Context) = AppPreferences.prefs(context).getBoolean(KEY_ENABLED, false)

    private fun init(context: Context) {
        if (storage != null) return
        storage = File(context.noBackupFilesDir, "diagnostics.json")
        runCatching {
            val file = File(context.noBackupFilesDir, "diagnostics.json")
            if (!file.exists() || file.length() > 1_000_000L) return@runCatching
            val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            val logs = json.optJSONArray("events") ?: JSONArray()
            val scans = json.optJSONArray("snapshots") ?: JSONArray()
            for (i in (logs.length() - 200).coerceAtLeast(0) until logs.length()) events.addLast(logs.getString(i).take(4_000))
            for (i in (scans.length() - 6).coerceAtLeast(0) until scans.length()) snapshots.addLast(scans.getString(i).take(48_000))
        }
    }

    fun connection(context: Context, value: Boolean, reason: String) {
        connected = value
        if (value) heartbeatAt = SystemClock.elapsedRealtime()
        event(context, "服务", reason)
    }

    fun heartbeat() { heartbeatAt = SystemClock.elapsedRealtime() }

    fun healthy(): Boolean = connected && heartbeatAt > 0L &&
        SystemClock.elapsedRealtime() - heartbeatAt < 5_000L

    fun event(context: Context, stage: String, detail: String) {
        if (!enabled(context)) return
        init(context)
        val now = SystemClock.elapsedRealtime()
        val previous = recent[stage]
        if (previous?.first == detail && now - previous.second < 5_000) return
        recent[stage] = detail to now
        events.addLast("${stamp()} [$stage] ${detail.take(1_900)}")
        while (events.size > 200) events.removeFirst()
        scheduleSave()
    }

    fun snapshot(context: Context, pkg: String, metadata: String, visibleText: String, nodes: String) {
        if (!enabled(context)) return
        init(context)
        val key = "$pkg|${metadata.substringBefore("；deepRefresh=")}|$visibleText|$nodes"
        val now = SystemClock.elapsedRealtime()
        if (key == lastSnapshotKey || now - lastSnapshotAt < 1_000) return
        lastSnapshotKey = key
        lastSnapshotAt = now
        snapshots.addLast("${stamp()} 目标应用：$pkg\n$metadata\n\n实际参与匹配的文字：\n${visibleText.take(16_000)}\n\n节点明细（包括不可见节点，仅可见文字参与匹配）：\n${nodes.take(28_000)}")
        while (snapshots.size > 6) snapshots.removeFirst()
        scheduleSave()
    }

    private fun scheduleSave() {
        if (pendingSave) return
        pendingSave = true
        handler.postDelayed({ pendingSave = false; persist() }, 1_000)
    }

    private fun persist() {
        val destination = storage ?: return
        val data = JSONObject().put("events", JSONArray(events.toList()))
            .put("snapshots", JSONArray(snapshots.toList())).toString()
        writer.execute {
            val file = AtomicFile(destination)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(data.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (_: Exception) { file.failWrite(stream) }
        }
    }

    fun clear(context: Context) {
        AppPreferences.clearLegacyText(context)
        init(context)
        events.clear(); snapshots.clear(); recent.clear()
        lastSnapshotKey = ""; lastSnapshotAt = 0
        persist()
    }

    fun report(context: Context): String {
        init(context)
        val prefs = AppPreferences.prefs(context)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val age = if (heartbeatAt == 0L) "尚无" else "${(SystemClock.elapsedRealtime() - heartbeatAt) / 1_000} 秒前"
        return buildString {
            appendLine("${context.getString(R.string.app_name)}诊断报告 v$version · ${stamp()}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("诊断记录：${if (enabled(context)) "开启" else "关闭（以下可能为历史记录）"}")
            appendLine("当前进程服务连接：$connected；最近检查：$age")
            if (Build.VERSION.SDK_INT >= 28) appendLine("系统后台运行受限：${context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted}")
            appendLine("提示检测：${prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true)}；观察模式：${prefs.getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false)}")
            appendLine("当前目标：${AppPreferences.targetPackages(context).joinToString()}")
            appendLine("广告标记循环：${prefs.getBoolean(AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true)}；仅默认方案；完成后至少 1 秒重读，连续 3 次完整读取无标记停止（允许空文字）；单轮最多有效检测 90 秒或 60 次，暂停与中断时间不计入；OCR 已移除")
            appendLine("短剧直播适配：仅 com.phoenix.read；可见播放器及同分支短剧推广容器、无剧集控件，且文字为空或仅领取/立即领取；同一窗口两次完整读取相隔至少 1 秒后生效。空文字及领取不单独触发。")
            appendLine("文字备用规则：${AppPreferences.customPrompts(context)?.joinToString(" | ") ?: "默认规则"}（广告循环启用时不参与默认方案匹配）")
            val pattern = PatternStore.active(context)
            appendLine("当前识别方案：${pattern?.name ?: PatternStore.DEFAULT_NAME}；绑定=${pattern?.packageName ?: "目标应用列表"}")
            appendLine("\n【检测过程 · 最近 200 条，时间正序】")
            appendLine(if (events.isEmpty()) "暂无记录。开启诊断后，请全屏进入目标应用复现。" else events.joinToString("\n"))
            appendLine("\n【目标文字快照 · 最近 6 份不同内容，最新在前】")
            appendLine("切回助手不会覆盖目标快照。每秒最多保存一次，不能代表期间每一帧；请结合扫描时间与截断标志判断。")
            appendLine(if (snapshots.isEmpty()) "尚无成功读取的目标快照。先查看上方窗口拦截原因，不能据此认定目标应用不提供文字。" else snapshots.reversed().joinToString("\n\n----------------\n\n"))
        }
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
}
