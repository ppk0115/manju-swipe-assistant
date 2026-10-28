package com.example.autoswipeassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoSwipeAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cycle = PromptCycle()
    private val adLoop = AdSwipeLoop()
    private val liveAdConfirmation = LiveAdConfirmation()
    private var adLoopPackage = ""
    private var gestureInFlight = false
    private var flightGeneration = 0
    private var lastForegroundCheckAt = 0L
    private var windowFailure = ""
    private var windowFailurePackage = ""
    private var lastPublishedStatus = ""
    private var lastPublishedPackage = ""
    private var lastInspectAt = 0L
    private var currentPackage = ""
    private var readyConfirmationPending = false
    private var targetPollingActive = false
    private var foregroundWatchdogActive = false
    private var lastTargetPackage = ""
    private var targetLeftAt = 0L
    private var lastObservedPageFingerprint = ""
    private var handledPageFingerprint = ""
    private var pageTransitionEventAt = 0L
    private var actionStartedAt = 0L
    private var lastDeepRefreshAt = -DEEP_REFRESH_INTERVAL_MS
    private var lastCacheClearAt = -DEEP_REFRESH_INTERVAL_MS
    private var staleRecoveryReported = false
    private var currentWindowId = -1
    private var pendingWindow: WindowSnapshot? = null
    private var recognitionConfiguration = ""
    private var gestureGeneration = 0
    private var handledControlFeatures: Set<String> = emptySet()
    private var lastMatchedControlFeatures: Set<String> = emptySet()
    private var retryGesture: Runnable? = null
    private var unmatchedSince = 0L
    private var unmatchedReported = false

    private val patternRecorder by lazy {
        PatternRecorder(this, capture = {
            val window = resolveForegroundWindow()
            if (window != null && window.snapshot.packageName in AppPreferences.targetPackages(this) && window.root.refresh())
                ControlSampler.read(window.root, window.snapshot.area) else null
        }, finished = { resetPromptCycleForNewSession() })
    }

    private data class ReadableWindow(val snapshot: WindowSnapshot, val root: AccessibilityNodeInfo)

    private val delayedInspection = Runnable {
        inspectCurrentWindow(requireReadyConfirmation = false)
    }

    private val confirmReady = Runnable {
        inspectCurrentWindow(requireReadyConfirmation = true)
    }

    private val targetPolling = object : Runnable {
        override fun run() {
            val prefs = AppPreferences.prefs(this@AutoSwipeAccessibilityService)
            val shouldContinue = prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true) &&
                currentPackage in PatternStore.effectiveTargets(this@AutoSwipeAccessibilityService) && !patternRecorder.recording

            if (!shouldContinue) {
                targetPollingActive = false
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastInspectAt >= INSPECTION_THROTTLE_MS) {
                lastInspectAt = now
                inspectCurrentWindow(requireReadyConfirmation = false)
            }
            mainHandler.postDelayed(this, TARGET_POLL_INTERVAL_MS)
        }
    }

    private val foregroundWatchdog = object : Runnable {
        override fun run() {
            if (!foregroundWatchdogActive) return
            refreshForegroundState()
            mainHandler.postDelayed(this, FOREGROUND_WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        // Events can originate from background apps; only live window ownership
        // determines which application may be inspected or operated.
        if (packageName.isNotBlank() && packageName == currentPackage && event.windowId == currentWindowId) {
            recordPageTransitionEvent(event, packageName)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundCheckAt >= INSPECTION_THROTTLE_MS) {
            lastForegroundCheckAt = now
            refreshForegroundState()
        }
    }

    override fun onInterrupt() {
        DiagnosticStore.event(this, "服务", "收到 onInterrupt，取消待执行手势；不等同于权限被关闭")
        gestureGeneration++
        retryGesture?.let(mainHandler::removeCallbacks)
        retryGesture = null
        cancelPendingInspections()
        stopTargetPolling()
        updateStatus("服务被系统中断")
        noteIssue("无障碍服务被系统中断", null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppPreferences.clearLegacyText(this)
        DiagnosticStore.connection(this, true, "onServiceConnected：服务已连接")
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        updateStatus("无障碍服务已连接，等待目标应用")
        startForegroundWatchdog()
    }

    override fun onDestroy() {
        patternRecorder.close()
        DiagnosticStore.connection(this, false, "onDestroy：服务销毁（进程被直接终止时不保证收到此回调）")
        mainHandler.removeCallbacksAndMessages(null)
        targetPollingActive = false
        foregroundWatchdogActive = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelPendingInspections()
        lastDeepRefreshAt = -DEEP_REFRESH_INTERVAL_MS
        lastCacheClearAt = -DEEP_REFRESH_INTERVAL_MS
        patternRecorder.resizePanel()
    }

    private fun startForegroundWatchdog() {
        if (foregroundWatchdogActive) return
        foregroundWatchdogActive = true
        mainHandler.post(foregroundWatchdog)
    }

    private fun synchronizeConfiguration(): Boolean {
        val prefs = AppPreferences.prefs(this)
        val configuration = prefs.getString(PatternStore.KEY_ACTIVE, PatternStore.DEFAULT_ID).orEmpty() +
            "|" + prefs.getBoolean(AppPreferences.KEY_CUSTOM_RULES_ENABLED, false) +
            "|" + prefs.getString(AppPreferences.KEY_CUSTOM_PROMPTS, "") +
            "|" + prefs.getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false) +
            "|" + prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true) +
            "|" + prefs.getString(AppPreferences.KEY_TARGET_PACKAGES, "") +
            "|" + prefs.getBoolean(AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true)
        if (configuration != recognitionConfiguration) {
            recognitionConfiguration = configuration
            adLoop.reset()
            resetPromptCycleForNewSession()
            return true
        }
        return false
    }

    private fun refreshForegroundState() {
        DiagnosticStore.heartbeat()
        patternRecorder.checkRequest()
        if (patternRecorder.recording) {
            DetectionRuntime.window(currentPackage, "正在学习，自动上滑暂停")
            cancelPendingInspections()
            stopTargetPolling()
            return
        }
        val prefs = AppPreferences.prefs(this)
        synchronizeConfiguration()
        val automationEnabled = prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true)
        val discoveryEnabled = prefs.getBoolean(AppPreferences.KEY_CANDIDATE_SCAN_ENABLED, true)
        val window = if (automationEnabled || discoveryEnabled) resolveForegroundWindow() else null
        val foregroundTarget = window?.snapshot?.packageName
        if (foregroundTarget != null && discoveryEnabled) rememberCandidatePackage(foregroundTarget)
        if (!automationEnabled) {
            DetectionRuntime.window(currentPackage, "自动上滑已暂停")
            DiagnosticStore.event(this, "检测开关", "提示检测已关闭，不扫描文字")
            cancelPendingInspections()
            stopTargetPolling()
            return
        }

        val targets = PatternStore.effectiveTargets(this)

        if (foregroundTarget != null && foregroundTarget in targets) {
            DetectionRuntime.window(foregroundTarget, "${orientationName(window.snapshot)}目标窗口校验通过，正在读取")
            if (currentPackage != foregroundTarget || currentWindowId != window.snapshot.id) {
                cancelPendingInspections()
            }
            currentWindowId = window.snapshot.id
            val enteredTarget = updateCurrentPackage(foregroundTarget)
            if (enteredTarget) {
                updateStatus("已确认目标应用在前台，开始主动扫描", foregroundTarget)
            }
            startTargetPolling()
            val now = SystemClock.elapsedRealtime()
            if (now - lastInspectAt >= INSPECTION_THROTTLE_MS) {
                lastInspectAt = now
                inspectCurrentWindow(requireReadyConfirmation = false)
            }
            return
        }

        cancelPendingInspections()
        cycle.interruptEvidence()
        if (window == null) {
            DetectionRuntime.window(windowFailurePackage.ifBlank { currentPackage }, windowFailure.ifBlank { "前台窗口暂时不可读" })
            val blockedPackage = windowFailurePackage.takeIf { it in targets } ?: currentPackage.takeIf { it in targets }
            if (blockedPackage != null) {
                val reason = windowFailure.ifBlank { "目标窗口暂时不可读" }
                updateStatus(reason, blockedPackage)
                RecentIssueStore.record(this, blockedPackage,
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, reason)
            }
        }
        if (window != null) {
            val message = if (foregroundTarget == packageName)
                "助手页面或小窗当前获得焦点；收起助手并点击目标应用后再测试"
            else "当前前台应用不适用于选中的方案，等待目标应用"
            DetectionRuntime.window(foregroundTarget.orEmpty(), message)
        }
        currentWindowId = -1
        if (foregroundTarget != null) DiagnosticStore.event(this, "目标判断", "当前前台=$foregroundTarget，不在目标列表=${targets.joinToString()}")
        if (foregroundTarget != null) updateCurrentPackage(foregroundTarget)
        stopTargetPolling()
    }

    private fun cancelPendingInspections() {
        liveAdConfirmation.reset()
        adLoop.interrupt(SystemClock.elapsedRealtime())
        readyConfirmationPending = false
        pendingWindow = null
        cycle.interruptEvidence()
        mainHandler.removeCallbacks(confirmReady)
        mainHandler.removeCallbacks(delayedInspection)
    }

    private fun resolveForegroundWindow(): ReadableWindow? {
        windowFailure = ""
        windowFailurePackage = ""
        val refreshAt = SystemClock.elapsedRealtime()
        // root.refresh() alone does not invalidate cached descendants after a player rotates.
        if (Build.VERSION.SDK_INT >= 33 && currentPackage in PatternStore.effectiveTargets(this) &&
            refreshAt - lastCacheClearAt >= DEEP_REFRESH_INTERVAL_MS) {
            lastCacheClearAt = refreshAt
            clearCache()
        }
        val activeRoot = rootInActiveWindow ?: run {
            windowFailure = "前台窗口暂时不可读，请关闭弹窗并返回目标应用"
            DiagnosticStore.event(this, "窗口", windowFailure)
            return null
        }
        val windowInfos = windows
        val roots = windowInfos.associate { it.id to it.root }
        val snapshots = windowInfos.map { info ->
            val rect = Rect()
            info.getBoundsInScreen(rect)
            WindowSnapshot(info.id, roots[info.id]?.packageName?.toString().orEmpty(),
                info.type == AccessibilityWindowInfo.TYPE_APPLICATION, info.isActive, info.isFocused,
                info.layer, WindowArea(rect.left, rect.top, rect.right, rect.bottom))
        }
        val rootPackage = activeRoot.packageName?.toString().orEmpty()
        windowFailurePackage = rootPackage
        val selected = WindowSafety.select(snapshots, rootPackage)
        windowFailure = WindowSafety.failureReason(snapshots, rootPackage, activeRoot.windowId).orEmpty()
        if (DiagnosticStore.enabled(this)) {
            DiagnosticStore.event(this, "窗口", "${windowFailure.ifBlank { "窗口校验通过" }}；root=$rootPackage id=${activeRoot.windowId}\n" +
                snapshots.joinToString("\n") { "id=${it.id} pkg=${it.packageName} active=${it.active} focused=${it.focused} layer=${it.layer} bounds=${it.area}" })
        }
        if (windowFailure.isNotEmpty() || selected == null) return null
        val root = roots[selected.id] ?: run {
            windowFailure = "目标窗口节点不可读，请重新打开目标应用"
            return null
        }
        return ReadableWindow(selected, root)
    }

    private fun updateCurrentPackage(packageName: String): Boolean {
        val targets = AppPreferences.targetPackages(this)
        val previousPackage = currentPackage
        val previousWasTarget = previousPackage in targets
        val newIsTarget = packageName in targets
        val now = SystemClock.elapsedRealtime()

        if (previousWasTarget && !newIsTarget) {
            targetLeftAt = now
        }

        if (newIsTarget) {
            val firstTargetObservation = lastTargetPackage.isBlank()
            val switchedTarget = lastTargetPackage.isNotBlank() && lastTargetPackage != packageName
            val returnedAfterBackground = targetLeftAt > 0L &&
                now - targetLeftAt >= TARGET_SESSION_RESET_MS
            if (firstTargetObservation || switchedTarget || returnedAfterBackground) {
                resetPromptCycleForNewSession()
            }
            lastTargetPackage = packageName
            targetLeftAt = 0L
        }

        currentPackage = packageName
        return newIsTarget && previousPackage != packageName
    }

    private fun resetPromptCycleForNewSession() {
        gestureGeneration++
        retryGesture?.let(mainHandler::removeCallbacks)
        retryGesture = null
        cycle.resetSession()
        handledControlFeatures = emptySet()
        lastMatchedControlFeatures = emptySet()
        unmatchedSince = 0L
        unmatchedReported = false
        cancelPendingInspections()
        readyConfirmationPending = false
        lastObservedPageFingerprint = ""
        handledPageFingerprint = ""
        pageTransitionEventAt = 0L
        actionStartedAt = 0L
        lastDeepRefreshAt = -DEEP_REFRESH_INTERVAL_MS
        staleRecoveryReported = false
        mainHandler.removeCallbacks(confirmReady)
        mainHandler.removeCallbacks(delayedInspection)
    }

    private fun recordPageTransitionEvent(event: AccessibilityEvent, eventPackage: String) {
        if (!cycle.handled || actionStartedAt == 0L) return
        if (eventPackage.isNotBlank() && eventPackage != currentPackage) return
        if (event.eventTime < actionStartedAt) return

        if (
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (pageTransitionEventAt == 0L) {
                pageTransitionEventAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun startTargetPolling() {
        if (targetPollingActive) return
        targetPollingActive = true
        mainHandler.postDelayed(targetPolling, TARGET_POLL_INTERVAL_MS)
    }

    private fun stopTargetPolling() {
        if (!targetPollingActive) return
        targetPollingActive = false
        mainHandler.removeCallbacks(targetPolling)
    }

    private fun inspectCurrentWindow(requireReadyConfirmation: Boolean) {
        if (patternRecorder.recording) return
        if (synchronizeConfiguration() && requireReadyConfirmation) return
        val prefs = AppPreferences.prefs(this)
        if (!prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true)) return
        if (currentPackage !in PatternStore.effectiveTargets(this)) return
        if (gestureInFlight) return
        val learned = PatternStore.active(this)
        val useAdLoop = learned == null && prefs.getBoolean(AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true)
        if (useAdLoop && SystemClock.elapsedRealtime() < adLoop.nextCheckAt) return
        // Reacquire the tree on every eligible ad-loop check, including after each gesture.
        if (useAdLoop && Build.VERSION.SDK_INT >= 33) clearCache()

        val window = resolveForegroundWindow()
        if (window == null || window.snapshot.packageName != currentPackage || window.snapshot.id != currentWindowId) {
            cancelPendingInspections()
            updateStatus("目标窗口不在前台或暂时不可操作，已暂停上滑")
            noteIssue("目标窗口不可操作或被其他窗口遮挡", window?.snapshot)
            return
        }
        if (requireReadyConfirmation && pendingWindow != window.snapshot) {
            cancelPendingInspections()
            updateStatus("窗口已变化，本次上滑取消")
            return
        }
        val root = window.root
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage.isNotBlank() && rootPackage != currentPackage) {
            cancelPendingInspections()
            DiagnosticStore.event(this, "读取", "节点包名=$rootPackage，与目标=$currentPackage 不同，停止读取")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val deepRefresh = useAdLoop || now - lastDeepRefreshAt >= DEEP_REFRESH_INTERVAL_MS
        if (deepRefresh) {
            lastDeepRefreshAt = now
            if (cycle.handled && !staleRecoveryReported) {
                staleRecoveryReported = true
                updateStatus("上一条提示可能未刷新，正在重新读取窗口", currentPackage)
            }
        }

        if (!root.refresh()) {
            cancelPendingInspections()
            updateStatus("目标窗口节点已失效，等待重新读取")
            noteIssue("目标窗口节点失效，等待重新读取", window.snapshot)
            return
        }
        val customPrompts = if (learned == null) AppPreferences.customPrompts(this) else null
        val read = if (learned == null) AccessibilityTextReader.read(this, currentPackage, root, deepRefresh, window.snapshot.area) else null
        if (useAdLoop && read != null) {
            inspectAdLoop(window.snapshot, read)
            return
        }
        if (read != null && (!read.complete || read.text.isBlank())) {
            cancelPendingInspections()
            val message = if (read.complete) "窗口没有可读文字；画面文字可能未向无障碍提供，不能仅凭截图判断" else
                "文字读取不完整：${read.problem}；已暂停本次识别"
            DetectionRuntime.read(currentPackage, window.snapshot.area, read, message)
            updateStatus(message)
            noteIssue(message, window.snapshot)
            return
        }
        val detection = read?.detection(customPrompts)
        if (read != null) {
            lastObservedPageFingerprint = read.fingerprint(customPrompts)
        }
        val state = if (learned == null) detection?.state ?: PromptState.None else {
            val sample = ControlSampler.read(root, window.snapshot.area)
            if (!sample.complete || sample.features.isEmpty()) {
                cancelPendingInspections()
                noteIssue("控件读取不完整，已暂停本次识别", window.snapshot)
                return
            }
            if (learned.samplesFor(sample.aspect) == null) {
                val direction = if (sample.aspect >= 1.0) "横屏" else "竖屏"
                noteIssue("当前方向或窗口比例没有适用样本，请全屏后补充${direction}学习", window.snapshot)
                cancelPendingInspections()
                return
            }
            val matched = PatternMatcher.match(learned, sample)
            lastMatchedControlFeatures = if (matched) sample.features else emptySet()
            if (matched) PromptState.Ready else PromptState.None
        }
        if (read != null) {
            val message = when (state) {
                PromptState.None -> if (customPrompts != null) "已读到文字，但未命中自定义规则；可切回默认方案排查" else
                    "已读到文字，但未匹配上滑提示；诊断快照可核对右上角提示是否可读"
                PromptState.Ready -> when {
                    now < cycle.cooldownUntil -> "已识别可上滑提示，仍在 15 秒安全间隔内"
                    cycle.handled && !cycle.promptCleared -> "已识别同一提示，防重复暂未解锁"
                    else -> "已识别可上滑提示，等待二次确认"
                }
                is PromptState.Countdown -> "已识别倒计时：${state.seconds} 秒，等待可上滑状态"
            }
            DetectionRuntime.read(currentPackage, window.snapshot.area, read, message)
        }
        if (learned != null) DiagnosticStore.event(this, "控件方案", "方案=${learned.name}；绑定=${learned.packageName}；结果=$state")
        val ruleDescription = learned?.let { "控件学习：${it.name}" } ?: (customPrompts?.joinToString(" | ") ?: "默认文字规则")
        DiagnosticStore.event(this, "匹配", "应用=$currentPackage；方向=${orientationName(window.snapshot)}；阶段=${if (requireReadyConfirmation) "二次确认" else "初次扫描"}；结果=$state；命中节点=${detection?.sourceText?.take(100).orEmpty()}；生效规则=$ruleDescription" +
            if (learned == null && customPrompts != null) "；命中条目=${customPrompts.filter { read?.detection(listOf(it))?.state == PromptState.Ready }.joinToString(" | ")}" else "")
        handleDetection(state, window.snapshot, requireReadyConfirmation)
    }

    private fun inspectAdLoop(expectedWindow: WindowSnapshot, read: VisibleTextRead) {
        if (adLoopPackage != expectedWindow.packageName) {
            adLoopPackage = expectedWindow.packageName
            adLoop.reset()
            liveAdConfirmation.reset()
        }
        val now = SystemClock.elapsedRealtime()
        val badge = AdBadgeDetector.contains(read.segments)
        val liveCandidate = ShortDramaLiveAdDetector.candidate(expectedWindow.packageName, read)
        val liveConfirmed = liveAdConfirmation.inspect(now, expectedWindow, liveCandidate)
        val hasAd = badge || liveConfirmed
        val source = if (badge) "广告文字" else if (liveConfirmed) "短剧直播结构" else if (liveCandidate) "直播结构待确认" else "无"
        DiagnosticStore.event(this, "直播适配", "应用=$currentPackage；候选=$liveCandidate；已确认=$liveConfirmed；来源=$source；播放器=${read.liveAdEvidence.matchingPlayers}；剧集控件=${read.liveAdEvidence.episodeControlsVisible}")
        val observing = AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false)
        if (observing) {
            val message = if (!read.complete) "观察模式：文字读取不完整，暂停判断" else
                if (hasAd) "观察模式：识别广告（$source），未执行上滑" else if (liveCandidate)
                    "观察模式：直播推广结构等待二次确认" else "观察模式：未读到广告标记"
            DetectionRuntime.read(currentPackage, expectedWindow.area, read, message)
            updateStatus(message)
            return
        }
        if (liveCandidate && !liveConfirmed && !badge) {
            adLoop.interrupt(now)
            val message = "直播推广结构首次命中，1 秒后完整重读确认；暂不上滑"
            DetectionRuntime.read(currentPackage, expectedWindow.area, read, message)
            updateStatus(message)
            return
        }
        val decision = adLoop.inspect(now, hasAd, read.complete && read.nodeCount > 0)
        val message = when (decision) {
            AdSwipeLoop.Decision.IDLE -> "未读到广告标记，等待广告"
            AdSwipeLoop.Decision.WAIT -> "等待上一手势完成及 1 秒检查间隔"
            AdSwipeLoop.Decision.SWIPE -> "识别广告（$source），执行第 ${adLoop.attempts} 次上滑尝试"
            AdSwipeLoop.Decision.ABSENT -> "本次未读到广告标记，连续确认 ${adLoop.absentChecks}/3；暂停上滑"
            AdSwipeLoop.Decision.CLEARED -> "连续 3 次完整读取未发现广告标记，已停止本轮尝试；不等同于已确认回到剧集"
            AdSwipeLoop.Decision.UNREADABLE -> "节点读取不完整，本轮暂停；不累计广告消失次数"
            AdSwipeLoop.Decision.LIMIT -> if (adLoop.limitReason == AdSwipeLoop.LimitReason.ATTEMPTS)
                "本轮已尝试 ${adLoop.attempts} 次，达到次数上限；暂停上滑，等待广告消失或手动恢复" else
                "本轮累计有效检测广告已达 90 秒（${adLoop.attempts} 次尝试），达到时间上限；暂停上滑"
        }
        DetectionRuntime.read(currentPackage, expectedWindow.area, read, message)
        updateStatus(message)
        DiagnosticStore.event(this, "广告循环", "应用=$currentPackage；方向=${orientationName(expectedWindow)}；完整=${read.complete}；广告标记=$hasAd；结果=$decision；尝试=${adLoop.attempts}；有效计时毫秒=${adLoop.activeElapsedMs}；上限原因=${adLoop.limitReason}；连续无广告=${adLoop.absentChecks}；识别来源=$source；文字广告=$badge；直播推广=$liveConfirmed")
        if (decision == AdSwipeLoop.Decision.UNREADABLE || decision == AdSwipeLoop.Decision.LIMIT)
            noteIssue(message, expectedWindow)
        if (decision == AdSwipeLoop.Decision.SWIPE) performAdSwipe(expectedWindow)
    }

    private fun performAdSwipe(expectedWindow: WindowSnapshot) {
        val prefs = AppPreferences.prefs(this)
        val live = resolveForegroundWindow()
        if (gestureInFlight || !prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true) ||
            prefs.getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false) ||
            !prefs.getBoolean(AppPreferences.KEY_AD_BADGE_LOOP_ENABLED, true) ||
            PatternStore.active(this) != null || patternRecorder.recording ||
            expectedWindow.packageName !in PatternStore.effectiveTargets(this) ||
            live?.snapshot != expectedWindow || currentPackage != expectedWindow.packageName) {
            adLoop.gestureFinished(SystemClock.elapsedRealtime())
            cancelPendingInspections()
            updateStatus("广告循环执行前窗口或配置变化，本次取消")
            return
        }
        val plan = WindowSafety.swipePlan(expectedWindow.area)
        val path = Path().apply { moveTo(plan.x, plan.startY); lineTo(plan.x, plan.endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, plan.durationMs)).build()
        gestureInFlight = true
        val flight = ++flightGeneration
        val owner = expectedWindow.packageName
        val attempt = adLoop.attempts
        fun finished(completed: Boolean) {
            if (flight != flightGeneration) return
            gestureInFlight = false
            adLoop.gestureFinished(SystemClock.elapsedRealtime())
            if (completed) prefs.edit()
                .putLong(AppPreferences.KEY_LAST_ACTION_AT, System.currentTimeMillis())
                .putString(AppPreferences.KEY_LAST_ACTION_PACKAGE, owner).apply()
            // No immediate retry from callbacks: the polling path must read the live window again.
            DiagnosticStore.event(this, "广告手势", "应用=$owner；尝试=$attempt；完成=$completed；至少 1 秒后重新完整读取；广告是否退出尚未确认")
            if (currentPackage == owner)
                updateStatus(if (completed) "广告上滑手势已完成，1 秒后重新检查广告标记" else
                    "广告上滑未完成，1 秒后重新检查；不使用旧文字重试", owner)
        }
        DiagnosticStore.event(this, "广告手势", "准备发送：应用=$owner；尝试=$attempt；窗口=${expectedWindow.id}")
        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finished(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { finished(false) }
            }, null)
        } catch (_: RuntimeException) { false }
        if (!accepted) finished(false)
    }

    private fun handleDetection(state: PromptState, expectedWindow: WindowSnapshot,
                                requireReadyConfirmation: Boolean) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            PromptState.None -> {
                markPromptAbsentAfterAction()
                if (unmatchedSince == 0L) unmatchedSince = now
                if (!unmatchedReported && now - unmatchedSince >= UNMATCHED_NOTICE_MS) {
                    unmatchedReported = true
                    noteIssue("连续 8 秒未命中当前方案；无法仅凭此判断是否广告", expectedWindow)
                }
                if (requireReadyConfirmation) {
                    cancelPendingInspections()
                    updateStatus("二次检查未发现可上滑提示，本次取消")
                    noteIssue("二次检查未命中可上滑提示", expectedWindow)
                }
            }

            is PromptState.Countdown -> {
                unmatchedSince = 0L
                unmatchedReported = false
                if (!cycle.armCountdown(now)) return
                clearPageTransitionEvidence()
                readyConfirmationPending = false
                pendingWindow = null
                mainHandler.removeCallbacks(confirmReady)
                mainHandler.removeCallbacks(delayedInspection)
                updateStatus("检测到倒计时：${state.seconds} 秒")
                val delay = state.seconds.coerceIn(1, 60) * 1_000L + COUNTDOWN_MARGIN_MS
                mainHandler.postDelayed(delayedInspection, delay)
            }

            PromptState.Ready -> {
                unmatchedSince = 0L
                unmatchedReported = false
                val wasHandled = cycle.handled
                if (!cycle.rearmReady(now)) {
                    noteIssue(if (now < cycle.cooldownUntil) "安全间隔中：两次上滑至少间隔 15 秒" else
                        "提示仍在，等待提示消失或新倒计时；不会连续上滑", expectedWindow)
                    return
                }
                if (wasHandled) {
                    clearPageTransitionEvidence()
                    updateStatus("提示消失后再次出现，重新确认上滑", currentPackage)
                }
                if (requireReadyConfirmation) {
                    performSwipeOrObserve(expectedWindow)
                } else if (!readyConfirmationPending) {
                    readyConfirmationPending = true
                    pendingWindow = expectedWindow
                    updateStatus("发现可上滑提示，正在二次确认")
                    mainHandler.postDelayed(confirmReady, READY_CONFIRM_DELAY_MS)
                }
            }
        }
    }

    private fun performSwipeOrObserve(expectedWindow: WindowSnapshot, retry: Boolean = false) {
        if (gestureInFlight) return
        val prefs = AppPreferences.prefs(this)
        // Final live query immediately before constructing and dispatching the
        // screen-coordinate gesture. Never fall back to background target roots.
        val live = resolveForegroundWindow()
        if (!prefs.getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true) ||
            expectedWindow.packageName !in PatternStore.effectiveTargets(this) || patternRecorder.recording ||
            live?.snapshot != expectedWindow || currentPackage != expectedWindow.packageName) {
            cancelPendingInspections()
            updateStatus("执行前窗口校验失败，本次上滑取消")
            noteIssue("执行前窗口发生变化，本次上滑取消", expectedWindow)
            return
        }
        if (!cycle.beginAction(SystemClock.elapsedRealtime(), retry)) {
            cancelPendingInspections()
            return
        }
        val actionPackage = currentPackage
        retryGesture?.let(mainHandler::removeCallbacks)
        retryGesture = null
        val token = ++gestureGeneration
        handledPageFingerprint = lastObservedPageFingerprint
        handledControlFeatures = lastMatchedControlFeatures
        actionStartedAt = SystemClock.elapsedRealtime()
        pageTransitionEventAt = 0L
        lastDeepRefreshAt = -DEEP_REFRESH_INTERVAL_MS
        staleRecoveryReported = false
        mainHandler.removeCallbacks(delayedInspection)
        mainHandler.removeCallbacks(confirmReady)
        readyConfirmationPending = false
        pendingWindow = null

        if (prefs.getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false)) {
            updateStatus("观察模式：确认可以上滑，未执行手势")
            return
        }

        val area = expectedWindow.area
        val plan = WindowSafety.swipePlan(area)
        val path = Path().apply {
            moveTo(plan.x, plan.startY)
            lineTo(plan.x, plan.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    plan.durationMs
                )
            )
            .build()

        DiagnosticStore.event(this, "手势", "准备发送：应用=$actionPackage 方向=${orientationName(expectedWindow)} 尝试=${cycle.attempt} 窗口=${expectedWindow.id} x=${plan.x} y=${plan.startY}->${plan.endY} duration=${plan.durationMs}")
        gestureInFlight = true
        val flight = ++flightGeneration
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    if (flight == flightGeneration) gestureInFlight = false
                    if (token != gestureGeneration) return
                    prefs.edit()
                        .putLong(AppPreferences.KEY_LAST_ACTION_AT, System.currentTimeMillis())
                        .putString(AppPreferences.KEY_LAST_ACTION_PACKAGE, actionPackage)
                        .apply()
                    updateStatus("手势已执行，正在确认内容是否变化", actionPackage)
                    DiagnosticStore.event(this@AutoSwipeAccessibilityService, "手势", "系统回调完成；应用=$actionPackage 尝试=${cycle.attempt}；广告是否切换待确认")
                    mainHandler.postDelayed({ verifyGestureOutcome(expectedWindow, token, null) }, GESTURE_VERIFY_DELAY_MS)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    if (flight == flightGeneration) gestureInFlight = false
                    if (token != gestureGeneration) return
                    updateStatus("上滑手势被系统取消")
                    noteIssue("手势被系统取消", expectedWindow)
                    scheduleRetryAfterCooldown(expectedWindow, token)
                }
            },
            null
        )
        DiagnosticStore.event(this, "手势", "系统是否接受 dispatchGesture=$accepted；应用=$actionPackage")
        if (!accepted) {
            if (flight == flightGeneration) gestureInFlight = false
            updateStatus("系统未接受上滑手势，本次未执行", actionPackage)
            noteIssue("系统未接受上滑手势", expectedWindow)
            scheduleRetryAfterCooldown(expectedWindow, token)
        }
    }

    private fun verifyGestureOutcome(expectedWindow: WindowSnapshot, token: Int, first: GestureEvidence?) {
        if (token != gestureGeneration) return
        val observed = gestureObservation(expectedWindow)
        if (first == null) {
            mainHandler.postDelayed({ verifyGestureOutcome(expectedWindow, token, observed) }, GESTURE_VERIFY_CONFIRM_MS)
            return
        }
        val result = GestureOutcome.confirm(first, observed)
        DiagnosticStore.event(this, "手势验证", "应用=${expectedWindow.packageName} 方向=${orientationName(expectedWindow)} 尝试=${cycle.attempt} 首次=${first.result} 再次=${observed.result} 结论=$result")
        when (result) {
            GestureObservation.CHANGED -> updateStatus("已确认上滑后界面发生变化", expectedWindow.packageName)
            GestureObservation.SAME -> {
                updateStatus(if (cycle.attempt == 1) "可读界面未变化，冷却后再试一次" else "重试后可读界面仍未变化，请查看检测记录", expectedWindow.packageName)
                noteIssue(if (cycle.attempt == 1) "手势完成后仍停留在同一提示，等待安全间隔后重试" else "重试后仍停留在同一提示", expectedWindow)
                scheduleRetryAfterCooldown(expectedWindow, token)
            }
            GestureObservation.UNKNOWN -> {
                updateStatus("手势已执行，但无法确认广告是否切换", expectedWindow.packageName)
                noteIssue("手势结果无法确认；不会自动连续重试", expectedWindow)
            }
        }
    }

    private fun gestureObservation(expectedWindow: WindowSnapshot): GestureEvidence {
        val live = resolveForegroundWindow() ?: return GestureEvidence.UNKNOWN
        if (live.snapshot != expectedWindow || currentPackage != expectedWindow.packageName || !live.root.refresh())
            return GestureEvidence.UNKNOWN
        val learned = PatternStore.active(this)
        if (learned != null) {
            val sample = ControlSampler.read(live.root, live.snapshot.area)
            if (!sample.complete || sample.features.isEmpty()) return GestureEvidence.UNKNOWN
            return GestureEvidence(GestureOutcome.controls(PatternMatcher.match(learned, sample), handledControlFeatures,
                sample.features, cycle.promptCleared, pageTransitionEventAt != 0L), sample.features.sorted().joinToString("\n"))
        }
        val customPrompts = AppPreferences.customPrompts(this)
        val read = AccessibilityTextReader.read(this, currentPackage, live.root, deepRefresh = true, area = live.snapshot.area)
        if (!read.complete || read.text.isBlank()) return GestureEvidence.UNKNOWN
        val state = read.detection(customPrompts)?.state ?: return GestureEvidence.UNKNOWN
        val fingerprint = read.fingerprint(customPrompts)
        return GestureEvidence(GestureOutcome.text(state, handledPageFingerprint, fingerprint,
            cycle.promptCleared, pageTransitionEventAt != 0L), fingerprint)
    }

    private fun scheduleRetryAfterCooldown(expectedWindow: WindowSnapshot, token: Int) {
        if (cycle.attempt != 1 || token != gestureGeneration) return
        retryGesture?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            retryGesture = null
            if (token != gestureGeneration || !cycle.handled || patternRecorder.recording ||
                AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_OBSERVE_ONLY, false) ||
                !AppPreferences.prefs(this).getBoolean(AppPreferences.KEY_AUTOMATION_ENABLED, true)) return@Runnable
            if (gestureObservation(expectedWindow).result != GestureObservation.SAME) {
                DiagnosticStore.event(this, "手势验证", "安全间隔结束，原提示已变化或状态不明，取消重试")
                return@Runnable
            }
            DiagnosticStore.event(this, "手势", "安全间隔结束，同一提示仍在，执行最后一次重试")
            performSwipeOrObserve(expectedWindow, retry = true)
        }
        retryGesture = task
        mainHandler.postDelayed(task, (cycle.cooldownUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun clearPageTransitionEvidence() {
        handledPageFingerprint = ""
        pageTransitionEventAt = 0L
        actionStartedAt = 0L
        lastDeepRefreshAt = -DEEP_REFRESH_INTERVAL_MS
        staleRecoveryReported = false
    }

    private fun markPromptAbsentAfterAction() {
        if (cycle.observeAbsent(SystemClock.elapsedRealtime())) {
            updateStatus("上一条可上滑提示已消失，等待下一条广告")
        }
    }

    private fun rememberCandidatePackage(packageName: String) {
        if (packageName == this.packageName || packageName in IGNORED_RECENT_PACKAGES) return
        val prefs = AppPreferences.prefs(this)
        if (!prefs.getBoolean(AppPreferences.KEY_CANDIDATE_SCAN_ENABLED, true)) return
        if (packageName in AppPreferences.targetPackages(this)) return

        val detected = AppPreferences.detectedPackages(this)
        val updated = CandidateHistory.touch(detected, packageName)
        if (updated != detected) AppPreferences.saveDetectedPackages(this, updated)
    }

    private fun updateStatus(message: String, statusPackage: String = currentPackage) {
        if (message == lastPublishedStatus && statusPackage == lastPublishedPackage) return
        lastPublishedStatus = message
        lastPublishedPackage = statusPackage
        DiagnosticStore.event(this, "状态", "应用=$statusPackage $message")
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        AppPreferences.prefs(this).edit()
            .putString(AppPreferences.KEY_LAST_STATUS, "$time  $message")
            .putString(AppPreferences.KEY_LAST_STATUS_PACKAGE, statusPackage)
            .apply()
    }

    private fun orientationName(window: WindowSnapshot): String =
        if (window.area.width >= window.area.height) "横屏" else "竖屏"

    private fun noteIssue(reason: String, window: WindowSnapshot?) {
        val landscape = window?.let { it.area.width >= it.area.height } ?:
            (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        RecentIssueStore.record(this, window?.packageName ?: currentPackage, landscape, reason)
    }

    companion object {
        private const val INSPECTION_THROTTLE_MS = 250L
        private const val TARGET_POLL_INTERVAL_MS = 500L
        private const val FOREGROUND_WATCHDOG_INTERVAL_MS = 1_000L
        private const val TARGET_SESSION_RESET_MS = 3_000L
        private const val READY_CONFIRM_DELAY_MS = 300L
        private const val COUNTDOWN_MARGIN_MS = 250L
        // Secondary guard. The primary guard stays locked until a new countdown
        // is actually observed, so ordinary content can never re-arm it.
        private const val DEEP_REFRESH_INTERVAL_MS = 3_000L
        private const val GESTURE_VERIFY_DELAY_MS = 3_200L
        private const val GESTURE_VERIFY_CONFIRM_MS = 900L
        private const val UNMATCHED_NOTICE_MS = 8_000L
        private val IGNORED_RECENT_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller"
        )
    }
}
