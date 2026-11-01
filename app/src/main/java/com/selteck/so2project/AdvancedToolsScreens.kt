package com.selteck.so2project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selteck.so2project.automation.AutomationCoordinates
import com.selteck.so2project.automation.CoordinateSpec
import com.selteck.so2project.automation.StickerCatalogScanner
import com.selteck.so2project.data.So2Database
import com.selteck.so2project.data.StickerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@Composable
fun CoordinatesContent(context: Context, onBack: () -> Unit) {
    var selectedIndex by remember { mutableStateOf(0) }
    val selected = AutomationCoordinates.specs[selectedIndex]
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "КАЛИБРОВКА ЭКРАНА",
            color = Color(0xFFFFB547),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Координаты задаются в процентах от игрового кадра. Выбери одну кнопку, измени X/Y и сохрани. Редактор показывает только одну настройку за раз, чтобы не перегружать телефон.",
            color = Color.LightGray,
            fontSize = 13.sp
        )
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selectedIndex + 1} / ${AutomationCoordinates.specs.size}", color = Color(0xFFFFB547))
                Text(selected.title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(selected.description, color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = selectedIndex > 0,
                        onClick = { selectedIndex-- },
                        modifier = Modifier.weight(1f)
                    ) { Text("ПРЕДЫДУЩАЯ") }
                    OutlinedButton(
                        enabled = selectedIndex < AutomationCoordinates.specs.lastIndex,
                        onClick = { selectedIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("СЛЕДУЮЩАЯ") }
                }
            }
        }
        CoordinateEditorRow(context, selected)
        OutlinedButton(
            onClick = { AutomationCoordinates.reset(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("СБРОСИТЬ ВСЕ КООРДИНАТЫ") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("ГОТОВО") }
    }
}

@Composable
private fun CoordinateEditorRow(context: Context, spec: CoordinateSpec) {
    val current = remember(spec.key) { AutomationCoordinates.get(context, spec.key) }
    var xText by remember(spec.key) {
        mutableStateOf(String.format(Locale.US, "%.1f", current.x * 100f))
    }
    var yText by remember(spec.key) {
        mutableStateOf(String.format(Locale.US, "%.1f", current.y * 100f))
    }
    var message by remember(spec.key) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(spec.description, color = Color.LightGray, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = xText,
                    onValueChange = { xText = it },
                    label = { Text("X %") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = yText,
                    onValueChange = { yText = it },
                    label = { Text("Y %") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val x = xText.replace(',', '.').toFloatOrNull()
                    val y = yText.replace(',', '.').toFloatOrNull()
                    if (x == null || y == null || x !in 0f..100f || y !in 0f..100f) {
                        message = "Введи числа от 0 до 100."
                    } else {
                        AutomationCoordinates.save(context, spec.key, x / 100f, y / 100f)
                        message = "Сохранено: X $x%, Y $y%."
                    }
                }) { Text("СОХРАНИТЬ") }
                Text(message, color = Color(0xFFFFB547), fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun ManualStickerContent(context: Context, onBack: () -> Unit) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var variant by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Выбери файл с изображением наклейки.") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
            previewBitmap?.recycle()
            previewBitmap = decoded
            status = if (decoded != null) "Изображение загружено. Заполни название и цену." else "Не удалось прочитать изображение."
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewBitmap?.recycle() }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "РУЧНОЕ ДОБАВЛЕНИЕ НАКЛЕЙКИ",
            color = Color(0xFFFFB547),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        Text(
            "Лучше загружать крупный квадратный скриншот, где видна только сама наклейка, без цены, фона игры и соседних иконок. Так визуальное сравнение будет точнее.",
            color = Color.LightGray,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selectedUri == null) "ВЫБРАТЬ ИЗОБРАЖЕНИЕ" else "ЗАМЕНИТЬ ИЗОБРАЖЕНИЕ")
        }
        previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Предпросмотр наклейки",
                modifier = Modifier.fillMaxWidth().height(170.dp),
                contentScale = ContentScale.Fit
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название наклейки") },
            placeholder = { Text("Например, Sticker 'Zane'") },
            singleLine = true
        )
        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Цена в G") },
            placeholder = { Text("Например, 1250") },
            singleLine = true
        )
        OutlinedTextField(
            value = variant,
            onValueChange = { variant = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Вариант (необязательно; пустое поле = авто-отпечаток)") },
            singleLine = true
        )
        Text(status, color = Color.LightGray, fontSize = 12.sp)
        Button(
            onClick = {
                val bitmap = previewBitmap
                val parsedPrice = price.replace(',', '.').toDoubleOrNull()
                if (bitmap == null || name.isBlank() || parsedPrice == null || parsedPrice < 0.0) {
                    status = "Нужны изображение, название и корректная цена."
                } else {
                    saving = true
                    status = "Сохраняю изображение и отпечаток…"
                    val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (safeBitmap == null) {
                        status = "Не удалось подготовить изображение. Выбери файл меньшего размера."
                        return@Button
                    }
                    val safeName = name.trim()
                    val safeVariant = variant.trim()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val scanner = StickerCatalogScanner(context)
                                val fingerprint = scanner.calculateFingerprint(safeBitmap)
                                    ?: error("Изображение слишком пустое или однотонное. Загрузи более крупный кадр наклейки.")
                                val directory = File(context.filesDir, "sticker_catalog/manual")
                                if (!directory.exists() && !directory.mkdirs()) {
                                    error("Не удалось создать папку базы изображений.")
                                }
                                val imageFile = File(directory, "manual_${System.currentTimeMillis()}.png")
                                FileOutputStream(imageFile).use { stream ->
                                    if (!safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                        error("Не удалось записать PNG.")
                                    }
                                    stream.flush()
                                }
                                val record = StickerEntity(
                                    name = safeName,
                                    marketPrice = parsedPrice,
                                    visualVariant = safeVariant.ifBlank { fingerprint.take(20) },
                                    imagePath = imageFile.absolutePath,
                                    visualHash = fingerprint
                                )
                                So2Database.get(context).stickerDao().upsert(record)
                                imageFile.name
                            }
                        }
                        safeBitmap.recycle()
                        saving = false
                        status = result.fold(
                            onSuccess = { "Добавлено в базу: ${it}" },
                            onFailure = { "Не удалось сохранить: ${it.message ?: "ошибка"}" }
                        )
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "СОХРАНЯЮ…" else "СОХРАНИТЬ В БАЗУ") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("НАЗАД") }
    }
}

