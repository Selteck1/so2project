package com.selteck.so2project.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.selteck.so2project.data.So2Database
import com.selteck.so2project.data.StickerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Reads visible sticker cards from the filtered market grid.
 * Saves each card image locally and stores name, observed price and visual hash in Room.
 */
class StickerCatalogScanner(context: Context) {
    private val appContext = context.applicationContext
    private val database = So2Database.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stickerNamePattern = Regex("""\bsticker\b""", RegexOption.IGNORE_CASE)

    fun scanVisibleCards(page: OcrPage, screen: Bitmap): Int {
        if (page.width <= 0 || page.height <= 0) return 0
        val titles = page.lines.filter { stickerNamePattern.containsMatchIn(it.text) }
        if (titles.isEmpty()) return 0

        val savedKeys = mutableSetOf<String>()
        var scanned = 0
        val root = File(appContext.filesDir, "sticker_catalog")
        if (!root.exists()) root.mkdirs()

        for (title in titles) {
            val itemName = title.text.trim().replace(Regex("""\s+"""), " ")
            if (itemName.length < 5) continue

            val xNorm = title.bounds.centerX().toFloat() / page.width
            val column = (((xNorm - GRID_START_X) / GRID_STEP_X).roundToInt()).coerceIn(0, 3)
            val cardLeft = (page.width * (GRID_START_X + column * GRID_STEP_X)).toInt()
                .coerceIn(0, page.width - 2)
            val cardRight = (cardLeft + page.width * GRID_CARD_WIDTH).toInt()
                .coerceIn(cardLeft + 1, page.width)

            val possiblePrices = page.lines
                .filter { line ->
                    line.bounds.top >= title.bounds.bottom &&
                        line.bounds.top - title.bounds.bottom < page.height * 0.12f &&
                        line.bounds.centerX() in cardLeft..cardRight
                }
                .mapNotNull { line ->
                    val price = ScreenClassifier.extractPrices(line.text).lastOrNull()
                    if (price != null) price to line else null
                }
            val pricePair = possiblePrices.minByOrNull {
                it.second.bounds.top - title.bounds.bottom
            } ?: continue

            val cropTop = (page.height * GRID_IMAGE_TOP).toInt().coerceIn(0, page.height - 2)
            val cropBottom = (title.bounds.top - page.height * 0.008f).toInt()
                .coerceIn(cropTop + 1, page.height)
            val cropHeight = cropBottom - cropTop
            val cropWidth = cardRight - cardLeft
            if (cropWidth < 30 || cropHeight < 30) continue

            val crop = runCatching {
                Bitmap.createBitmap(screen, cardLeft, cropTop, cropWidth, cropHeight)
            }.getOrNull() ?: continue

            try {
                val visualHash = calculateVisualHash(crop)
                val identity = itemName.lowercase() + "|" + visualHash
                if (!savedKeys.add(identity)) continue

                val safeName = itemName.lowercase()
                    .replace(Regex("""[^a-z0-9а-я]+"""), "_")
                    .trim('_')
                    .take(48)
                val imageFile = File(root, safeName + "_" + visualHash.take(12) + ".png")
                if (!imageFile.exists()) {
                    FileOutputStream(imageFile).use { output ->
                        crop.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.flush()
                        output.fd.sync()
                    }
                }

                val record = StickerEntity(
                    name = itemName,
                    marketPrice = pricePair.first,
                    visualVariant = visualHash,
                    imagePath = imageFile.absolutePath,
                    visualHash = visualHash,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                scope.launch {
                    runCatching { database.stickerDao().upsert(record) }
                }
                scanned += 1
            } finally {
                crop.recycle()
            }
        }
        return scanned
    }

    private fun calculateVisualHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var differenceHash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = scaled.getPixel(x, y)
                    val right = scaled.getPixel(x + 1, y)
                    val leftGray = (
                        Color.red(left) * 299 + Color.green(left) * 587 + Color.blue(left) * 114
                    ) / 1000
                    val rightGray = (
                        Color.red(right) * 299 + Color.green(right) * 587 + Color.blue(right) * 114
                    ) / 1000
                    if (leftGray > rightGray) differenceHash = differenceHash or (1L shl bit)
                    bit += 1
                }
            }

            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            for (y in 0 until scaled.height) {
                for (x in 0 until scaled.width) {
                    val pixel = scaled.getPixel(x, y)
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    count += 1
                }
            }
            return "%016x-%02x%02x%02x".format(
                differenceHash,
                red / count,
                green / count,
                blue / count
            )
        } finally {
            scaled.recycle()
        }
    }

    companion object {
        private const val GRID_START_X = 0.247f
        private const val GRID_STEP_X = 0.185f
        private const val GRID_CARD_WIDTH = 0.18f
        private const val GRID_IMAGE_TOP = 0.135f
    }
}
