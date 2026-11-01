package com.selteck.so2project.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunMode { MONITOR, CATALOG }

data class MonitorSnapshot(
    val connected: Boolean = false,
    val foregroundPackage: String = "",
    val lastScreen: String = "",
    val lastText: String = "",
    val status: String = "Служба не подключена",
    val mode: RunMode? = null
)

object AutomationBridge {
    private val _state = MutableStateFlow(MonitorSnapshot())
    val state: StateFlow<MonitorSnapshot> = _state.asStateFlow()
    @Volatile var service: So2AccessibilityService? = null
    @Volatile var monitoringRequested = false
    @Volatile var paused = false
    @Volatile var targetPackage: String? = null
    @Volatile var mode: RunMode? = null
    @Volatile var selectedWeapon = ""
    @Volatile var selectedSkin = ""
    @Volatile var selectedCategory = "Regular"
    @Volatile var minStickerPrice = 0.0
    @Volatile var minProfit = 10.0

    fun prepare(packageName: String, mode: RunMode, weapon: String, skin: String,
        category: String, minStickerPrice: Double, minProfit: Double) {
        targetPackage = packageName
        this.mode = mode
        selectedWeapon = weapon
        selectedSkin = skin
        selectedCategory = category
        this.minStickerPrice = minStickerPrice
        this.minProfit = minProfit
        paused = false
        monitoringRequested = true
        update(status = "Ожидание запуска Standoff 2", mode = mode)
    }

    fun attach(service: So2AccessibilityService) {
        this.service = service
        update(connected = true, status = "Служба Android подключена")
    }

    fun detach(service: So2AccessibilityService) {
        if (this.service === service) this.service = null
        monitoringRequested = false
        paused = false
        update(connected = false, status = "Служба отключена")
    }

    fun update(connected: Boolean? = null, foregroundPackage: String? = null,
        lastScreen: String? = null, lastText: String? = null, status: String? = null,
        mode: RunMode? = _state.value.mode) {
        val old = _state.value
        _state.value = old.copy(
            connected = connected ?: old.connected,
            foregroundPackage = foregroundPackage ?: old.foregroundPackage,
            lastScreen = lastScreen ?: old.lastScreen,
            lastText = lastText ?: old.lastText,
            status = status ?: old.status,
            mode = mode
        )
    }
}
