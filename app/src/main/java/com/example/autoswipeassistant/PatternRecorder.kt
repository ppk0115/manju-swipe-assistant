package com.example.autoswipeassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject

/** Non-focusable accessibility overlay; it is removed from view while samples are read. */
class PatternRecorder(
    private val service: AccessibilityService,
    private val capture: () -> ControlSample?,
    private val finished: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var generation = 0
    private var startedAt = 0L
    private var normal: ControlSample? = null
    private var ad: ControlSample? = null
    val recording get() = panel != null

    fun checkRequest() {
        if (recording && SystemClock.elapsedRealtime() - startedAt > 600_000) {
            close(); toast("学习已超时，请重新开始")
        }
        val prefs = AppPreferences.prefs(service)
        val request = prefs.getString(PatternStore.KEY_REQUEST, null) ?: return
        prefs.edit().remove(PatternStore.KEY_REQUEST).apply()
        runCatching {
            val obj = JSONObject(request)
            require(System.currentTimeMillis() - obj.getLong("at") in 0..60_000)
            val pkg = obj.getString("package")
            require(pkg in AppPreferences.targetPackages(service) && pkg != service.packageName)
            val name = obj.getString("name").trim()
            require(name.isNotBlank() && name.length <= 30)
            start(obj.getString("id"), name, pkg)
        }.onFailure {
            DiagnosticStore.event(service, "学习失败", "${it.javaClass.simpleName}: ${it.message}")
            android.util.Log.e("PatternRecorder", "Cannot start learning", it)
            close(); toast("无法开始学习，请检查目标列表后重试")
        }
    }

    private fun start(id: String, name: String, pkg: String) {
        close()
        normal = null; ad = null
        startedAt = SystemClock.elapsedRealtime()
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(service.getColor(R.color.background)); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), service.getColor(R.color.input_border))
            }
        }
        val title = TextView(service).apply {
            text = service.getString(R.string.learning_step_normal)
            textSize = 13f
            setTextColor(service.getColor(R.color.text_primary))
        }
        layout.addView(title)
        val sampleButton = Button(service).apply {
            text = "记录正常视频"; isAllCaps = false; textSize = 13f; minHeight = dp(48)
        }
        layout.addView(sampleButton)
        val backButton = Button(service).apply {
            text = "重录上一步"; isAllCaps = false; textSize = 13f; minHeight = dp(48)
        }
        layout.addView(backButton)
        val feedback = TextView(service).apply {
            textSize = 13f
            setTextColor(service.getColor(R.color.warning_text))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(feedback)
        fun updateStep() {
            val step = if (normal == null) 1 else if (ad == null) 2 else 3
            title.text = service.getString(R.string.learning_progress, step, service.getString(when (step) {
                1 -> R.string.learning_step_normal
                2 -> R.string.learning_step_ad
                else -> R.string.learning_step_ready
            }))
            sampleButton.text = when (step) { 1 -> "记录正常视频"; 2 -> "记录广告状态"; else -> "记录并保存" }
            backButton.isEnabled = normal != null
            feedback.text = ""
        }
        backButton.setOnClickListener {
            if (ad != null) ad = null else normal = null
            updateStep()
        }
        updateStep()
        val overlay = ScrollView(service).apply { addView(layout) }
        layout.addView(Button(service).apply {
            text = "取消学习"; isAllCaps = false; textSize = 13f; minHeight = dp(48)
            setOnClickListener { close(); toast("已取消，原方案未修改") }
        })
        sampleButton.setOnClickListener {
            sampleButton.isEnabled = false
            val token = generation
            overlay.visibility = View.GONE
            handler.postDelayed({
                if (token != generation || panel !== overlay) return@postDelayed
                val sample = runCatching { capture() }.getOrNull()
                overlay.visibility = View.VISIBLE
                sampleButton.isEnabled = true
                if (sample == null || sample.packageName != pkg || pkg !in AppPreferences.targetPackages(service)) {
                    feedback.text = "请全屏打开所选应用，关闭其他小窗后重试"
                    return@postDelayed
                }
                if (!sample.complete) { feedback.text = "节点读取不完整，请重试"; return@postDelayed }
                when {
                    normal == null -> {
                        normal = sample
                        updateStep()
                    }
                    ad == null -> {
                        ad = sample
                        updateStep()
                    }
                    else -> {
                        val reason = PatternMatcher.validation(normal!!, ad!!, sample)
                        if (reason != null) { feedback.text = service.getString(R.string.learning_retry_hint, reason); return@postDelayed }
                        val result = runCatching {
                            val samples = PatternSamples(normal!!, ad!!, sample)
                            val existing = PatternStore.list(service).firstOrNull { it.id == id && it.packageName == pkg }
                            val pattern = existing?.withSamples(samples)?.copy(name = name) ?:
                                LearnedPattern(id, name, pkg, samples.normal, samples.advertisement, samples.ready)
                            PatternStore.save(service, pattern)
                            PatternStore.select(service, id)
                        }
                        if (result.isSuccess) {
                            val direction = if (sample.aspect >= 1.0) "横屏" else "竖屏"
                            DiagnosticStore.event(service, "方案", "已保存并选用 $name 的${direction}样本，绑定 $pkg")
                            close(); toast("已保存并选用：$name（$direction）")
                        } else toast("保存失败，最多保留 20 个方案；请返回助手管理")
                    }
                }
            }, 400)
        }
        val params = WindowManager.LayoutParams(dp(184), (service.resources.displayMetrics.heightPixels * 0.60f).toInt().coerceAtMost(dp(360)),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(64)
        }
        panel = overlay
        panelParams = params
        try {
            manager.addView(overlay, params)
            DiagnosticStore.event(service, "学习", "已开始学习 $name，绑定=$pkg；自动上滑暂停")
        }
        catch (error: Exception) {
            DiagnosticStore.event(service, "学习失败", "显示控制条：${error.javaClass.simpleName}: ${error.message}")
            android.util.Log.e("PatternRecorder", "Cannot show learning panel", error)
            panel = null; panelParams = null; toast("系统未允许显示学习控制条")
        }
    }

    fun close() {
        generation++
        panel?.let { runCatching { manager.removeViewImmediate(it) } }
        panel = null
        panelParams = null
        normal = null; ad = null
        finished()
    }

    fun resizePanel() {
        val view = panel ?: return
        val params = panelParams ?: return
        params.height = (service.resources.displayMetrics.heightPixels * 0.60f).toInt().coerceAtMost(dp(360))
        manager.updateViewLayout(view, params)
    }

    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(service, message, Toast.LENGTH_LONG).show()
}