@Composable
fun GuideContent(onBack: () -> Unit) {
    val steps = listOf(
        "1. Запусти SO2 Project и включи службу специальных возможностей Android. Без неё игра не разрешит читать экран и выполнять касания.",
        "2. Открой Standoff 2. Дождись главного меню. Мини-панель появится поверх игры: в ней есть Пауза, Стоп, Скрин и Просмотр.",
        "3. В игре открой рынок кнопкой тележки слева. Затем нажми «Фильтр». Если игра открылась в другом разделе, останови сценарий и откалибруй координаты в настройках.",
        "4. В окне фильтра нажми «Сбросить». В типе предмета выбери «Наклейки». Не выбирай оружие или контейнеры для заполнения каталога наклеек.",
        "5. На рынке выбери сортировку по цене и направление «По возрастанию», чтобы сначала показывались самые дешёвые наклейки. Затем нажми «Применить».",
        "6. Сборщик читает карточки на странице, сохраняет изображение, название, цену и визуальный отпечаток. После этого он открывает карточку наклейки и возвращается кнопкой Android «Назад».",
        "7. Когда все карточки на странице обработаны, сборщик прокручивает список по правой полосе и повторяет проверку. Если полоса не движется, откалибруй точки прокрутки.",
        "8. Для ручного добавления открой «База» → «Добавить наклейку». Загрузи крупный обрезанный скриншот одной наклейки, введи точное название и её цену в G, затем нажми «Сохранить в базу».",
        "9. На экране предложения лота тумблер «С наклейками» должен быть включён. Если галочка уже стоит, помощник не должен выключать её. В списке лотов могут быть 1–4 наклейки: позиции фиксированы, пустые ячейки слева пропускаются.",
        "10. Значок каждой наклейки в строке лота можно открыть касанием. Просмотр закрывается кнопкой Android «Назад», после чего помощник возвращается к той же строке. Покупка рассматривается только после проверки названия, цены и прибыли.",
        "11. В окне покупки сравнивается изображение и название скина, а также точная цена. Крестик закрывает окно. Кнопка Купить подтверждает покупку. Если написано «Недостаточно средств», покупку не подтверждать.",
        "12. Фото10 показывает отдельный экран осмотра оружия; фото11 — сообщение об ошибке/лоте, который уже недоступен; фото12 — историю продаж. Эти экраны не являются карточками рынка и не должны запускать повторное нажатие Купить."
    )
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ПОШАГОВАЯ ИНСТРУКЦИЯ", color = Color(0xFFFFB547), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        steps.forEach { step ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028))
            ) {
                Text(step, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(13.dp))
            }
        }
        Text(
            "Фотоориентиры: фото1 — главное меню; фото2 — инвентарь; фото3 — рынок и сортировка; фото4 — фильтр; фото5 — сетка результатов; фото6 — выключенный тумблер и окно лотов; фото7 — включённый тумблер и полоса прокрутки; фото8 — подтверждение покупки; фото9 — экран пополнения голды; фото10 — осмотр оружия; фото11 — ошибка/лот недоступен; фото12 — история продаж.",
            color = Color.LightGray,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("НАЗАД") }
    }
}
