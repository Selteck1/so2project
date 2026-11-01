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
        CATALOG_SCAN, CATALOG_SORT, CATALOG_OPEN_ITEM, CATALOG_WAIT_GRID, PAUSED
    }

    private var phase = Phase.WAIT_MAIN_MENU
    private var lastActionAt = 0L
    private var resetDone = false
    private var categoryDone = false
    private var searchDone = false
    private var applyDone = false
    private var stickersToggleAttempts = 0
    private val seenActivitySignatures = LinkedHashSet<String>()
    private var lastCatalogPageSignature = ""
    private var catalogStalePages = 0
    private var catalogPageCount = 0
    private var discoveredOffers = 0
    private var isInspectingDeal = false
    private var pendingPurchase: PendingPurchase? = null
    private var baseSkinPrice: Double? = null
    private var catalogSortPriceSelected = false
    private var catalogSortDirectionSelected = false
    private var catalogSortFrames = 0
    private var catalogOpenWaitFrames = 0
    private var catalogBackAttempts = 0
    private var catalogPendingTitle: String? = null
    private var catalogLastScrollSignature = ""
    private var catalogNoMoveCount = 0
    private val visitedCatalogItems = LinkedHashSet<String>()

    private data class PendingPurchase(
        val itemName: String,
        val price: Double,
        val rowCenterY: Int
    )

    fun onFrame(page: OcrPage, bitmap: Bitmap) {
        if (!AutomationBridge.monitoringRequested || AutomationBridge.paused || phase == Phase.PAUSED) return

        val reading = ScreenClassifier.classify(page)
        when (reading.kind) {
            ScreenKind.LOW_BALANCE -> {
                phase = Phase.PAUSED
                AutomationBridge.monitoringRequested = false
                service.handleLowBalance(bitmap)
                service.tapConfigured("low_balance_back", 0.03f, 0.06f)
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
                    service.tapConfigured("menu_market", 0.035f, 0.50f)
                    phase = Phase.WAIT_MARKET
                    actionStatus("Открываю рынок")
                } else if (reading.kind == ScreenKind.MARKET_HOME) {
                    val filter = page.findLineContaining("Фильтр")
                    if (filter != null && canAct()) {
                        service.tapConfigured("market_filter", 0.035f, 0.36f)
                        actionStatus("Открываю фильтры рынка")
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
                            service.tapConfigured(
                                "stickers_toggle",
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
                service.tapConfigured("filter_reset", 0.80f, 0.80f)
                actionStatus("Сбрасываю фильтры")
                resetDone = true
            }
            return
        }
        if (!categoryDone) {
            val category = page.findLineContaining(AutomationBridge.selectedCategory)
            if (category != null && canAct()) {
                service.tapConfigured("filter_stickers", category.bounds.centerX().toFloat() / page.width, category.bounds.centerY().toFloat() / page.height)
                actionStatus("Выбираю категорию " + AutomationBridge.selectedCategory)
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
                service.tapConfigured("filter_apply", apply.bounds.centerX().toFloat() / page.width, apply.bounds.centerY().toFloat() / page.height)
                actionStatus("Применяю фильтр " + target)
                applyDone = true
                phase = Phase.WAIT_RESULTS
            }
        }
    }

    private fun handleCatalog(page: OcrPage, reading: ScreenReading, bitmap: Bitmap) {
        when (phase) {
            Phase.WAIT_MAIN_MENU, Phase.WAIT_MARKET -> {
                if (reading.kind == ScreenKind.MAIN_MENU && canAct()) {
                    service.tapConfigured("menu_market", 0.035f, 0.50f)
                    phase = Phase.WAIT_MARKET
                    actionStatus("Открываю рынок для заполнения базы")
                } else if (reading.kind == ScreenKind.MARKET_HOME) {
                    val filter = page.findLineContaining("Фильтр")
                    if (filter != null && canAct()) {
                        service.tapConfigured("market_filter", 0.035f, 0.36f)
                        phase = Phase.CATALOG_FILTER
                        resetDone = false
                        categoryDone = false
                        applyDone = false
                        catalogSortPriceSelected = false
                        catalogSortDirectionSelected = false
                        actionStatus("Открываю фильтр каталога")
                    }
                }
            }

            Phase.CATALOG_FILTER -> {
                if (reading.kind != ScreenKind.MARKET_FILTERS) {
                    AutomationBridge.update(status = "Ожидаю окно фильтра. На экране сейчас: " + reading.kind)
                    return
                }
                if (!resetDone) {
                    val reset = page.findLineContaining("Сбросить")
                    if (reset != null && canAct()) {
                        service.tapConfigured("filter_reset", reset.bounds.centerX().toFloat() / page.width, reset.bounds.centerY().toFloat() / page.height)
                        resetDone = true
                        actionStatus("Сбрасываю все фильтры")
                    }
                    return
                }
                if (!categoryDone) {
                    val stickers = page.findLineContaining("Наклейки")
                    if (stickers != null && canAct()) {
                        service.tapConfigured("filter_stickers", stickers.bounds.centerX().toFloat() / page.width, stickers.bounds.centerY().toFloat() / page.height)
                        categoryDone = true
                        actionStatus("Выбираю тип предмета «Наклейки»")
                    } else {
                        AutomationBridge.update(status = "Ищу категорию «Наклейки» в левом списке фильтра")
                    }
                    return
                }
                if (!applyDone) {
                    val apply = page.findLineContaining("Применить")
                    if (apply != null && canAct()) {
                        service.tapConfigured("filter_apply", apply.bounds.centerX().toFloat() / page.width, apply.bounds.centerY().toFloat() / page.height)
                        applyDone = true
                        phase = Phase.CATALOG_SORT
                        actionStatus("Применяю фильтр наклеек")
                    }
                }
            }

            Phase.CATALOG_SORT, Phase.CATALOG_SCAN -> {
                if (reading.kind != ScreenKind.MARKET_GRID &&
                    !page.fullText.contains("Sticker", ignoreCase = true)) {
                    AutomationBridge.update(status = "Жду список наклеек после фильтра. Экран: " + reading.kind)
                    return
                }

                val pageSignature = page.lines
                    .filter { line ->
                        val normalized = OcrPage.normalize(line.text)
                        val inGrid = line.bounds.centerX() >= page.width * GRID_START_X &&
                            line.bounds.centerY() in (page.height * 0.18f).toInt()..(page.height * 0.93f).toInt()
                        inGrid && (normalized.contains("sticker") ||
                            page.locatedPrices.any { price ->
                                price.bounds.centerX() - line.bounds.centerX() in -page.width * 0.02f..page.width * 0.20f &&
                                    price.bounds.centerY() > line.bounds.bottom &&
                                    price.bounds.centerY() - line.bounds.bottom < page.height * 0.13f
                            })
                    }
                    .joinToString("|") { OcrPage.normalize(it.text) } + "|" +
                    page.locatedPrices
                        .filter { it.bounds.centerY() in (page.height * 0.18f).toInt()..(page.height * 0.93f).toInt() }
                        .joinToString("|") { String.format("%.2f", it.value) }

                if (pageSignature.isNotBlank() && pageSignature != lastCatalogPageSignature) {
                    val saved = service.catalogScanner.scanVisibleCards(page, bitmap)
                    discoveredOffers += saved
                    catalogPageCount += 1
                    lastCatalogPageSignature = pageSignature
                    AutomationBridge.update(
                        status = "Страница $catalogPageCount: сохранено карточек $saved. Уже просмотрено ${visitedCatalogItems.size} наклеек."
                    )
                }

                if (!catalogSortPriceSelected) {
                    val priceSort = page.lines.firstOrNull {
                        OcrPage.normalize(it.text).contains("цена") &&
                            it.bounds.centerX() < page.width * 0.18f &&
                            it.bounds.centerY() < page.height * 0.38f
                    }
                    if (canAct()) {
                        service.tapConfigured(
                            "sort_price",
                            priceSort?.bounds?.centerX()?.toFloat()?.div(page.width) ?: 0.05f,
                            priceSort?.bounds?.centerY()?.toFloat()?.div(page.height) ?: 0.30f
                        )
                        catalogSortPriceSelected = true
                        catalogSortFrames = 0
                        actionStatus("Выбираю сортировку по цене")
                    }
                    return
                }

                if (!catalogSortDirectionSelected) {
                    val ascending = page.lines.firstOrNull {
                        val value = OcrPage.normalize(it.text)
                        value.contains("возрастан") ||
                            value.contains("от деш") ||
                            value.contains("сначала деш")
                    }
                    if (ascending != null && canAct()) {
                        tapLine(page, ascending, "Сортирую от самой дешёвой наклейки к дорогой")
                        catalogSortDirectionSelected = true
                        phase = Phase.CATALOG_SCAN
                        return
                    }
                    catalogSortFrames += 1
                    if (catalogSortFrames >= 2) {
                        // Some game builds immediately apply ascending order when the Price option is selected.
                        catalogSortDirectionSelected = true
                        phase = Phase.CATALOG_SCAN
                        actionStatus("Сортировка по цене выбрана. Продолжаю просмотр карточек.")
                    }
                    return
                }

                val candidates = page.lines
                    .filter { OcrPage.normalize(it.text).contains("sticker") }
                    .sortedWith(compareBy<OcrLine> { it.bounds.top }.thenBy { it.bounds.left })
                val nextSticker = candidates.firstOrNull {
                    OcrPage.normalize(it.text) !in visitedCatalogItems
                }

                if (nextSticker != null && canAct()) {
                    val key = OcrPage.normalize(nextSticker.text)
                    visitedCatalogItems += key
                    catalogPendingTitle = nextSticker.text.trim()
                    catalogOpenWaitFrames = 0
                    catalogBackAttempts = 0
                    val tapX = nextSticker.bounds.centerX().toFloat() / page.width
                    val tapY = (nextSticker.bounds.top - page.height * 0.10f)
                        .coerceAtLeast(page.height * 0.18f).toFloat() / page.height
                    val accepted = service.tapNormalized(tapX, tapY)
                    if (!accepted) {
                        pause("Android отклонил касание карточки наклейки. Проверь разрешение специальных возможностей и координаты.")
                    } else {
                        phase = Phase.CATALOG_OPEN_ITEM
                        actionStatus("Открываю наклейку: " + catalogPendingTitle + ". После просмотра вернусь кнопкой «Назад».")
                    }
                    return
                }

                if (nextSticker == null) {
                    if (catalogPageCount >= MAX_CATALOG_PAGES) {
                        phase = Phase.PAUSED
                        AutomationBridge.monitoringRequested = false
                        actionStatus("Сканирование остановлено по лимиту страниц. В базе просмотрено ${visitedCatalogItems.size} наклеек.")
                        return
                    }
                    if (pageSignature == catalogLastScrollSignature) {
                        catalogNoMoveCount += 1
                    } else {
                        catalogNoMoveCount = 0
                    }
                    catalogLastScrollSignature = pageSignature
                    if (catalogNoMoveCount >= 3) {
                        pause("Полоса прокрутки не сдвинула каталог после трёх попыток. Настрой точки прокрутки в разделе «Координаты».")
                    } else if (canAct()) {
                        service.swipeConfigured(
                            "catalog_scroll_bottom",
                            "catalog_scroll_top",
                            ScreenPoint(0.99f, 0.82f),
                            ScreenPoint(0.99f, 0.42f),
                            520L
                        )
                        actionStatus("Прокручиваю список по правой полосе, чтобы перейти к следующим наклейкам.")
                    }
                }
            }

            Phase.CATALOG_OPEN_ITEM -> {
                val leftGrid = reading.kind != ScreenKind.MARKET_GRID &&
                    !page.fullText.contains("Sticker", ignoreCase = true)
                if (leftGrid || reading.kind == ScreenKind.ITEM_DETAIL ||
                    reading.kind == ScreenKind.OFFERS || reading.kind == ScreenKind.UNKNOWN
                ) {
                    val returned = service.goBack()
                    phase = Phase.CATALOG_WAIT_GRID
                    catalogBackAttempts = 1
                    actionStatus(
                        if (returned) "Осмотрел «" + (catalogPendingTitle ?: "наклейку") + "». Возвращаюсь назад."
                        else "Не удалось отправить команду «Назад» при осмотре наклейки."
                    )
                } else {
                    catalogOpenWaitFrames += 1
                    if (catalogOpenWaitFrames >= 3) {
                        pause("Карточка «" + (catalogPendingTitle ?: "наклейка") + "» не открылась. Проверь координаты карточки и касания.")
                    }
                }
            }

            Phase.CATALOG_WAIT_GRID -> {
                if (reading.kind == ScreenKind.MARKET_GRID ||
                    page.fullText.contains("Sticker", ignoreCase = true)
                ) {
                    phase = Phase.CATALOG_SCAN
                    catalogPendingTitle = null
                    actionStatus("Вернулся к списку. Открываю следующую наклейку.")
                } else if (catalogBackAttempts < 2) {
                    catalogBackAttempts += 1
                    service.goBack()
                    actionStatus("Повторно возвращаюсь к списку наклеек.")
                } else {
                    pause("После просмотра наклейки не удалось вернуться к каталогу. Остановил автоматизацию.")
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
        val activity = reading.recentActivityLines.firstOrNull { line ->
            val candidate = OcrPage.normalize(line.text)
            candidate.isNotBlank() &&
                !candidate.contains("не было последних действий") &&
                candidate !in seenActivitySignatures
        } ?: return

        val signature = OcrPage.normalize(activity.text)
        if (!seenActivitySignatures.add(signature)) return
        if (seenActivitySignatures.size > MAX_SEEN_ACTIVITY_LINES) {
            val oldest = seenActivitySignatures.firstOrNull()
            if (oldest != null) seenActivitySignatures.remove(oldest)
        }

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
            val accepted = service.tapNormalized(
                buyWord.bounds.centerX().toFloat() / page.width,
                buyWord.bounds.centerY().toFloat() / page.height
            )
            if (!accepted) {
                pendingPurchase = null
                pause("Android не принял нажатие на найденную кнопку покупки. Останавливаюсь.")
                return@inspectCandidateRowsAsync
            }
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
            val accepted = service.tapConfigured(
                "purchase_confirm",
                page.normalizedCenter(buyLine).first,
                page.normalizedCenter(buyLine).second
            )
            if (!accepted) {
                pause("Android не принял нажатие подтверждения. Покупка не считается выполненной.")
                return
            }
            pendingPurchase = null
            phase = Phase.WATCH_ACTIVITY
            actionStatus("Нажатие подтверждения принято Android. Проверяю возврат к списку предложений.")
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
        private const val MAX_SEEN_ACTIVITY_LINES = 100
        private const val GRID_START_X = 0.247f
        private const val GRID_STEP_X = 0.185f
        private const val GRID_CARD_WIDTH = 0.18f
    }
}
