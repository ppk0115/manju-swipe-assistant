package com.example.autoswipeassistant

data class AppReadiness(val title: String, val hint: String, val action: Action, val ready: Boolean = false) {
    enum class Action { AUTHORIZE, ENABLE, TARGETS, RECONNECT, BATTERY, NONE }

    companion object {
        fun evaluate(granted: Boolean, connected: Boolean, healthy: Boolean, enabled: Boolean,
                     hasTargets: Boolean, observing: Boolean, backgroundRestricted: Boolean = false): AppReadiness = when {
            !granted -> AppReadiness("请先开启无障碍权限", "授权后才能读取提示并执行上滑。", Action.AUTHORIZE)
            !enabled -> AppReadiness("自动上滑已暂停", "开启自动上滑后恢复检测。", Action.ENABLE)
            !hasTargets -> AppReadiness("请选择适用的目标应用", "当前方案没有生效的目标应用，请添加应用或切换方案。", Action.TARGETS)
            !connected -> AppReadiness("已授权，服务尚未连接", "请稍候；若一直未连接，可在系统设置中关闭再开启服务。", Action.RECONNECT)
            backgroundRestricted && !observing -> AppReadiness("后台运行受限，可能停止检测", "系统可能冻结助手。请在应用电池设置中允许后台运行，或选择无限制。", Action.BATTERY)
            !healthy -> AppReadiness("服务暂时没有响应", "最近 5 秒没有收到检查心跳，请检查无障碍服务。", Action.RECONNECT)
            observing -> AppReadiness("观察模式：不会上滑", "服务已连接，只识别和记录。可在设置中关闭观察模式。", Action.NONE)
            else -> AppReadiness("准备就绪", "服务已连接。进入目标应用后，按当前识别方式自动处理广告。", Action.NONE, true)
        }
    }
}
