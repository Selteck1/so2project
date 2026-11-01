package com.selteck.so2project.automation

import android.graphics.Rect

data class OcrWord(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val lineIndex: Int
)

data class OcrLine(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val lineIndex: Int
)

data class OcrPage(
    val width: Int,
    val height: Int,
    val words: List<OcrWord>,
    val lines: List<OcrLine>
) {
    val fullText: String
        get() = lines.joinToString("\n") { it.text }

    /**
     * Price tokens with exact word rectangles. Both the offer matcher and scanner
     * consume this list so they never reconstruct click/crop locations from line text.
     */
    val locatedPrices: List<LocatedPrice>
        get() {
            val linesByIndex = lines.associateBy { it.lineIndex }
            val expression = Regex("""(?<![\\d])([0-9]{1,7}[.,][0-9]{2})(?![\\d])""")
            return words.flatMap { word ->
                expression.findAll(word.text).mapNotNull { match ->
                    match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()?.let { amount ->
                        val line = linesByIndex[word.lineIndex] ?: OcrLine(
                            text = word.text,
                            bounds = Rect(word.bounds),
                            confidence = word.confidence,
                            lineIndex = word.lineIndex
                        )
                        LocatedPrice(amount, line, Rect(word.bounds))
                    }
                }.toList()
            }
        }

    fun findLineContaining(fragment: String): OcrLine? {
        val needle = normalize(fragment)
        if (needle.isBlank()) return null
        return lines.firstOrNull { normalize(it.text).contains(needle) }
    }

    fun findLineMatching(regex: Regex): OcrLine? =
        lines.firstOrNull { regex.containsMatchIn(it.text) }

    fun linesInVerticalBand(topRatio: Float, bottomRatio: Float): List<OcrLine> {
        val top = (height * topRatio).toInt()
        val bottom = (height * bottomRatio).toInt()
        return lines.filter { it.bounds.centerY() in top..bottom }
    }

    fun normalizedCenter(line: OcrLine): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return 0.5f to 0.5f
        return (line.bounds.centerX().toFloat() / width).coerceIn(0f, 1f) to
            (line.bounds.centerY().toFloat() / height).coerceIn(0f, 1f)
    }

    companion object {
        fun normalize(value: String): String = value
            .lowercase()
            .replace('ё', 'е')
            .replace('ё', 'е')
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }

    fun toLines(): List<OcrLine> {
        if (words.isEmpty()) return emptyList()
        return words.groupBy { it.lineIndex }.toSortedMap().mapNotNull { (index, lineWords) ->
            val validWords = lineWords.filter { it.text.isNotBlank() }
            if (validWords.isEmpty()) return@mapNotNull null
            val union = Rect(validWords.first().bounds)
            validWords.drop(1).forEach { union.union(it.bounds) }
            OcrLine(
                text = validWords.joinToString(" ") { it.text },
                bounds = union,
                confidence = validWords.map { it.confidence }.average().toFloat(),
                lineIndex = index
            )
        }
    }
}
