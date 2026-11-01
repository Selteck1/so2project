package com.selteck.so2project

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selteck.so2project.automation.AutomationBridge
import com.selteck.so2project.automation.RunMode
import com.selteck.so2project.data.So2Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val So2Gold = Color(0xFFFFB547)
private val So2Background = Color(0xFF101216)
private val So2Surface = Color(0xFF1B2028)
private val So2Muted = Color(0xFF9DA7B5)

private val weapons = listOf(
    "USP", "G22", "P350", "Berettas", "TEC-9", "F/S", "Desert Eagle",
    "MAC10", "UMP45", "MP5", "MP7", "Akimbo UZI", "P90", "M4", "M16",
    "AKR", "AKR12", "FAMAS", "FAL", "M4A1", "VAL", "SM1014", "FaDM",
    "SPAS-12", "M60", "AWM", "M40", "M110"
)

private enum class Page { HOME, CONFIG, DATABASE, SETTINGS }

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessibilityEnabled = AccessibilityPermission.isEnabled(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = So2Gold,
                secondary = Color(0xFF83A9E8),
                background = So2Background,
                surface = So2Surface,
                onPrimary = Color(0xFF171717),
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                So2App(
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = AccessibilityPermission.isEnabled(this)
    }
}

@Composable
private fun So2App(
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val monitorState by AutomationBridge.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var selectedWeapon by rememberSaveable { mutableStateOf("AKR") }
    var skinName by rememberSaveable { mutableStateOf("Carbon") }
    var category by rememberSaveable { mutableStateOf("Regular") }
    var minStickerPrice by rememberSaveable { mutableStateOf(30f) }
    var minProfit by rememberSaveable { mutableStateOf(10f) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var statusMessage by rememberSaveable { mutableStateOf("Готово к настройке") }
    var targetPackage by rememberSaveable { mutableStateOf<String?>(null) }

    fun findGame(): String? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .firstOrNull { info ->
                val label = info.loadLabel(pm).toString()
                label.contains("Standoff 2", ignoreCase = true) ||
                    info.activityInfo.packageName.contains("standoff", ignoreCase = true)
            }?.activityInfo?.packageName
    }

    fun launchSelectedGame(mode: RunMode) {
        if (!accessibilityEnabled) {
            showPermissionDialog = true
            return
        }
        val packageName = targetPackage ?: findGame()
        if (packageName == null) {
            statusMessage = "Standoff 2 не найдена. Установи игру и повтори поиск."
            Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            statusMessage = "Не удалось открыть Standoff 2."
            return
        }
        targetPackage = packageName
        AutomationBridge.prepare(
            packageName = packageName,
            mode = mode,
            weapon = selectedWeapon,
            skin = skinName.trim(),
            category = category,
            minStickerPrice = minStickerPrice.toDouble(),
            minProfit = minProfit.toDouble()
        )
        context.startActivity(launchIntent)
        statusMessage = if (mode == RunMode.CATALOG) {
            "Режим базы данных подготовлен. Открыта игра."
        } else {
            "Мониторинг подготовлен. Открыта игра."
        }
        page = Page.HOME
    }

    Surface(modifier = Modifier.fillMaxSize(), color = So2Background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text("SO2 PROJECT", color = So2Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (page) {
                        Page.HOME -> "Автоматизация рынка"
                        Page.CONFIG -> "Настройка поиска"
                        Page.DATABASE -> "База наклеек"
                        Page.SETTINGS -> "Настройки"
                    },
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                when (page) {
                    Page.HOME -> {
                        StatusCard(accessibilityEnabled, statusMessage, monitorState.lastScreen)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val found = findGame()
                                if (found == null) {
                                    statusMessage = "Standoff 2 не найдена на устройстве."
                                    Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                } else {
                                    targetPackage = found
                                    page = Page.CONFIG
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = So2Gold)
                        ) {
                            Text("ЗАПУСТИТЬ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { page = Page.DATABASE },
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) {
                            Text("ЗАПОЛНИТЬ БАЗУ ДАННЫХ")
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Для чтения экрана и касаний Android попросит отдельно включить службу специальных возможностей.",
                            color = So2Muted,
                            fontSize = 13.sp
                        )
                    }

                    Page.CONFIG -> {
                        Text("Выбери оружие", color = So2Muted, fontSize = 13.sp)
                        WeaponSelector(selectedWeapon, weapons) { selectedWeapon = it }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = skinName,
                            onValueChange = { skinName = it },
                            label = { Text("Оригинальное название скина") },
                            placeholder = { Text("Например, Carbon") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Категория", color = So2Muted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("Regular", "StatTrack").forEach { item ->
                                if (category == item) {
                                    Button(onClick = { category = item }) { Text(item) }
                                } else {
                                    OutlinedButton(onClick = { category = item }) { Text(item) }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Минимальная цена наклейки: " + minStickerPrice.toInt() + " G")
                        Slider(
                            value = minStickerPrice,
                            onValueChange = { minStickerPrice = it },
                            valueRange = 0f..5000f
                        )
                        Text("Минимальная прибыль: " + minProfit.toInt() + " G")
                        Slider(value = minProfit, onValueChange = { minProfit = it }, valueRange = 0f..500f)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (skinName.isBlank()) {
                                    statusMessage = "Введи название скина."
                                } else {
                                    launchSelectedGame(RunMode.MONITOR)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = So2Gold)
                        ) {
                            Text("ЗАПУСТИТЬ МОНИТОРИНГ", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { page = Page.HOME }, modifier = Modifier.fillMaxWidth()) {
                            Text("Назад")
                        }
                    }

                    Page.DATABASE -> {
                        val count = produceState(initialValue = 0) {
                            value = withContext(Dispatchers.IO) {
                                So2Database.get(context).stickerDao().count()
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = So2Surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Записей в базе", color = So2Muted)
                                Text(count.value.toString(), color = So2Gold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Для каждой наклейки будут храниться название, цена, вариант перелива и визуальный отпечаток.",
                                    color = So2Muted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { launchSelectedGame(RunMode.CATALOG) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = So2Gold)
                        ) {
                            Text("ОТКРЫТЬ ИГРУ ДЛЯ СБОРА", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Полный автоматический обход каталога пока не включён. Сначала проверим распознавание карточек и различение похожих визуальных вариантов, чтобы в базу не попадали перепутанные наклейки.",
                            color = So2Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    Page.SETTINGS -> {
                        Text("Минимальная цена наклейки: " + minStickerPrice.toInt() + " G")
                        Slider(value = minStickerPrice, onValueChange = { minStickerPrice = it }, valueRange = 0f..5000f)
                        Text("Минимальная прибыль: " + minProfit.toInt() + " G")
                        Slider(value = minProfit, onValueChange = { minProfit = it }, valueRange = 0f..500f)
                        Text(
                            "Параметры применяются локально при следующем запуске мониторинга.",
                            color = So2Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }

                if (page == Page.HOME && monitorState.lastText.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = So2Surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Последний распознанный экран", color = So2Gold, fontWeight = FontWeight.Bold)
                            Text(monitorState.lastText.take(500), color = So2Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            NavigationBar(containerColor = So2Surface) {
                NavigationBarItem(
                    selected = page == Page.HOME || page == Page.CONFIG,
                    onClick = { page = Page.HOME },
                    icon = {},
                    label = { Text("Главная") }
                )
                NavigationBarItem(
                    selected = page == Page.DATABASE,
                    onClick = { page = Page.DATABASE },
                    icon = {},
                    label = { Text("База") }
                )
                NavigationBarItem(
                    selected = page == Page.SETTINGS,
                    onClick = { page = Page.SETTINGS },
                    icon = {},
                    label = { Text("Настройки") }
                )
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Нужно разрешение Android") },
            text = {
                Text("Включи «SO2 Project — распознавание и управление» в настройках специальных возможностей. Без этого Android не разрешит читать экран игры и выполнять касания.")
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    onOpenAccessibilitySettings()
                }) { Text("Открыть настройки") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPermissionDialog = false }) { Text("Позже") }
            }
        )
    }
}

@Composable
private fun StatusCard(enabled: Boolean, message: String, screen: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = So2Surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Состояние службы", color = So2Muted, fontSize = 12.sp)
            Text(
                if (enabled) "Разрешение включено" else "Требуется разрешение",
                color = if (enabled) Color(0xFF72D69B) else So2Gold,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.White, fontSize = 13.sp)
            if (screen.isNotBlank()) {
                Text("Экран: " + screen, color = So2Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun WeaponSelector(selected: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected, modifier = Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { weapon ->
                DropdownMenuItem(
                    text = { Text(weapon) },
                    onClick = {
                        onSelected(weapon)
                        expanded = false
                    }
                )
            }
        }
    }
}
