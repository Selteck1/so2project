package com.selteck.so2project.automation

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen-driven navigation for the visible market UI.
 * This controller does not press purchase buttons. It opens the selected listing,
 * enables the sticker filter and observes fresh actions/prices.
 */
class ScreenAutomationEngine(private val service: So2AccessibilityService) {
    private enum class Phase {
        WAIT_MAIN_MENU, WAIT_MARKET, CONFIGURE_FILTER, WAIT_RESULTS,
        OPEN_ITEM, ENABLE_STICKERS, WATCH_ACTIVITY, CATALOG_FILTER,
        CATALOG_SCAN, PAUSED
    }

    private var phase = Phase.WAIT_MAIN_MENU
    private var lastActionAt = 0L
    private var resetDone = false
    private var categoryDone = false
    private var searchDone = false
    private var applyDone = false
    private var stickersToggleAttempts = 0
    private var lastActivitySignature = ""
    private var lastCatalogPageSignature = ""
    private var catalogStalePages = 0
    private var catalogPageCount = 0
    private var discoveredOffers = 0
    private var isInspectingDeal = false
    private var pendingPurchase: PendingPurchase? = null
    private var baseSkinPrice: Double? = null

    private data class PendingPurchase(
        val itemName: String,
        val price: Double,
        val rowCenterY: Int
    )

    fun onFrame(page: OcrPage, bitmap: Bitmap) {
        if (!AutomationBridge.monitoringRequested || phase == Phase.PAUSED) return

        val reading = ScreenClassifier.classify(page)
        when (reading.kind) {
            ScreenKind.LOW_BALANCE -> {
                phase = Phase.PAUSED
                AutomationBridge.monitoringRequested = false
                service.handleLowBalance(bitmap)
                return
            }
            ScreenKind.ERROR_DIALOG -> {
                val okLine = page.findLineContaining("OK") ?: page.findLineContaining("ОК")
                if (okLine != null && canAct()) {
                    tapLine(page, okLine, "Закрываю окно ошибки")
                    phase = Phase.WAIT_MARKET
                } else {
                    pause("Появилось неизвестное окно ошибки. Автоматизация остановлена.")
                }
                return
            }
            ScreenKind.PURCHASE_CONFIRM -> {
                confirmPurchase(page)
                return
            }
            else -> Unit
        }

        when (AutomationBridge.mode) {
            RunMode.MONITOR -> handleMonitor(page, reading, bitmap)
            RunMode.CATALOG -> handleCatalog(page, reading, bitmap)
            null -> AutomationBridge.update(status = "Выбери режим в приложении")
        }
    }

