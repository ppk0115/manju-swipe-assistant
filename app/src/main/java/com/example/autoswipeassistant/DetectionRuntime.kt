package com.example.autoswipeassistant

/** Live, text-free telemetry. No page labels or images are retained here or written to disk. */
object DetectionRuntime {
    data class Check(val at: Long, val packageName: String, val message: String)
    data class TargetRead(val at: Long, val packageName: String, val landscape: Boolean,
                          val nodeCount: Int, val characters: Int, val complete: Boolean, val message: String)
    var windowCheck: Check? = null
        private set
    var targetRead: TargetRead? = null
        private set

    fun window(packageName: String, message: String) {
        windowCheck = Check(System.currentTimeMillis(), packageName, message)
    }

    fun read(packageName: String, area: WindowArea, read: VisibleTextRead, message: String) {
        targetRead = TargetRead(System.currentTimeMillis(), packageName, area.width >= area.height,
            read.nodeCount, read.text.length, read.complete, message)
    }
}
