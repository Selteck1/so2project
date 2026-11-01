package com.selteck.so2project.automation

import android.content.Context

data class ScreenPoint(val x: Float, val y: Float)

data class CoordinateSpec(
    val key: String,
    val title: String,
    val description: String,
    val defaultX: Float,
    val defaultY: Float
)

/**
 * Coordinates are percentages of the actual captured game frame, not the
 * phone's portrait-size displayMetrics. This keeps taps correct after rotation.
 */
object AutomationCoordinates {
    private const val PREFS = "so2_screen_coordinates"

    val specs = listOf(
        CoordinateSpec("menu_market", "Главное меню → Рынок", "Иконка тележки слева.", 0.035f, 0.50f),
        CoordinateSpec("market_filter", "Рынок → Фильтр", "Кнопка фильтра в левой панели.", 0.10f, 0.43f),
        CoordinateSpec("filter_reset", "Фильтр → Сбросить", "Сброс всех предыдущих параметров.", 0.82f, 0.81f),
        CoordinateSpec("filter_stickers", "Фильтр → Наклейки", "Тип предмета «Наклейки».", 0.13f, 0.90f),
        CoordinateSpec("sort_price", "Сортировка → Цена", "Открыть выбор сортировки по цене.", 0.12f, 0.34f),
        CoordinateSpec("sort_ascending", "Сортировка → По возрастанию", "Выбрать сначала самые дешёвые.", 0.12f, 0.31f),
        CoordinateSpec("filter_apply", "Фильтр → Применить", "Применение фильтра.", 0.82f, 0.91f),
        CoordinateSpec("stickers_toggle", "Лот → С наклейками", "Флажок должен оставаться включённым.", 0.49f, 0.37f),
        CoordinateSpec("offer_sticker_1", "Лот → Наклейка 1", "Первая фиксированная ячейка слева.", 0.66f, 0.48f),
        CoordinateSpec("offer_sticker_2", "Лот → Наклейка 2", "Вторая фиксированная ячейка.", 0.69f, 0.48f),
        CoordinateSpec("offer_sticker_3", "Лот → Наклейка 3", "Третья фиксированная ячейка.", 0.72f, 0.48f),
        CoordinateSpec("offer_sticker_4", "Лот → Наклейка 4", "Четвёртая фиксированная ячейка справа.", 0.73f, 0.48f),
        CoordinateSpec("offer_buy", "Лот → Купить", "Кнопка покупки справа от цены.", 0.92f, 0.48f),
        CoordinateSpec("purchase_confirm", "Окно покупки → Купить", "Оранжевая кнопка подтверждения.", 0.50f, 0.74f),
        CoordinateSpec("purchase_close", "Окно покупки → Закрыть", "Крестик в правом верхнем углу окна.", 0.75f, 0.23f),
        CoordinateSpec("low_balance_back", "Недостаточно голды → Назад", "Стрелка возврата на экране Gold.", 0.03f, 0.06f),
        CoordinateSpec("catalog_scroll_top", "Каталог → Полоса прокрутки сверху", "Правая часть полосы прокрутки списка.", 0.99f, 0.42f),
        CoordinateSpec("catalog_scroll_bottom", "Каталог → Полоса прокрутки снизу", "Нижняя точка свайпа по списку.", 0.99f, 0.82f)
    )

    fun get(context: Context, key: String): ScreenPoint {
        val spec = specs.firstOrNull { it.key == key }
            ?: return ScreenPoint(0.5f, 0.5f)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ScreenPoint(
            prefs.getFloat("${key}_x", spec.defaultX).coerceIn(0f, 1f),
            prefs.getFloat("${key}_y", spec.defaultY).coerceIn(0f, 1f)
        )
    }

    fun save(context: Context, key: String, x: Float, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("${key}_x", x.coerceIn(0f, 1f))
            .putFloat("${key}_y", y.coerceIn(0f, 1f))
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