    private fun handleMonitor(page: OcrPage, reading: ScreenReading, bitmap: Bitmap) {
        val target = targetName()
        when (phase) {
            Phase.WAIT_MAIN_MENU, Phase.WAIT_MARKET -> {
                if (reading.kind == ScreenKind.MAIN_MENU && canAct()) {
                    service.tapNormalized(0.035f, 0.50f)
                    phase = Phase.WAIT_MARKET
                    actionStatus("Открываю рынок")
                } else if (reading.kind == ScreenKind.MARKET_HOME) {
                    val filter = page.findLineContaining("Фильтр")
                    if (filter != null && canAct()) {
                        tapLine(page, filter, "Открываю фильтры рынка")
                        phase = Phase.CONFIGURE_FILTER
                        resetDone = false
                        categoryDone = false
                        searchDone = false
                        applyDone = false
                    } else {
                        AutomationBridge.update(status = "Рынок открыт. Ищу кнопку фильтра.")
                    }
                }
            }
            Phase.CONFIGURE_FILTER -> configureMonitorFilters(page, target)
            Phase.WAIT_RESULTS -> {
                if (reading.kind == ScreenKind.MARKET_HOME || reading.kind == ScreenKind.MARKET_GRID) {
                    val targetLine = page.findLineContaining(target)
                        ?: page.lines.firstOrNull {
                            val normalized = OcrPage.normalize(it.text)
                            normalized.contains(OcrPage.normalize(AutomationBridge.selectedWeapon)) &&
                                normalized.contains(OcrPage.normalize(AutomationBridge.selectedSkin))
                        }
                    if (targetLine != null && canAct()) {
                        val capturedPrice = findCardPrice(page, targetLine)
                        if (capturedPrice == null || capturedPrice <= 0.0) {
                            pause("Не удалось надёжно прочитать базовую цену выбранного скина. Покупки отключены.")
                        } else {
                            baseSkinPrice = capturedPrice
                            tapLine(
                                page,
                                targetLine,
                                "Открываю " + target + " · базовая цена " + String.format("%.2f", capturedPrice) + " G"
                            )
                            phase = Phase.OPEN_ITEM
                        }
                    } else {
                        AutomationBridge.update(status = "Ищу лот " + target + " в результатах рынка")
                    }
                }
            }
            Phase.OPEN_ITEM, Phase.ENABLE_STICKERS -> {
                if (reading.kind == ScreenKind.OFFERS || page.findLineContaining("С наклейками") != null) {
                    val toggle = page.findLineContaining("С наклейками")
                        ?: page.findLineContaining("With stickers")
                    if (toggle != null) {
                        val checkboxX = (toggle.bounds.left - page.width * 0.015f).toInt().coerceAtLeast(0)
                        val isChecked = service.isCheckboxChecked(bitmap, checkboxX, toggle.bounds.centerY())
                        if (isChecked == true) {
                            phase = Phase.WATCH_ACTIVITY
                            actionStatus("Фильтр «С наклейками» включён. Слежу за последними действиями.")
                        } else if (stickersToggleAttempts < 2 && canAct()) {
                            stickersToggleAttempts += 1
                            service.tapNormalized(
                                checkboxX.toFloat() / page.width,
                                toggle.bounds.centerY().toFloat() / page.height
                            )
                            phase = Phase.ENABLE_STICKERS
                            actionStatus("Включаю фильтр «С наклейками»")
                        } else {
                            pause("Не удалось надёжно проверить переключатель «С наклейками».")
                        }
                    }
                } else {
                    AutomationBridge.update(status = "Ожидаю список предложений по " + target)
                }
            }
            Phase.WATCH_ACTIVITY -> watchRecentActivity(page, reading, bitmap)
            else -> Unit
        }
    }

    private fun findCardPrice(page: OcrPage, title: OcrLine): Double? {
        val normalizedX = title.bounds.centerX().toFloat() / page.width
        val column = (((normalizedX - GRID_START_X) / GRID_STEP_X).roundToInt()).coerceIn(0, 3)
        val left = (page.width * (GRID_START_X + column * GRID_STEP_X)).toInt()
        val right = (left + page.width * GRID_CARD_WIDTH).toInt()

        return page.locatedPrices
            .filter { located ->
                located.bounds.centerX() in left..right &&
                    located.bounds.centerY() > title.bounds.bottom &&
                    located.bounds.centerY() - title.bounds.bottom < page.height * 0.12f
            }
            .minByOrNull { it.bounds.centerY() }
            ?.value
    }

