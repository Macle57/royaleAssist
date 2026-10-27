package com.macle.royaleassist.ocr

/**
 * Merges lines from several recognizers run on the same image. Lines whose boxes overlap heavily
 * are the same text read by different models; we keep the one whose script fits:
 *  - a specialised recognizer (chinese/japanese/korean/devanagari) wins if most of its letters are
 *    in its script and it is not much less confident than the Latin reading;
 *  - otherwise the Latin recognizer wins (the CJK models also read Latin, usually worse);
 *  - ties fall back to confidence.
 */
object LineMerger {
    const val OVERLAP = 0.6

    fun merge(all: List<OcrLine>): List<OcrLine> {
        val remaining = all.filter { it.text.isNotBlank() }.toMutableList()
        val out = mutableListOf<OcrLine>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val cluster = mutableListOf(seed)
            val it = remaining.iterator()
            while (it.hasNext()) {
                val o = it.next()
                if (cluster.any { c -> c.box.overlapRatio(o.box) >= OVERLAP && verticallySimilar(c.box, o.box) }) {
                    cluster += o
                    it.remove()
                }
            }
            out += chooseBest(cluster)
        }
        return out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun verticallySimilar(a: Box, b: Box): Boolean {
        val h = maxOf(a.height, b.height).coerceAtLeast(1)
        return kotlin.math.abs(a.centerY - b.centerY) < h * 0.5
    }

    fun chooseBest(cluster: List<OcrLine>): OcrLine {
        if (cluster.size == 1) return cluster[0]
        val latin = cluster.filter { it.source == "latin" }.maxByOrNull { it.confidence }
        val special = cluster
            .filter { it.source != "latin" }
            .map { it to Scripts.fitFraction(it.text, it.source) }
            .filter { it.second >= 0.5 }
            .maxByOrNull { it.first.confidence + it.second.toFloat() * 0.2f }
            ?.first
        return when {
            special != null && (latin == null || special.confidence >= latin.confidence - 0.15f ||
                Scripts.fitFraction(latin.text, "latin") < 0.5) -> special
            latin != null -> latin
            else -> cluster.maxBy { it.confidence }
        }
    }
}
