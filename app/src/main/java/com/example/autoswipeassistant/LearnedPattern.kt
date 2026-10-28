package com.example.autoswipeassistant

/** No page text or images: only visible control identities, geometry and state. */
data class ControlSample(val packageName: String, val aspect: Double, val features: Set<String>, val complete: Boolean)

data class PatternSamples(val normal: ControlSample, val advertisement: ControlSample, val ready: ControlSample) {
    val landscape get() = ready.aspect >= 1.0
}

data class LearnedPattern(
    val id: String, val name: String, val packageName: String, val normal: ControlSample,
    val advertisement: ControlSample, val ready: ControlSample, val alternate: PatternSamples? = null
) {
    val primary get() = PatternSamples(normal, advertisement, ready)

    fun samplesFor(aspect: Double): PatternSamples? =
        listOfNotNull(primary, alternate).firstOrNull { PatternMatcher.sameOrientation(it.ready.aspect, aspect) }

    fun withSamples(samples: PatternSamples): LearnedPattern = when {
        PatternMatcher.sameDirection(ready.aspect, samples.ready.aspect) ->
            copy(normal = samples.normal, advertisement = samples.advertisement, ready = samples.ready)
        alternate == null || PatternMatcher.sameDirection(alternate.ready.aspect, samples.ready.aspect) ->
            copy(alternate = samples)
        else -> copy(alternate = samples)
    }
}

object PatternMatcher {
    fun validation(normal: ControlSample, ad: ControlSample, ready: ControlSample): String? {
        if (listOf(normal, ad, ready).any { !it.complete }) return "界面节点读取不完整，请换一个节点较少的页面或重新采样"
        if (normal.packageName.isBlank() || normal.packageName != ad.packageName || ad.packageName != ready.packageName)
            return "三次采样必须来自同一个目标应用"
        if (!sameOrientation(normal, ready) || !sameOrientation(ad, ready)) return "请保持相同方向和窗口大小完成学习"
        if (ready.features.size < 6) return "可读取的控件太少，无法建立可靠规则"
        if ((ad.features intersect ready.features).size < 3) return "广告与可上滑页面差异过大，请采样同一条广告"
        if (ready.features.subtract(normal.features).size < 2) return "可上滑状态与正常视频没有足够差异，暂时无法学习"
        if (ready.features.subtract(ad.features).isEmpty()) return "广告前后没有可读取的状态差异，暂时无法学习"
        return null
    }

    fun match(pattern: LearnedPattern, sample: ControlSample): Boolean {
        if (!sample.complete || sample.packageName != pattern.packageName) return false
        val selected = pattern.samplesFor(sample.aspect) ?: return false
        if (validation(selected.normal, selected.advertisement, selected.ready) != null) return false
        val ready = selected.ready.features
        // All observed stage-specific evidence must be present, as well as most of the ready layout.
        val againstNormal = ready - selected.normal.features
        val againstAd = ready - selected.advertisement.features
        return sample.features.containsAll(againstNormal) && sample.features.containsAll(againstAd) &&
            (sample.features intersect ready).size.toDouble() / ready.size >= 0.90
    }

    fun sameDirection(a: Double, b: Double) = (a >= 1.0) == (b >= 1.0)

    fun sameOrientation(a: Double, b: Double) =
        a > 0 && b > 0 && sameDirection(a, b) && kotlin.math.abs(a / b - 1) <= 0.12

    private fun sameOrientation(a: ControlSample, b: ControlSample) = sameOrientation(a.aspect, b.aspect)
}
