package com.psmobile.psd

/** Stages the loading screen animates through while a document is being opened. */
enum class ParsePhase(val order: Int) {
    OPENING(0),
    HEADER(1),
    RESOURCES(2),
    LAYERS(3),
    INDEXING(4),
    PREVIEW(5),
    DONE(6);

    companion object {
        val LAST = DONE.order
    }
}

data class ParseProgress(
    val phase: ParsePhase,
    /** 0..1 within the current phase, or -1 when the phase has no measurable length. */
    val fraction: Float = -1f,
    val detail: String = "",
) {
    /** 0..1 across the whole open operation, for a single continuous progress bar. */
    val overall: Float
        get() {
            val f = if (fraction < 0f) 0.5f else fraction.coerceIn(0f, 1f)
            return ((phase.order + f) / (ParsePhase.LAST + 1)).coerceIn(0f, 1f)
        }
}

fun interface ProgressListener {
    fun onProgress(progress: ParseProgress)
}
