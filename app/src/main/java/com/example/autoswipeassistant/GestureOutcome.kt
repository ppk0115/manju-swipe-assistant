package com.example.autoswipeassistant

enum class GestureObservation { CHANGED, SAME, UNKNOWN }

data class GestureEvidence(val result: GestureObservation, val content: String = "") {
    companion object { val UNKNOWN = GestureEvidence(GestureObservation.UNKNOWN) }
}

/** Classifies only evidence available from the target's accessibility tree. */
object GestureOutcome {
    fun confirm(first: GestureEvidence, second: GestureEvidence): GestureObservation =
        if (first.content == second.content) confirm(first.result, second.result)
        else GestureObservation.UNKNOWN

    fun text(state: PromptState, before: String, after: String, promptCleared: Boolean,
             transitionEvent: Boolean): GestureObservation {
        if (before.isNotBlank() && after.isNotBlank() && before != after) return GestureObservation.CHANGED
        if (state == PromptState.None && promptCleared) return GestureObservation.CHANGED
        if (state != PromptState.Ready) return GestureObservation.UNKNOWN
        return if (before.isNotBlank() && before == after && !transitionEvent)
            GestureObservation.SAME else GestureObservation.UNKNOWN
    }

    fun controls(stillMatches: Boolean, before: Set<String>, after: Set<String>, promptCleared: Boolean,
                 transitionEvent: Boolean): GestureObservation {
        if (!stillMatches) return if (promptCleared) GestureObservation.CHANGED else GestureObservation.UNKNOWN
        return if (before.isNotEmpty() && before == after && !transitionEvent)
            GestureObservation.SAME else GestureObservation.UNKNOWN
    }

    fun confirm(first: GestureObservation, second: GestureObservation): GestureObservation =
        if (first == second) first else GestureObservation.UNKNOWN
}
