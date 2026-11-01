package com.selteck.so2project.automation

data class LocatedPrice(val value: Double, val line: OcrLine)

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
            kind = classifyText(text),
            prices = prices,
            locatedPrices = emptyList(),
            hasStickerToggle = "с наклейками" in text || "with stickers" in text,
            hasBuyButton = "купить" in text || Regex("""\bbuy\b""").containsMatchIn(text),
            recentActivityLines = emptyList()
        )
    }

    fun classify(page: OcrPage): ScreenReading {
        val text = page.fullText.lowercase().replace('ё', 'е')
        val located = page.lines.flatMap { line ->
            priceRegex.findAll(line.text).mapNotNull { match ->
                match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()?.let {
                    LocatedPrice(it, line)
                }
            }.toList()
        }
        val recent = page.linesInVerticalBand(0.80f, 1.0f)
            .filter { activityPattern.containsMatchIn(it.text) }
        return ScreenReading(
            kind = classifyText(text),
            prices = located.map { it.value },
            locatedPrices = located,
            hasStickerToggle = "с наклейками" in text || "with stickers" in text,
            hasBuyButton = "купить" in text || Regex("""\bbuy\b""").containsMatchIn(text),
            recentActivityLines = recent
        )
    }

    private fun classifyText(text: String): ScreenKind = when {
        "недостаточно средств" in text ||
            "insufficient funds" in text ||
            ("недостаточно" in text && ("голд" in text || "пополните" in text || "баланс" in text)) ->
            ScreenKind.LOW_BALANCE
        "ошибка" in text || Regex("""\berror\b""").containsMatchIn(text) -> ScreenKind.ERROR_DIALOG
        ("подтвердите покупку" in text || "confirm purchase" in text) &&
            ("покупка" in text || "purchase" in text) -> ScreenKind.PURCHASE_CONFIRM
        "с наклейками" in text && ("предложений" in text || "купить" in text) -> ScreenKind.OFFERS
        "применить" in text && "сбросить" in text -> ScreenKind.MARKET_FILTERS
        "история продаж" in text || "история покупок" in text -> ScreenKind.HISTORY
        "рынок" in text && ("мои запросы" in text || "история" in text || "фильтр" in text) ->
            ScreenKind.MARKET_HOME
        "играть" in text || Regex("""\bplay\b""").containsMatchIn(text) -> ScreenKind.MAIN_MENU
        "инвентарь" in text -> ScreenKind.INVENTORY
        "осмотреть" in text || ("купить" in text && "предложений" in text) -> ScreenKind.ITEM_DETAIL
        text.contains("sticker") && priceRegex.containsMatchIn(text) -> ScreenKind.MARKET_GRID
        else -> ScreenKind.UNKNOWN
    }

    fun extractPrices(text: String): List<Double> =
        priceRegex.findAll(text).mapNotNull {
            it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }.toList()
}
