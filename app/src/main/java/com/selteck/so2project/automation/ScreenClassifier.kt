package com.selteck.so2project.automation

import android.graphics.Rect

data class LocatedPrice(val value: Double, val line: OcrLine, val bounds: Rect)

data class ScreenReading(
    val kind: ScreenKind,
    val prices: List<Double>,
    val locatedPrices: List<LocatedPrice>,
    val hasStickerToggle: Boolean,
    val hasBuyButton: Boolean,
    val recentActivityLines: List<OcrLine>
)

enum class ScreenKind {
    UNKNOWN, MAIN_MENU, INVENTORY, MARKET_HOME, MARKET_FILTERS, MARKET_GRID,
    ITEM_DETAIL, OFFERS, PURCHASE_CONFIRM, LOW_BALANCE, ERROR_DIALOG, HISTORY
}

object ScreenClassifier {
    private val priceRegex = Regex("""(?<![\d])([0-9]{1,7}[.,][0-9]{2})(?![\d])""")
    private val activityPattern = Regex(
        """(выставил|выставила|разместил|разместила|снял|купил|продал|listed|sold|bought)""",
        RegexOption.IGNORE_CASE
    )

    fun classify(rawText: String): ScreenReading {
        val text = rawText.lowercase().replace('ё', 'е')
        val prices = extractPrices(rawText)
        return ScreenReading(
            classifyText(text), prices, emptyList(),
            "с наклейками" in text || "with stickers" in text,
            "купить" in text || Regex("""\bbuy\b""").containsMatchIn(text),
            emptyList()
        )
    }

    fun classify(page: OcrPage): ScreenReading {
        val text = page.fullText.lowercase().replace('ё', 'е')
        val lines = page.lines.associateBy { it.lineIndex }
        val located = page.words.flatMap { word ->
            priceRegex.findAll(word.text).mapNotNull { match ->
                match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()?.let { amount ->
                    val line = lines[word.lineIndex] ?: OcrLine(
                        word.text, Rect(word.bounds), word.confidence, word.lineIndex
                    )
                    LocatedPrice(amount, line, Rect(word.bounds))
                }
            }.toList()
        }
        val recent = page.linesInVerticalBand(0.80f, 1.0f)
            .filter { activityPattern.containsMatchIn(it.text) }
        return ScreenReading(
            classifyText(text),
            located.map { it.value },
            located,
            "с наклейками" in text || "with stickers" in text,
            "купить" in text || Regex("""\bbuy\b""").containsMatchIn(text),
            recent
        )
    }

    private fun classifyText(text: String): ScreenKind = when {
        "ошибка" in text || Regex("""\berror\b""").containsMatchIn(text) -> ScreenKind.ERROR_DIALOG
        ("подтвердите покупку" in text || "confirm purchase" in text) &&
            ("покупка" in text || "purchase" in text) -> ScreenKind.PURCHASE_CONFIRM
        "недостаточно средств" in text ||
            "insufficient funds" in text ||
            ("недостаточно" in text && ("голд" in text || "пополните" in text || "баланс" in text)) ->
            ScreenKind.LOW_BALANCE
        "с наклейками" in text && ("предложений" in text || "купить" in text) -> ScreenKind.OFFERS
        ("применить" in text && ("сбросить" in text || "наклейки" in text || "тип предмета" in text)) ||
            ("сбросить" in text && ("название" in text || "редкость" in text || "тип" in text)) ->
            ScreenKind.MARKET_FILTERS
        "история продаж" in text || "история покупок" in text -> ScreenKind.HISTORY
        "рынок" in text && ("мои запросы" in text || "история" in text || "фильтр" in text) -> ScreenKind.MARKET_HOME
        "играть" in text || Regex("""\bplay\b""").containsMatchIn(text) -> ScreenKind.MAIN_MENU
        "инвентарь" in text -> ScreenKind.INVENTORY
        "осмотреть" in text || ("купить" in text && "предложений" in text) -> ScreenKind.ITEM_DETAIL
        text.contains("sticker") && priceRegex.findAll(text).count() >= 3 -> ScreenKind.MARKET_GRID
        else -> ScreenKind.UNKNOWN
    }

    fun extractPrices(text: String): List<Double> =
        priceRegex.findAll(text).mapNotNull {
            it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }.toList()
}
