package com.selteck.so2project.automation

data class ScreenReading(
    val kind: ScreenKind,
    val prices: List<Double>,
    val hasStickerToggle: Boolean,
    val hasBuyButton: Boolean
)

enum class ScreenKind {
    UNKNOWN, MAIN_MENU, INVENTORY, MARKET, FILTERS, ITEM_DETAIL,
    OFFERS, PURCHASE_CONFIRM, LOW_BALANCE, ERROR_DIALOG, HISTORY
}

object ScreenClassifier {
    private val priceRegex = Regex("""(?<![\d])([0-9]{1,7}[.,][0-9]{2})\s*(?:G)?""", RegexOption.IGNORE_CASE)

    fun classify(rawText: String): ScreenReading {
        val text = rawText.lowercase()
        val kind = when {
            "недостаточно средств" in text ||
                "insufficient funds" in text ||
                ("недостаточно" in text && ("голд" in text || "пополните" in text || "баланс" in text)) ->
                ScreenKind.LOW_BALANCE
            "ошибка" in text || Regex("""\berror\b""").containsMatchIn(text) -> ScreenKind.ERROR_DIALOG
            "подтвердите покупку" in text || "confirm purchase" in text -> ScreenKind.PURCHASE_CONFIRM
            "история продаж" in text || "история покупок" in text -> ScreenKind.HISTORY
            "с наклейками" in text || "with stickers" in text -> ScreenKind.OFFERS
            "применить" in text && "сбросить" in text -> ScreenKind.FILTERS
            "сортировать по" in text || ("фильтр" in text && "рынок" in text) -> ScreenKind.MARKET
            "осмотреть" in text || "купить" in text -> ScreenKind.ITEM_DETAIL
            "инвентарь" in text -> ScreenKind.INVENTORY
            "играть" in text || "play" in text -> ScreenKind.MAIN_MENU
            else -> ScreenKind.UNKNOWN
        }
        val prices = priceRegex.findAll(rawText).mapNotNull {
            it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }.toList()
        return ScreenReading(
            kind = kind,
            prices = prices,
            hasStickerToggle = "с наклейками" in text || "with stickers" in text,
            hasBuyButton = "купить" in text || Regex("""\bbuy\b""").containsMatchIn(text)
        )
    }
}
