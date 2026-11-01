package com.selteck.so2project.automation

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs

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
                pause("Появилось подтверждение покупки. Покупка не нажимается без отдельной проверки лота.")
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
                        tapLine(page, targetLine, "Открываю " + target)
                        phase = Phase.OPEN_ITEM
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
            Phase.WATCH_ACTIVITY -> watchRecentActivity(page, reading)
            else -> Unit
        }
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

    private fun watchRecentActivity(page: OcrPage, reading: ScreenReading) {
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

        val candidates = reading.locatedPrices.filter { located ->
            located.line.bounds.centerY() in
                (page.height * 0.40f).toInt()..(page.height * 0.82f).toInt() &&
                abs(located.value - price) < 0.005
        }
        discoveredOffers += candidates.size
        actionStatus(
            "Новое действие за " + String.format("%.2f", price) + " G. " +
                "Совпадающих предложений на экране: " + candidates.size + ". Проверяю без покупки."
        )
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
    }
}
