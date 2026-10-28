package com.example.autoswipeassistant

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val pages = mutableListOf<ScrollView>()
    private val navigation = mutableListOf<Button>()
    private val labels = mutableMapOf<String, String>()
    private var refreshRecognitionRules: (() -> Unit)? = null
    private lateinit var serviceStatus: TextView
    private lateinit var statusHint: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var lastAction: TextView
    private lateinit var recentIssuesSummary: TextView
    private lateinit var homeTargets: TextView
    private lateinit var serviceButton: Button
    private lateinit var targetListContainer: LinearLayout
    private lateinit var candidateListContainer: LinearLayout
    private lateinit var candidateCount: TextView
    private lateinit var targetCount: TextView
    private lateinit var candidateScanSwitch: Switch
    private lateinit var targetLockSwitch: Switch
    private lateinit var automationSwitch: Switch
    private lateinit var observeSwitch: Switch
    private var selectedPage = 0
    private var targetPage = 0
    private var listSignature = ""
    private var refreshCandidateDialog: (() -> Unit)? = null
    private var candidateDialog: Dialog? = null
    private var pendingDiagnosticExport: String? = null
    private lateinit var patternSummary: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshListsIfChanged()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.clearLegacyText(this)
        selectedPage = savedInstanceState?.getInt("page") ?: 0
        targetPage = savedInstanceState?.getInt("targetPage") ?: 0
        window.statusBarColor = color(R.color.background)
        window.navigationBarColor = color(R.color.surface)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildContent())
        renderTargetPackages()
        renderCandidatePackages()
        refreshStatus()
        selectPage(selectedPage)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (selectedPage != 0) selectPage(0) else finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        labels.clear()
        listSignature = ""
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        refreshCandidateDialog = null
        candidateDialog?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", selectedPage)
        outState.putInt("targetPage", targetPage)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Uses platform back navigation")
    override fun onBackPressed() {
        if (selectedPage != 0) selectPage(0) else super.onBackPressed()
    }

    private fun buildContent(): View {
        val root = column().apply {
            setBackgroundColor(color(R.color.background))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val host = FrameLayout(this)
        listOf(buildHome(), buildApplications(), buildSettings()).forEach { content ->
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(content)
                visibility = View.GONE
            }
            pages.add(scroll)
            host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedSurface(R.color.surface, R.color.border, 0)
        }
        val titles = listOf("首页", "应用", "设置")
        val icons = listOf(R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_tune)
        titles.forEachIndexed { index, title ->
            val item = secondaryButton(title) { selectPage(index) }.apply {
                minHeight = dp(64)
                textSize = 13f
                val icon = getDrawable(icons[index])!!.mutate()
                icon.setBounds(0, 0, dp(22), dp(22))
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(4)
            }
            navigation.add(item)
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        root.addView(bar)
        return root
    }

    private fun selectPage(index: Int) {
        selectedPage = index.coerceIn(0, 2)
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        pages.forEachIndexed { i, page -> page.visibility = if (i == selectedPage) View.VISIBLE else View.GONE }
        navigation.forEachIndexed { i, button ->
            val selected = i == selectedPage
            button.isSelected = selected
            button.setTextColor(color(if (selected) R.color.primary else R.color.text_secondary))
            button.compoundDrawables[1]?.setTint(color(if (selected) R.color.primary else R.color.text_secondary))
            button.background = ripple(if (selected) R.color.primary_soft else R.color.surface, 16)
        }
    }

    private fun page(title: String, subtitle: String, showAuthor: Boolean = false): LinearLayout = column().apply {
        val gutter = if (resources.configuration.screenWidthDp >= 600)
            ((resources.configuration.screenWidthDp - 560) / 2).coerceAtLeast(32) else 20
        setPadding(dp(gutter), dp(24), dp(gutter), dp(24))
        if (showAuthor) {
            val header = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(text(title, 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(secondaryButton("关于作者") { showAboutAuthorDialog() }.apply {
                textSize = 14f
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            addView(header, LinearLayout.LayoutParams(-1, -2))
        } else addView(text(title, 28f, true))
        addView(mutedText(subtitle, 14f), spacedParams(6))
    }

    private fun buildHome(): LinearLayout = page(getString(R.string.app_name), "少一次手动，多一份轻松。", showAuthor = true).apply {
        val hero = card(R.color.primary_soft)
        val heading = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val identity = column()
        identity.addView(text("追剧的小帮手", 20f, true))
        identity.addView(mutedText("识别可上滑提示，帮你滑一下", 13f), spacedParams(4))
        heading.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(heading)
        automationSwitch = preferenceSwitch("自动上滑", AppPreferences.KEY_AUTOMATION_ENABLED, true) { refreshStatus() }
        hero.addView(automationSwitch, spacedParams(8))
        hero.addView(mutedText("仅在指定应用前台生效", 13f))


        val patternsCard = card()
        patternsCard.addView(sectionLabel("当前识别方案"))
        patternSummary = text("", 17f, true)
        patternsCard.addView(patternSummary, spacedParams(8))
        patternsCard.addView(button("切换方案") { showPatternSelector() }, spacedParams(12))
        patternsCard.addView(secondaryButton("新建 / 管理学习方案") { showPatternManager() }, spacedParams(8))


        val statusCard = card()
        statusCard.addView(sectionLabel("准备状态"))
        serviceStatus = text("", 20f, true)
        statusHint = mutedText("", 14f)
        statusCard.addView(serviceStatus, spacedParams(8))
        statusCard.addView(statusHint, spacedParams(6))
        serviceButton = button("开启无障碍权限") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusCard.addView(serviceButton, spacedParams(12))
        addView(statusCard, spacedParams(16))
        addView(hero, spacedParams(12))

        val targetCard = card()
        targetCard.addView(sectionLabel("在哪些应用生效"))
        homeTargets = text("", 17f, true)
        targetCard.addView(homeTargets, spacedParams(8))
        targetCard.addView(secondaryButton("管理目标应用") { selectPage(1) }, spacedParams(12))
        addView(targetCard, spacedParams(16))
        addView(patternsCard, spacedParams(16))

        val activityCard = card()
        activityCard.addView(sectionLabel("最近一次上滑"))
        lastAction = text("", 17f, true).apply { setTextIsSelectable(true) }
        activityCard.addView(lastAction, spacedParams(8))
        activityCard.addView(secondaryButton("查看检测状态") { showRuntimeDialog() }, spacedParams(12))
        addView(activityCard, spacedParams(16))

        val issuesCard = card()
        issuesCard.addView(sectionLabel("最近未滑原因"))
        recentIssuesSummary = mutedText("暂无记录", 14f)
        issuesCard.addView(recentIssuesSummary, spacedParams(8))
        issuesCard.addView(secondaryButton("查看最近记录") { showRecentIssuesDialog() }, spacedParams(12))
        issuesCard.addView(secondaryButton("处理最近的问题") { resolveRecentIssue() }, spacedParams(8))
        addView(issuesCard, spacedParams(16))

        addView(column().apply {
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(text("后台运行帮助", 15f, true).apply { setTextColor(color(R.color.warning_text)) })
            addView(mutedText("部分手机需要允许后台运行或在最近任务中锁定“${getString(R.string.app_name)}”。若停止工作，请先检查服务连接状态。", 14f), spacedParams(4))
        }, spacedParams(16))
    }

    private fun buildApplications(): LinearLayout = page("应用", "只在你选中的应用里，帮你上滑。").apply {
        val targetCard = card()
        targetCount = sectionTitle("目标应用")
        targetCard.addView(targetCount)
        targetLockSwitch = preferenceSwitch("锁定应用列表", AppPreferences.KEY_TARGETS_LOCKED, false) {
            renderTargetPackages()
            renderCandidatePackages()
            refreshCandidateDialog?.invoke()
        }
        targetCard.addView(targetLockSwitch, spacedParams(8))
        targetCard.addView(mutedText("锁定后不能添加或删除目标应用。", 13f), spacedParams(4))
        targetListContainer = column()
        targetCard.addView(targetListContainer, spacedParams(12))
        targetCard.addView(button("从已安装应用中添加") { showCandidatesDialog(installed = true) }, spacedParams(12))
        addView(targetCard, spacedParams(24))

        val candidateCard = card()
        candidateCount = sectionTitle("发现应用")
        candidateCard.addView(candidateCount)
        candidateCard.addView(mutedText("可直接从已安装应用中添加，也可打开目标应用后回来选择。下方展示最近访问的 3 个候选。", 14f), spacedParams(6))
        candidateScanSwitch = preferenceSwitch("发现新应用", AppPreferences.KEY_CANDIDATE_SCAN_ENABLED, true) {
            renderCandidatePackages()
        }
        candidateCard.addView(candidateScanSwitch, spacedParams(8))
        candidateListContainer = column()
        candidateCard.addView(candidateListContainer, spacedParams(8))
        candidateCard.addView(button("搜索 / 管理全部候选") { showCandidatesDialog() }, spacedParams(12))
        addView(candidateCard, spacedParams(16))
    }

    private fun buildSettings(): LinearLayout = page("设置", "按你的习惯，调整识别方式。").apply {
        val patterns = card()
        patterns.addView(sectionTitle("学习方案"))
        patterns.addView(mutedText("为不同应用保存控件识别方案；默认方案支持广告标记循环或提示文字识别。", 14f), spacedParams(6))
        patterns.addView(button("新建 / 管理学习方案") { showPatternManager() }, spacedParams(12))
        addView(patterns, spacedParams(24))
        val adLoop = card()
        adLoop.addView(sectionTitle(getString(R.string.ad_loop_title)))
        adLoop.addView(preferenceSwitch(getString(R.string.ad_loop_switch), AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true) {
            refreshRecognitionRules?.invoke()
            refreshStatus()
        }, spacedParams(8))
        adLoop.addView(mutedText(getString(R.string.ad_loop_help), 14f), spacedParams(6))
        addView(adLoop, spacedParams(24))
        addView(buildRecognitionRulesCard(), spacedParams(16))
        val advanced = card()
        advanced.addView(sectionTitle("调试与状态"))
        observeSwitch = preferenceSwitch("观察模式", AppPreferences.KEY_OBSERVE_ONLY, false) { refreshStatus() }
        advanced.addView(observeSwitch, spacedParams(8))
        advanced.addView(mutedText("开启后只识别、记录，不执行上滑。日常使用保持关闭即可。", 14f), spacedParams(4))
        advanced.addView(secondaryButton("查看检测状态") { showRuntimeDialog() }, spacedParams(12))
        advanced.addView(button("开发诊断 / 文字快照") { showDiagnostics() }, spacedParams(8))
        advanced.addView(secondaryButton("无障碍权限设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, spacedParams(8))
        addView(advanced, spacedParams(16))
        val about = card()
        about.addView(sectionTitle(getString(R.string.about_app, getString(R.string.app_name))))
        about.addView(mutedText("点点PPK制作 · 完全免费的产品", 14f), spacedParams(6))
        about.addView(secondaryButton("关于作者与使用帮助") { showAboutAuthorDialog() }, spacedParams(12))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        about.addView(mutedText("版本 $version · 本地识别，无需联网", 12f), spacedParams(12))
        addView(about, spacedParams(16))
    }

    private fun showPatternSelector() {
        val learned = PatternStore.list(this)
        val ids = listOf(PatternStore.DEFAULT_ID) + learned.map { it.id }
        val names = listOf(PatternStore.DEFAULT_NAME) + learned.map { "${it.name} · ${appLabel(it.packageName)} · ${patternDirections(it)}" }
        val active = AppPreferences.prefs(this).getString(PatternStore.KEY_ACTIVE, PatternStore.DEFAULT_ID)
        AlertDialog.Builder(this).setTitle("选择识别方案")
            .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(active).coerceAtLeast(0)) { dialog, index ->
                PatternStore.select(this, ids[index])
                DiagnosticStore.event(this, "方案", "用户切换方案：${names[index]}")
                refreshStatus()
                toast("已切换：${names[index]}")
                dialog.dismiss()
            }
            .setNeutralButton("管理方案") { _, _ -> showPatternManager() }
            .setNegativeButton("取消", null).show()
    }

    private fun showPatternManager() {
        val patterns = PatternStore.list(this)
        val names = listOf("默认方案 · 红果短剧 / 红果漫剧") + patterns.map { "${it.name}\n${appLabel(it.packageName)} · ${patternDirections(it)}" }
        AlertDialog.Builder(this).setTitle("学习方案 · ${patterns.size}/${PatternStore.LIMIT}")
            .setItems(names.toTypedArray()) { _, index ->
                if (index == 0) {
                    AlertDialog.Builder(this).setTitle(PatternStore.DEFAULT_NAME)
                        .setMessage("使用原有文字检测、二次确认和防重复逻辑。默认方案不能删除；原有自定义文字设置也保留在此方案中。\n\n仍只在目标列表里的应用前台生效。")
                        .setPositiveButton("选用默认方案") { _, _ -> PatternStore.select(this, PatternStore.DEFAULT_ID); refreshStatus() }
                        .setNegativeButton("关闭", null).show()
                } else showPatternDetails(patterns[index - 1])
            }
            .setPositiveButton("新建学习方案") { _, _ -> beginLearningDialog() }
            .setNegativeButton("关闭", null).show()
    }

    private fun showPatternDetails(pattern: LearnedPattern) {
        AlertDialog.Builder(this).setTitle(pattern.name)
            .setItems(arrayOf("选用此方案", "修改名称", "补充 / 重学当前方向", "删除方案")) { _, index ->
                when (index) {
                    0 -> { PatternStore.select(this, pattern.id); refreshStatus(); toast("已选用：${pattern.name}") }
                    1 -> renamePattern(pattern)
                    2 -> beginLearningDialog(pattern)
                    3 -> AlertDialog.Builder(this).setTitle("删除 ${pattern.name}？")
                        .setMessage("删除当前方案会切回默认方案，不删除目标应用。")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            PatternStore.delete(this, pattern.id); refreshStatus(); toast("方案已删除")
                        }.show()
                }
            }.setNeutralButton("方案说明") { _, _ ->
                AlertDialog.Builder(this).setTitle(pattern.name)
                    .setMessage("绑定应用：${appIdentity(pattern.packageName)}\n已学习：${patternDirections(pattern)}\n\n横竖屏会自动选择对应样本，无需手动切换。缺少的方向可在该方向下补充学习；不同广告样式仍可能需要另建方案。")
                    .setPositiveButton("知道了", null).show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun renamePattern(pattern: LearnedPattern) {
        val name = EditText(this).apply {
            setText(pattern.name); setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
            contentDescription = "方案名称"
        }
        val body = column().apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            val label = text("方案名称（最多 30 字）", 15f)
            name.id = View.generateViewId(); label.labelFor = name.id
            addView(label); addView(name, spacedParams(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("修改名称").setView(body)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (!validPatternName(value, pattern.id)) { name.error = "请输入不重复的方案名称"; return@setOnClickListener }
                PatternStore.save(this, pattern.copy(name = value))
                refreshStatus(); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validPatternName(value: String, id: String?) = value.isNotBlank() && value.length <= 30 &&
        PatternStore.list(this).none { it.id != id && it.name.equals(value, true) }

    private fun beginLearningDialog(existing: LearnedPattern? = null) {
        val targets = AppPreferences.targetPackages(this).filter { it != packageName }.sortedBy(::appLabel)
        if (targets.isEmpty() || (existing != null && existing.packageName !in targets)) {
            toast("请先在“应用”页添加要学习的目标应用"); selectPage(1); return
        }
        if (existing == null && PatternStore.list(this).size >= PatternStore.LIMIT) {
            toast("最多保存 20 个方案，请先删除不需要的方案"); return
        }
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val name = EditText(this).apply {
            id = View.generateViewId(); setSingleLine(true)
            hint = "例如：A软件日常模式"
            setText(existing?.name ?: "")
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
        }
        body.addView(text("方案名称", 16f, true).apply { labelFor = name.id })
        body.addView(name, spacedParams(8))
        val apps = Spinner(this).apply {
            id = View.generateViewId(); minimumHeight = dp(48)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, targets.map(::appLabel))
            if (existing != null) { setSelection(targets.indexOf(existing.packageName)); isEnabled = false }
        }
        body.addView(text("绑定目标应用", 16f, true).apply { labelFor = apps.id }, spacedParams(16))
        body.addView(apps, spacedParams(8))
        body.addView(mutedText("开始后会显示学习控制条。依次采样：\n1. 正常视频\n2. 广告刚出现、尚不能划走\n3. 同一条广告可以划走时\n\n请在要补充的横屏或竖屏方向完成三次采样。运行时会自动选用对应方向，无需切换方案。学习期间暂停自动上滑，失败或取消不会覆盖原方案。", 14f), spacedParams(12))
        body.addView(mutedText("控件学习为实验功能。没有可读取的状态差异时无法学习；不保证覆盖所有广告或应用。", 13f), spacedParams(8))
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "新建学习方案" else "重新学习方案")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("开始学习", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (!validPatternName(value, existing?.id)) { name.error = "请输入不重复的方案名称"; name.requestFocus(); return@setOnClickListener }
                if (!isAccessibilityServiceEnabled() || !DiagnosticStore.healthy()) {
                    toast("无障碍服务尚未连接，请先开启服务后重试"); return@setOnClickListener
                }
                val pkg = targets[apps.selectedItemPosition]
                val request = org.json.JSONObject().put("id", existing?.id ?: java.util.UUID.randomUUID().toString())
                    .put("name", value).put("package", pkg).put("at", System.currentTimeMillis())
                AppPreferences.prefs(this).edit().putString(PatternStore.KEY_REQUEST, request.toString()).apply()
                dialog.dismiss()
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) runCatching { startActivity(launch) }.onFailure { toast("请手动打开目标应用，按控制条完成学习") }
                else toast("请手动打开目标应用，按控制条完成学习")
            }
        }
        dialog.show()
    }

    private fun buildRecognitionRulesCard(): View {
        val prefs = AppPreferences.prefs(this)
        val rulesCard = card()
        rulesCard.addView(sectionTitle("识别文字"))
        rulesCard.addView(mutedText("以下文字规则仅用于默认方案（红果短剧 / 红果漫剧）；学习方案使用采样的控件特征。", 14f), spacedParams(top = 6))
        val mode = Switch(this).apply {
            text = "使用自定义文字"
            textSize = 16f
            minHeight = dp(52)
            setTextColor(color(R.color.text_primary))
            isChecked = prefs.getBoolean(AppPreferences.KEY_CUSTOM_RULES_ENABLED, false)
        }
        rulesCard.addView(mode, spacedParams(top = 12))
        val status = text("", 14f, true)
        rulesCard.addView(status, spacedParams(top = 8))
        var editingCustom = false
        val editRulesButton = secondaryButton("编辑自定义文字") {}
        rulesCard.addView(editRulesButton, spacedParams(top = 12))
        val customFields = column()
        rulesCard.addView(customFields, spacedParams(top = 8))
        val editorLabel = text("自定义可上滑提示 · 每行一条", 16f, true)
        customFields.addView(editorLabel, spacedParams(top = 16))
        val editor = EditText(this).apply {
            id = RULE_EDITOR_ID
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 6
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedSurface(R.color.surface_muted, R.color.input_border, 10)
            hint = "例如：上滑继续看短剧\n上滑继续观看短剧"
            setText(prefs.getString(AppPreferences.KEY_CUSTOM_PROMPTS, "上滑继续看短剧\n上滑继续观看短剧"))
        }
        editorLabel.labelFor = editor.id
        customFields.addView(editor, spacedParams(top = 8))
        customFields.addView(mutedText("页面文字包含任意一条即可匹配，忽略空格与换行。请填写完整提示，避免只写“继续”等常见短词。倒计时等待、二次确认和防重复保护仍然有效。", 14f), spacedParams(top = 8))
        fun updateStatus() {
            val adLoopEnabled = prefs.getBoolean(AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true)
            mode.isEnabled = !adLoopEnabled
            customFields.visibility = if (!adLoopEnabled && (mode.isChecked || editingCustom)) View.VISIBLE else View.GONE
            editRulesButton.visibility = if (mode.isChecked || adLoopEnabled) View.GONE else View.VISIBLE
            editRulesButton.text = if (editingCustom) "收起编辑" else "编辑自定义文字"
            val stored = prefs.getString(AppPreferences.KEY_CUSTOM_PROMPTS, "").orEmpty()
            val count = PromptDetector.parseCustomPrompts(stored).size
            val dirty = PromptDetector.parseCustomPrompts(editor.text.toString()) != PromptDetector.parseCustomPrompts(stored)
            status.text = if (adLoopEnabled) "默认方案当前使用广告标记循环；关闭上方开关后恢复提示文字规则" else
                if (mode.isChecked) "当前使用：自定义文字（$count 条）" else "当前使用：默认提示规则"
            if (!adLoopEnabled && dirty && (mode.isChecked || editingCustom)) status.append("\n输入内容尚未保存")
        }
        fun saveCustomRules(): Boolean {
            val prompts = PromptDetector.parseCustomPrompts(editor.text.toString())
            if (prompts.isEmpty()) {
                editor.error = "请至少填写一条提示文字"
                editor.requestFocus()
                return false
            }
            prefs.edit().putString(AppPreferences.KEY_CUSTOM_PROMPTS, prompts.joinToString("\n")).apply()
            editor.error = null
            return true
        }
        styleSwitch(mode)
        editRulesButton.setOnClickListener { editingCustom = !editingCustom; updateStatus() }
        mode.setOnCheckedChangeListener { _, checked ->
            if (checked && !saveCustomRules()) {
                mode.isChecked = false
                editingCustom = true
            } else {
                prefs.edit().putBoolean(AppPreferences.KEY_CUSTOM_RULES_ENABLED, checked).apply()
            }
            updateStatus()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateStatus() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        customFields.addView(button("保存自定义文字") {
            if (saveCustomRules()) {
                updateStatus()
                Toast.makeText(this, if (mode.isChecked) "已保存，自定义文字已生效" else "已保存；开启自定义模式后生效", Toast.LENGTH_SHORT).show()
            }
        }, spacedParams(top = 12))
        updateStatus()
        refreshRecognitionRules = { updateStatus() }
        return rulesCard
    }

    private fun showAboutAuthorDialog() {
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))

            addView(text("使用者你好！我是点点PPK。", 18f, true))
            addView(
                text("这是一个完全免费的产品。如果在使用中遇到一些小问题，欢迎通过抖音私信向我反馈。", 16f),
                spacedParams(top = 12)
            )

            addView(text("联系我", 16f, true), spacedParams(top = 20))
            addView(text("抖音：${getString(R.string.author_douyin_id)}", 16f, true).apply {
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedSurface(R.color.primary_soft, R.color.border, 10)
            }, spacedParams(top = 8))
            addView(text("这是一个开源项目，如果你感兴趣，可以关注我的抖音获取源码", 16f), spacedParams(top = 12))

            addView(text("鸿蒙系统说明", 16f, true), spacedParams(top = 20))
            addView(
                text("目前鸿蒙系统可能存在无法获取无障碍权限的情况，深感抱歉。", 16f),
                spacedParams(top = 6)
            )

            addView(text("首次识别小贴士", 16f, true), spacedParams(top = 20))
            addView(
                text(
                    "先开启无障碍服务，在“应用”页添加目标应用，并保持默认方案和“广告标记循环上滑”开启。建议先用观察模式核对识别，再开启自动上滑。\n\n默认识别独立的“广告”标记；红果短剧还适配部分直播推广页面，连续两次完整读取确认后才上滑。每次手势结束至少等待 1 秒重新检查；首次没有广告就暂停手势，连续 3 次完整读取没有广告后结束本轮。“领取”“立即领取”或空文字不会单独触发上滑。\n\n请全屏播放，收起助手小窗、通知栏和遮挡手势的悬浮窗。单轮最多尝试 60 次或累计有效检测 90 秒。漏滑时先查看首页的“最近未滑原因”，并检查后台运行限制。\n\n节点无法提供识别依据时可尝试控件学习，不是所有广告都能识别。开发诊断默认关闭；主动开启后记录可能含页面个人信息，分享前请脱敏，排查后关闭并清空。",
                    16f
                ),
                spacedParams(top = 6)
            )
        }

        val scrollContent = ScrollView(this).apply {
            isFillViewport = true
            addView(dialogContent)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("关于作者")
            .setView(scrollContent)
            .setNeutralButton("复制抖音号") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("抖音号", getString(R.string.author_douyin_id)))
                toast("抖音号已复制")
            }
            .setPositiveButton("知道了", null)
            .create()

        dialog.show()
    }


    private fun refreshListsIfChanged() {
        val prefs = AppPreferences.prefs(this)
        val signature = listOf(
            prefs.getString(AppPreferences.KEY_TARGET_PACKAGES, ""),
            prefs.getString(AppPreferences.KEY_DETECTED_PACKAGES, ""),
            prefs.getString(PatternStore.KEY_ACTIVE, PatternStore.DEFAULT_ID),
            prefs.getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false).toString(),
            prefs.getBoolean(AppPreferences.KEY_CANDIDATE_SCAN_ENABLED, true).toString()
        ).joinToString("|")
        if (signature == listSignature) return
        listSignature = signature
        renderTargetPackages()
        renderCandidatePackages()
        refreshCandidateDialog?.invoke()
    }

    private fun candidatePackages(): List<String> {
        val targets = AppPreferences.targetPackages(this)
        return AppPreferences.detectedPackages(this).asReversed().filterNot {
            it in targets || it in IGNORED_CANDIDATE_PACKAGES || it == packageName
        }
    }

    private fun renderTargetPackages() {
        targetListContainer.removeAllViews()
        val targets = AppPreferences.targetPackages(this).sortedBy(::appLabel)
        val locked = AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)
        targetCount.text = getString(R.string.target_count, targets.size)
        targetLockSwitch.text = if (locked) "应用列表已锁定" else "锁定应用列表"
        homeTargets.text = when {
            targets.isEmpty() -> "还没选择应用"
            targets.size == 1 -> appLabel(targets.first())
            else -> "${appLabel(targets.first())} 等 ${targets.size} 个应用"
        }
        if (targets.isEmpty()) {
            targetListContainer.addView(emptyState("先添加一个应用", "点击“从已安装应用中添加”，选择需要自动上滑的应用。"))
            return
        }
        val pageCount = (targets.size + TARGET_PAGE_SIZE - 1) / TARGET_PAGE_SIZE
        targetPage = targetPage.coerceIn(0, pageCount - 1)
        targets.drop(targetPage * TARGET_PAGE_SIZE).take(TARGET_PAGE_SIZE).forEach { pkg ->
            targetListContainer.addView(applicationRow(pkg, if (locked) "已锁定" else "删除", !locked) {
                requestRemovePackage(pkg)
            }, spacedParams(8))
        }
        if (pageCount > 1) {
            val pager = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            pager.addView(secondaryButton("上一页") { targetPage--; renderTargetPackages() }.apply {
                isEnabled = targetPage > 0
            }, LinearLayout.LayoutParams(0, -2, 1f))
            pager.addView(mutedText("${targetPage + 1} / $pageCount", 13f).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(64), -2))
            pager.addView(secondaryButton("下一页") { targetPage++; renderTargetPackages() }.apply {
                isEnabled = targetPage < pageCount - 1
            }, LinearLayout.LayoutParams(0, -2, 1f))
            targetListContainer.addView(pager, spacedParams(12))
        }
    }

    private fun renderCandidatePackages() {
        val candidates = candidatePackages()
        val prefs = AppPreferences.prefs(this)
        val locked = prefs.getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)
        val scan = prefs.getBoolean(AppPreferences.KEY_CANDIDATE_SCAN_ENABLED, true)
        candidateCount.text = getString(R.string.candidate_count, candidates.size)
        candidateScanSwitch.text = if (scan) "发现新应用 · 已开启" else "发现新应用 · 已暂停"
        candidateListContainer.removeAllViews()
        if (candidates.isEmpty()) {
            candidateListContainer.addView(emptyState("暂无候选应用",
                if (scan) "请打开目标应用，再通过最近任务返回这里。" else "开启“发现新应用”后，打开一次目标应用。"))
        }
        candidates.take(3).forEach { pkg ->
            candidateListContainer.addView(applicationRow(pkg, if (locked) "已锁定" else "添加", !locked) {
                addCandidatePackage(pkg)
            }, spacedParams(8))
        }
        if (candidates.size > 3) candidateListContainer.addView(
            mutedText("还有 ${candidates.size - 3} 个候选，可在全部列表中搜索。", 13f), spacedParams(12))
    }

    private fun addCandidatePackage(pkg: String) {
        if (AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)) {
            toast("请先解锁目标应用列表"); return
        }
        val targets = AppPreferences.targetPackages(this).toMutableSet()
        if (!targets.add(pkg)) return
        AppPreferences.saveTargetPackages(this, targets)
        AppPreferences.removeDetectedPackage(this, pkg)
        refreshListsIfChanged()
        refreshStatus()
        toast("已添加：${appLabel(pkg)}")
    }

    private fun requestRemovePackage(pkg: String) {
        if (AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)) return
        AlertDialog.Builder(this)
            .setTitle("删除目标应用？")
            .setMessage("${appLabel(pkg)}\n$pkg\n\n删除后，不再在该应用中自动上滑。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)) {
                    toast("应用列表已锁定"); return@setPositiveButton
                }
                AppPreferences.saveTargetPackages(this, AppPreferences.targetPackages(this) - pkg)
                refreshListsIfChanged()
                refreshStatus()
            }.show()
    }

    private fun showCandidatesDialog(installed: Boolean = false) {
        val installedPackages = if (installed) packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.distinct().filter { it != packageName }.sortedBy(::appLabel)
        else emptyList<String>()
        val body = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = roundedSurface(R.color.background, R.color.border, 24)
            clipToOutline = true
        }
        body.addView(text(if (installed) "添加已安装应用" else "全部候选", 24f, true))
        val label = mutedText("搜索应用名称或包名", 14f)
        body.addView(label, spacedParams(8))
        val search = EditText(this).apply {
            id = View.generateViewId()
            hint = "例如：红果"
            setSingleLine(true)
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(48)
            background = roundedSurface(R.color.surface, R.color.input_border, 12)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { view, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
                true
            }
        }
        label.labelFor = search.id
        body.addView(search, spacedParams(8))
        val count = mutedText("", 13f)
        body.addView(count, spacedParams(8))
        val adapter = CandidateAdapter()
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
            dividerHeight = dp(8)
            isVerticalScrollBarEnabled = true
        }
        val empty = mutedText("", 15f).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val listHost = FrameLayout(this)
        listHost.addView(list, FrameLayout.LayoutParams(-1, -1))
        listHost.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty
        body.addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        val actions = LinearLayout(this)
        val clear = secondaryButton("清空候选") {
            AlertDialog.Builder(this).setTitle("清空候选记录？")
                .setMessage("不会删除已添加的目标应用。发现功能开启时，之后访问的应用仍会再次出现。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    AppPreferences.saveDetectedPackages(this, emptyList())
                    refreshListsIfChanged()
                }.show()
        }
        clear.visibility = if (installed) View.GONE else View.VISIBLE
        val close = button("完成") { candidateDialog?.dismiss() }
        actions.addView(clear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(actions, spacedParams(12))
        val refresh = {
            val all = if (installed) installedPackages.filterNot { it in AppPreferences.targetPackages(this) } else candidatePackages()
            val query = search.text.toString().trim()
            adapter.items = all.filter { it.contains(query, true) || appLabel(it).contains(query, true) }
            adapter.notifyDataSetChanged()
            val locked = AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)
            count.text = if (locked) "列表已锁定，请先回应用页解锁" else "共 ${all.size} 个 · 当前显示 ${adapter.items.size} 个"
            empty.text = if (all.isEmpty()) if (installed) "没有可添加的应用" else "暂无候选\n打开一次目标应用，再回来查看。"
                else "没有匹配的应用\n试试应用名的一部分，或包名。"
            clear.isEnabled = all.isNotEmpty()
        }
        search.addTextChangedListener(afterTextChanged { refresh() })
        refreshCandidateDialog = refresh
        candidateDialog = Dialog(this).also { dialog ->
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(body)
            dialog.setOnDismissListener { refreshCandidateDialog = null; candidateDialog = null }
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                val bounds = resources.displayMetrics
                setLayout((bounds.widthPixels * 0.94f).toInt().coerceAtMost(dp(560)), (bounds.heightPixels * 0.84f).toInt())
            }
        }
        body.isFocusableInTouchMode = true
        body.requestFocus()
        refresh()
    }

    private inner class CandidateAdapter : BaseAdapter() {
        var items: List<String> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: createApplicationRow()
            val pkg = items[position]
            val locked = AppPreferences.prefs(this@MainActivity).getBoolean(AppPreferences.KEY_TARGETS_LOCKED, false)
            bindApplicationRow(row, pkg, if (locked) "已锁定" else "添加", !locked) { addCandidatePackage(pkg) }
            return row
        }
    }

    private data class AppRow(val name: TextView, val packageText: TextView, val action: Button)

    private fun createApplicationRow(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(8), dp(12))
        background = roundedSurface(R.color.surface_muted, R.color.border, 12)
        val name = text("", 16f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val pkg = mutedText("", 12f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val identity = column().apply { addView(name); addView(pkg, spacedParams(4)) }
        addView(identity, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        val action = secondaryButton("") {}
        addView(action, LinearLayout.LayoutParams(-2, -2))
        tag = AppRow(name, pkg, action)
    }

    private fun bindApplicationRow(row: LinearLayout, pkg: String, actionLabel: String, enabled: Boolean, action: () -> Unit) {
        val holder = row.tag as AppRow
        holder.name.text = appLabel(pkg)
        val isTarget = pkg in AppPreferences.targetPackages(this)
        val applicability = if (!isTarget) "" else if (pkg in PatternStore.effectiveTargets(this))
            "\n适用于当前方案" else "\n当前方案不适用，需切换方案"
        holder.packageText.maxLines = if (isTarget) 3 else 2
        holder.packageText.text = getString(R.string.app_with_applicability, pkg, applicability)
        holder.name.contentDescription = appLabel(pkg)
        holder.packageText.contentDescription = pkg
        holder.name.tooltipText = appLabel(pkg)
        holder.packageText.tooltipText = pkg
        holder.action.text = actionLabel
        holder.action.contentDescription = "$actionLabel ${appLabel(pkg)}"
        holder.action.isEnabled = enabled
        holder.action.setTextColor(color(if (actionLabel == "删除") R.color.danger else R.color.primary))
        holder.action.alpha = if (enabled) 1f else 0.45f
        holder.action.setOnClickListener { action() }
    }

    private fun applicationRow(pkg: String, label: String, enabled: Boolean, action: () -> Unit) =
        createApplicationRow().also { bindApplicationRow(it, pkg, label, enabled, action) }

    private fun showRuntimeDialog() {
        runtimeStatus = text("", 15f).apply { setTextIsSelectable(true); setPadding(dp(24), dp(12), dp(24), dp(16)) }
        refreshStatus()
        AlertDialog.Builder(this).setTitle("检测状态")
            .setView(ScrollView(this).apply { addView(runtimeStatus) })
            .setNeutralButton("开发诊断") { _, _ -> showDiagnostics() }
            .setPositiveButton("知道了", null).show()
    }

    private fun showDiagnostics() {
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        body.addView(preferenceSwitch("开启诊断记录", DiagnosticStore.KEY_ENABLED, false) {
            DiagnosticStore.event(this, "诊断", "用户开启诊断记录")
        })
        body.addView(mutedText("先开启并清空旧记录，再全屏进入目标应用复现几秒，回来查看。诊断不会绕过前台保护，也不会自动开启提示检测。", 15f), spacedParams(8))
        body.addView(mutedText("仅本地保存最近 200 条过程与 6 份不同的目标文字快照，不参与系统备份。文字可能包含页面中的个人信息；排查后可关闭并清空。", 13f), spacedParams(8))
        body.addView(button("查看目标文字快照") { showDiagnosticText(true) }, spacedParams(16))
        body.addView(secondaryButton("查看检测过程 / 拦截原因") { showDiagnosticText(false) }, spacedParams(8))
        body.addView(secondaryButton("复制排查摘要") {
            val report = DiagnosticStore.report(this)
            val section = report.indexOf("【目标文字快照")
            val summary = report.take(8_000) + "\n\n" + report.substring(section.coerceAtLeast(0)).take(35_000) +
                "\n（复制内容有长度限制，完整记录请导出 TXT）"
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("${getString(R.string.app_name)}诊断摘要", summary))
            toast("排查摘要已复制")
        }, spacedParams(8))
        body.addView(secondaryButton("导出完整诊断 TXT") {
            pendingDiagnosticExport = DiagnosticStore.report(this)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "${getString(R.string.app_name)}诊断-${System.currentTimeMillis()}.txt")
            }
            try { startActivityForResult(intent, DIAGNOSTIC_EXPORT_REQUEST) }
            catch (_: android.content.ActivityNotFoundException) { toast("没有可用的文件保存程序，请复制排查摘要") }
        }, spacedParams(8))
        body.addView(secondaryButton("清空诊断记录") {
            AlertDialog.Builder(this).setTitle("清空诊断记录？")
                .setMessage("删除本地诊断过程和文字快照，不修改识别规则、目标应用或已导出的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ -> DiagnosticStore.clear(this); toast("诊断记录已清空") }.show()
        }, spacedParams(8))
        AlertDialog.Builder(this).setTitle("开发诊断")
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("关闭", null).show()
    }

    private fun patternDirections(pattern: LearnedPattern): String =
        listOfNotNull(pattern.primary, pattern.alternate)
            .map { if (it.landscape) "横屏" else "竖屏" }.distinct().joinToString("、")

    private fun issueLine(issue: RecentIssueStore.Issue): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(issue.at))
        return "$time · ${appLabel(issue.packageName)} · ${if (issue.landscape) "横屏" else "竖屏"}\n${issue.reason}"
    }

    private fun resolveRecentIssue() {
        val issue = RecentIssueStore.list(this).firstOrNull() ?: run { toast("暂无问题记录"); return }
        val pattern = PatternStore.active(this)
        when {
            "样本" in issue.reason && pattern != null -> beginLearningDialog(pattern)
            "服务" in issue.reason || "权限" in issue.reason -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "遮挡" in issue.reason || "窗口" in issue.reason || "焦点" in issue.reason ->
                AlertDialog.Builder(this).setTitle("恢复目标窗口")
                    .setMessage("${issue.reason}\n\n关闭通知栏、键盘及悬浮窗，全屏打开 ${appLabel(issue.packageName)} 后重试。")
                    .setPositiveButton("打开目标应用") { _, _ ->
                        val launch = packageManager.getLaunchIntentForPackage(issue.packageName)
                        if (launch == null) toast("无法打开，请检查应用是否仍已安装")
                        else runCatching { startActivity(launch) }.onFailure { toast("请手动打开目标应用") }
                    }.setNegativeButton("关闭", null).show()
            else -> showDiagnostics()
        }
    }

    private fun showRecentIssuesDialog() {
        val issues = RecentIssueStore.list(this)
        AlertDialog.Builder(this).setTitle("最近未滑原因")
            .setMessage(if (issues.isEmpty()) "暂无记录。正常播放时不一定会产生记录；遇到漏滑后请尽快回来查看。" else
                issues.joinToString("\n\n") { issueLine(it) })
            .setNeutralButton("清空记录") { _, _ ->
                RecentIssueStore.clear(this)
                refreshStatus()
                toast("最近原因已清空")
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showDiagnosticText(snapshots: Boolean) {
        val report = DiagnosticStore.report(this)
        val section = report.indexOf("【目标文字快照")
        val part = if (snapshots) report.substring(section.coerceAtLeast(0)) else report.substring(0, section.coerceAtLeast(0))
        val detail = text(part.take(65_000) + if (part.length > 65_000) "\n\n页面显示已截断，请导出完整 TXT。" else "", 13f).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        AlertDialog.Builder(this).setTitle(if (snapshots) "目标文字快照（最新在前）" else "检测过程（打开时的记录）")
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton("关闭", null).show()
    }

    @Deprecated("Uses platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DIAGNOSTIC_EXPORT_REQUEST) return
        val uri = data?.data
        val report = pendingDiagnosticExport
        pendingDiagnosticExport = null
        if (resultCode != RESULT_OK || uri == null) return
        if (report == null) { toast("导出过程被中断，请重新导出"); return }
        Thread {
            val success = runCatching {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开文件")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
            }.isSuccess
            runOnUiThread { toast(if (success) "完整诊断已导出" else "保存失败，请重新选择位置") }
        }.start()
    }

    private fun refreshStatus() {
        val prefs = AppPreferences.prefs(this)
        val granted = isAccessibilityServiceEnabled()
        val enabled = prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true)
        val targets = PatternStore.effectiveTargets(this)
        val learned = PatternStore.active(this)
        patternSummary.text = if (learned == null) PatternStore.DEFAULT_NAME else
            "${learned.name}\n${appLabel(learned.packageName)} · 已学习${patternDirections(learned)}（自动切换）"
        homeTargets.text = when {
            targets.isEmpty() -> "未配置当前方案的目标应用"
            targets.size == 1 -> appLabel(targets.first())
            else -> "${appLabel(targets.first())} 等 ${targets.size} 个应用"
        }
        val observing = prefs.getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false)
        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 &&
            getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        val readiness = AppReadiness.evaluate(granted, DiagnosticStore.connected, DiagnosticStore.healthy(),
            enabled, targets.isNotEmpty(), observing, backgroundRestricted)
        serviceStatus.text = readiness.title
        statusHint.text = readiness.hint
        serviceStatus.setTextColor(color(if (readiness.ready) R.color.success else R.color.warning_text))
        serviceButton.visibility = if (readiness.action == AppReadiness.Action.NONE) View.GONE else View.VISIBLE
        serviceButton.text = when (readiness.action) {
            AppReadiness.Action.ENABLE -> "开启自动上滑"
            AppReadiness.Action.TARGETS -> "选择目标应用"
            AppReadiness.Action.RECONNECT -> "检查无障碍服务"
            AppReadiness.Action.BATTERY -> "检查后台运行设置"
            else -> "开启无障碍权限"
        }
        serviceButton.setOnClickListener {
            when (readiness.action) {
                AppReadiness.Action.ENABLE -> automationSwitch.isChecked = true
                AppReadiness.Action.TARGETS -> selectPage(1)
                AppReadiness.Action.BATTERY -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
                else -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val status = prefs.getString(AppPreferences.KEY_LAST_STATUS, "尚无检测记录").orEmpty()
        val statusPackage = prefs.getString(AppPreferences.KEY_LAST_STATUS_PACKAGE, "").orEmpty()
        val actionAt = prefs.getLong(AppPreferences.KEY_LAST_ACTION_AT, 0L)
        val actionPackage = prefs.getString(AppPreferences.KEY_LAST_ACTION_PACKAGE, "").orEmpty()
        val actionTime = if (actionAt == 0L) "暂无上滑记录" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(actionAt))
        lastAction.text = if (actionAt == 0L) "还没有执行过上滑" else "${appLabel(actionPackage)}\n$actionTime"
        val issues = RecentIssueStore.list(this)
        recentIssuesSummary.text = if (issues.isEmpty()) "暂无记录" else issues.take(2).joinToString("\n\n") { issueLine(it) }
        if (::runtimeStatus.isInitialized) {
            runtimeStatus.text = getString(R.string.combined_runtime_details, liveDetectionDetails(),
                getString(R.string.runtime_details, status, appIdentity(statusPackage), actionTime, appIdentity(actionPackage)))
        }
    }

    private fun liveDetectionDetails(): String {
        fun stamp(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
        val gate = DetectionRuntime.windowCheck
        val read = DetectionRuntime.targetRead
        val current = if (!DiagnosticStore.healthy()) "服务尚未连接或心跳已过期，请先检查无障碍服务" else
            gate?.let { "${stamp(it.at)} · ${appLabel(it.packageName)}\n${it.message}" } ?: "尚未收到窗口检查"
        val latest = read?.let {
            getString(R.string.target_read_summary, stamp(it.at), appLabel(it.packageName),
                if (it.landscape) "横屏" else "竖屏", it.nodeCount, it.characters,
                if (it.complete) "完整" else "不完整", it.message)
        } ?: "尚无目标文字读取记录"
        return getString(R.string.live_detection_details, current, latest)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AutoSwipeAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun preferenceSwitch(label: String, key: String, default: Boolean, changed: () -> Unit): Switch =
        Switch(this).apply {
            text = label
            isChecked = AppPreferences.prefs(this@MainActivity).getBoolean(key, default)
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                AppPreferences.prefs(this@MainActivity).edit().putBoolean(key, checked).apply()
                changed()
            }
        }

    private fun styleSwitch(control: Switch) {
        control.textSize = 16f
        control.minHeight = dp(52)
        control.setTextColor(color(R.color.text_primary))
        control.switchPadding = dp(12)
        control.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.primary), color(R.color.text_secondary))
        )
        control.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.switch_track), color(R.color.input_border))
        )
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(fill: Int = R.color.surface) = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = roundedSurface(fill, if (fill == R.color.surface) R.color.border else fill, 20)
    }
    private fun emptyState(title: String, detail: String) = column().apply {
        setPadding(dp(16), dp(20), dp(16), dp(20))
        background = roundedSurface(R.color.surface_muted, R.color.border, 12)
        addView(text(title, 16f, true))
        addView(mutedText(detail, 14f), spacedParams(6))
    }
    private fun sectionTitle(value: String) = text(value, 20f, true)
    private fun sectionLabel(value: String) = mutedText(value, 13f, true)
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(R.color.text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }
    private fun mutedText(value: String, size: Float, bold: Boolean = false) =
        text(value, size, bold).apply { setTextColor(color(R.color.text_secondary)) }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = dp(64)
        textSize = 15f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(color(android.R.color.white))
        background = ripple(R.color.primary, 12)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun secondaryButton(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        setTextColor(color(R.color.primary))
        background = ripple(R.color.surface_muted, 12)
    }
    private fun ripple(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(color(R.color.ripple)),
        roundedSurface(fill, fill, radius), null
    )
    private fun roundedSurface(fillColor: Int, strokeColor: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color(fillColor))
        setStroke(dp(1), color(strokeColor))
    }
    private fun afterTextChanged(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private fun spacedParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(resourceId: Int) = getColor(resourceId)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "未知应用"
        return labels.getOrPut(pkg) {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
        }
    }
    private fun appIdentity(pkg: String) = if (pkg.isBlank()) "暂无应用记录" else "${appLabel(pkg)}\n$pkg"

    companion object {
        private const val DIAGNOSTIC_EXPORT_REQUEST = 410
        private const val TARGET_PAGE_SIZE = 4
        private const val RULE_EDITOR_ID = 0x1001
        private val IGNORED_CANDIDATE_PACKAGES = setOf(
            "android", "com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller"
        )
    }
}