    private fun configureMonitorFilters(page: OcrPage, target: String) {
        if (!resetDone) {
            val reset = page.findLineContaining("Сбросить")
            if (reset != null && canAct()) {
                tapLine(page, reset, "Сбрасываю фильтры")
                resetDone = true
            }
            return
        }
        if (!categoryDone) {
            val category = page.findLineContaining(AutomationBridge.selectedCategory)
            if (category != null && canAct()) {
                tapLine(page, category, "Выбираю категорию " + AutomationBridge.selectedCategory)
                categoryDone = true
            } else {
                AutomationBridge.update(status = "Не вижу категорию " + AutomationBridge.selectedCategory)
            }
            return
        }
        if (!searchDone) {
            val label = page.findLineContaining("Название")
            if (label == null) {
                AutomationBridge.update(status = "Ожидаю поле «Название» в фильтре")
                return
            }
            if (canAct()) {
                val x = (label.bounds.left + page.width * 0.115f).toFloat() / page.width
                val y = (label.bounds.bottom + page.height * 0.09f).toFloat() / page.height
                service.tapNormalized(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                if (service.replaceFocusedText(target)) {
                    searchDone = true
                    actionStatus("Ввожу " + target)
                } else {
                    pause("Android не обнаружил доступное поле ввода «Название». Останавливаюсь без случайных нажатий.")
                }
            }
            return
        }
        if (!applyDone) {
            val apply = page.findLineContaining("Применить")
            if (apply != null && canAct()) {
                tapLine(page, apply, "Применяю фильтр " + target)
                applyDone = true
                phase = Phase.WAIT_RESULTS
            }
        }
    }

    private fun handleCatalog(page: OcrPage, reading: ScreenReading, bitmap: Bitmap) {
        when (phase) {
            Phase.WAIT_MAIN_MENU, Phase.WAIT_MARKET -> {
                if (reading.kind == ScreenKind.MAIN_MENU && canAct()) {
                    service.tapNormalized(0.035f, 0.50f)
                    phase = Phase.WAIT_MARKET
                    actionStatus("Открываю рынок для заполнения базы")
                } else if (reading.kind == ScreenKind.MARKET_HOME) {
                    val filter = page.findLineContaining("Фильтр")
                    if (filter != null && canAct()) {
                        tapLine(page, filter, "Открываю фильтр наклеек")
                        phase = Phase.CATALOG_FILTER
                        resetDone = false
                        categoryDone = false
                        applyDone = false
                    }
                }
            }
            Phase.CATALOG_FILTER -> {
                if (!resetDone) {
                    val reset = page.findLineContaining("Сбросить")
                    if (reset != null && canAct()) {
                        tapLine(page, reset, "Сбрасываю фильтры каталога")
                        resetDone = true
                    }
                    return
                }
                if (!categoryDone) {
                    val stickers = page.findLineContaining("Наклейки")
                    if (stickers != null && canAct()) {
                        tapLine(page, stickers, "Выбираю категорию «Наклейки»")
                        categoryDone = true
                    } else {
                        AutomationBridge.update(status = "Не вижу категорию «Наклейки»")
                    }
                    return
                }
                val apply = page.findLineContaining("Применить")
                if (apply != null && canAct()) {
                    tapLine(page, apply, "Применяю фильтр наклеек")
                    applyDone = true
                    phase = Phase.CATALOG_SCAN
                }
            }
            Phase.CATALOG_SCAN -> {
                if (reading.kind == ScreenKind.MARKET_GRID || page.fullText.contains("Sticker", ignoreCase = true)) {
                    val cards = service.catalogScanner.scanVisibleCards(page, bitmap)
                    discoveredOffers += cards
                    catalogPageCount += 1
                    val signature = page.lines.filter {
                        it.text.contains("Sticker", ignoreCase = true)
                    }.joinToString("|") { it.text }
                    if (signature == lastCatalogPageSignature || cards == 0) {
                        catalogStalePages += 1
                    } else {
                        catalogStalePages = 0
                    }
                    lastCatalogPageSignature = signature
                    if (catalogStalePages >= 3 || catalogPageCount >= MAX_CATALOG_PAGES) {
                        phase = Phase.PAUSED
                        AutomationBridge.monitoringRequested = false
                        actionStatus(
                            "Сканирование остановлено. В базе найдено " + discoveredOffers +
                                " карточек за " + catalogPageCount + " страниц."
                        )
                    } else if (canAct()) {
                        service.swipeNormalized(0.965f, 0.78f, 0.965f, 0.36f, 420L)
                        actionStatus(
                            "Просматриваю страницу каталога " + catalogPageCount +
                                " · найдено " + discoveredOffers + " наклеек"
                        )
                    }
                } else if (reading.kind == ScreenKind.MARKET_FILTERS) {
                    AutomationBridge.update(status = "Ожидаю применения фильтра наклеек")
                }
            }
            else -> Unit
        }
    }

    private fun watchRecentActivity(
        page: OcrPage,
        reading: ScreenReading,
        bitmap: Bitmap
    ) {
        if (isInspectingDeal) return
        val activity = reading.recentActivityLines.firstOrNull {
            !it.text.contains("не было последних действий", ignoreCase = true)
        } ?: return

        val signature = activity.text.trim()
        if (signature == lastActivitySignature) return
        lastActivitySignature = signature

        val price = ScreenClassifier.extractPrices(signature).lastOrNull()
        if (price == null) {
            actionStatus("Новое действие: " + signature + ". Не удалось прочитать цену, пропускаю.")
            return
        }

        isInspectingDeal = true
        actionStatus("Новое выставление за " + String.format("%.2f", price) + " G. Проверяю три нижних лота…")
        val basePrice = baseSkinPrice
        if (basePrice == null || basePrice <= 0.0) {
            isInspectingDeal = false
            actionStatus("Не сохранена базовая цена обычного скина. Лот пропускаю.")
            return
        }
        service.catalogScanner.inspectCandidateRowsAsync(
            screen = bitmap,
            page = page,
            candidatePrice = price,
            cheapestBaseSkinPrice = basePrice,
            configuredMinStickerPrice = AutomationBridge.minStickerPrice,
            minimumProfit = AutomationBridge.minProfit
        ) { inspections ->
            isInspectingDeal = false
            if (!AutomationBridge.monitoringRequested || phase == Phase.PAUSED) return@inspectCandidateRowsAsync

            val profitable = inspections.firstOrNull { it.shouldBuy }
            if (profitable == null) {
                val best = inspections.maxByOrNull { it.estimatedProfit }
                actionStatus(
                    if (best == null) {
                        "Лоты за " + String.format("%.2f", price) + " G не удалось надёжно сопоставить с базой. Пропускаю."
                    } else {
                        "Окуп не подтверждён: расчёт " + String.format("%.2f", best.estimatedProfit) +
                            " G; неизвестных иконок: " + best.unknownIconCount + ". Пропускаю."
                    }
                )
                return@inspectCandidateRowsAsync
            }

            val buyWord = page.words
                .filter {
                    val word = OcrPage.normalize(it.text)
                    word == "купить" || word == "buy"
                }
                .minByOrNull { kotlin.math.abs(it.bounds.centerY() - profitable.centerY) }

            if (buyWord == null || !canAct()) {
                actionStatus("Окуп подтверждён, но кнопка покупки в этой строке не распознана. Пропускаю.")
                return@inspectCandidateRowsAsync
            }

            pendingPurchase = PendingPurchase(targetName(), profitable.price, profitable.centerY)
            service.tapNormalized(
                buyWord.bounds.centerX().toFloat() / page.width,
                buyWord.bounds.centerY().toFloat() / page.height
            )
            phase = Phase.WATCH_ACTIVITY
            actionStatus(
                "Окуп найден: прибыль " + String.format("%.2f", profitable.estimatedProfit) +
                    " G. Проверяю диалог подтверждения для " + targetName() + "."
            )
        }
    }

    private fun confirmPurchase(page: OcrPage) {
        val expected = pendingPurchase
        if (expected == null) {
            pause("Появилось неподтверждённое окно покупки. Ничего не нажимаю.")
            return
        }

        val normalizedText = OcrPage.normalize(page.fullText)
        if (!normalizedText.contains(OcrPage.normalize(expected.itemName))) {
            pause("Название в окне покупки не совпало с выбранным скином. Покупка отменена.")
            return
        }

        val matchingPrice = ScreenClassifier.extractPrices(page.fullText)
            .any { kotlin.math.abs(it - expected.price) < 0.005 }
        if (!matchingPrice) {
            pause("Цена в окне покупки не совпала с найденным лотом. Покупка отменена.")
            return
        }

        val buyLine = page.lines
            .filter { OcrPage.normalize(it.text).contains("купить") }
            .filter {
                it.bounds.centerY() > page.height * 0.62f &&
                    it.bounds.centerX() in (page.width * 0.32f).toInt()..(page.width * 0.68f).toInt()
            }
            .minByOrNull { kotlin.math.abs(it.bounds.centerY() - page.height * 0.74f) }

        if (buyLine == null) {
            pause("Не нашёл кнопку подтверждения покупки в ожидаемом окне. Покупка отменена.")
            return
        }

        if (canAct()) {
            tapLine(page, buyLine, "Подтверждаю совпавший лот за " + String.format("%.2f", expected.price) + " G")
            pendingPurchase = null
            phase = Phase.WATCH_ACTIVITY
        }
    }

    private fun tapLine(page: OcrPage, line: OcrLine, message: String) {
        val center = page.normalizedCenter(line)
        service.tapNormalized(center.first, center.second)
        actionStatus(message)
    }

    private fun targetName(): String =
        AutomationBridge.selectedWeapon + " \"" + AutomationBridge.selectedSkin + "\""

    private fun canAct(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return false
        lastActionAt = now
        return true
    }

    private fun actionStatus(value: String) {
        AutomationBridge.update(status = value)
    }

    private fun pause(reason: String) {
        phase = Phase.PAUSED
        AutomationBridge.monitoringRequested = false
        AutomationBridge.update(status = reason)
    }

    companion object {
        private const val ACTION_COOLDOWN_MS = 1300L
        private const val MAX_CATALOG_PAGES = 80
        private const val GRID_START_X = 0.247f
        private const val GRID_STEP_X = 0.185f
        private const val GRID_CARD_WIDTH = 0.18f
    }
}
