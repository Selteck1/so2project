package com.selteck.so2project.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.selteck.so2project.data.So2Database
import com.selteck.so2project.data.StickerEntity
import com.selteck.so2project.logic.StickerProfitCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class StickerIconMatch(
    val stickerName: String,
    val marketPrice: Double,
    val confidence: Double
)

data class OfferRowInspection(
    val price: Double,
    val centerY: Int,
    val matches: List<StickerIconMatch>,
    val unknownIconCount: Int,
    val allIconsRecognized: Boolean,
    val estimatedProfit: Double,
    val shouldBuy: Boolean
)

class StickerCatalogScanner(context: Context) {
    private val appContext = context.applicationContext
    private val database = So2Database.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun scanVisibleCards(page: OcrPage, screen: Bitmap): Int {
        if (page.width <= 0 || page.height <= 0) return 0
        val titles = page.lines.filter { Regex("""\bsticker\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
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
            val cardLeft = (page.width * (GRID_START_X + column * GRID_STEP_X)).toInt().coerceIn(0, page.width - 2)
            val cardRight = (cardLeft + page.width * GRID_CARD_WIDTH).toInt().coerceIn(cardLeft + 1, page.width)

            val pricePair = page.lines
                .filter {
                    it.bounds.top >= title.bounds.bottom &&
                        it.bounds.top - title.bounds.bottom < page.height * 0.12f &&
                        it.bounds.centerX() in cardLeft..cardRight
                }
                .mapNotNull { line ->
                    ScreenClassifier.extractPrices(line.text).lastOrNull()?.let { it to line }
                }
                .minByOrNull { it.second.bounds.top - title.bounds.bottom } ?: continue

            val cropTop = (page.height * GRID_IMAGE_TOP).toInt().coerceIn(0, page.height - 2)
            val cropBottom = (title.bounds.top - page.height * 0.008f).toInt().coerceIn(cropTop + 1, page.height)
            val crop = runCatching {
                Bitmap.createBitmap(screen, cardLeft, cropTop, cardRight - cardLeft, cropBottom - cropTop)
            }.getOrNull() ?: continue

            try {
                val fingerprint = calculateFingerprint(crop) ?: continue
                if (!savedKeys.add(itemName.lowercase() + "|" + fingerprint)) continue
                val safeName = itemName.lowercase()
                    .replace(Regex("""[^a-z0-9а-я]+"""), "_").trim('_').take(48)
                val imageFile = File(root, safeName + "_" + fingerprint.take(12) + ".png")
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
                    visualVariant = fingerprint,
                    imagePath = imageFile.absolutePath,
                    visualHash = fingerprint,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                scope.launch { runCatching { database.stickerDao().upsert(record) } }
                scanned += 1
            } finally {
                crop.recycle()
            }
        }
        return scanned
    }

    fun inspectCandidateRowsAsync(
        screen: Bitmap,
        page: OcrPage,
        candidatePrice: Double,
        configuredMinStickerPrice: Double,
        minimumProfit: Double,
        callback: (List<OfferRowInspection>) -> Unit
    ) {
        val snapshot = runCatching { screen.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (snapshot == null) {
            callback(emptyList())
            return
        }

        scope.launch {
            val results = try {
                val records = runCatching { database.stickerDao().getAll() }.getOrDefault(emptyList())
                inspectCandidateRows(snapshot, page, candidatePrice, configuredMinStickerPrice, minimumProfit, records)
            } catch (_: Throwable) {
                emptyList()
            } finally {
                snapshot.recycle()
            }
            mainHandler.post { callback(results) }
        }
    }

    private fun inspectCandidateRows(
        screen: Bitmap,
        page: OcrPage,
        candidatePrice: Double,
        configuredMinStickerPrice: Double,
        minimumProfit: Double,
        records: List<StickerEntity>
    ): List<OfferRowInspection> {
        if (records.isEmpty()) return emptyList()

        val rows = page.locatedPrices
            .filter {
                abs(it.value - candidatePrice) < 0.005 &&
                    it.bounds.centerY() in
                    (page.height * OFFER_TOP).toInt()..(page.height * OFFER_BOTTOM).toInt()
            }
            .distinctBy { it.line.lineIndex }
            .sortedByDescending { it.bounds.centerY() }
            .take(3)
        if (rows.isEmpty()) return emptyList()

        val visiblePrices = page.locatedPrices
            .filter {
                it.bounds.centerY() in
                    (page.height * OFFER_TOP).toInt()..(page.height * OFFER_BOTTOM).toInt()
            }
            .map { it.value }
        val cheapestBasePrice = visiblePrices.minOrNull() ?: candidatePrice

        return rows.map { located ->
            val matches = mutableListOf<StickerIconMatch>()
            var unknownCount = 0
            var visibleIconCount = 0

            for (cell in iconCells(screen, located.bounds)) {
                val crop = runCatching {
                    Bitmap.createBitmap(screen, cell.left, cell.top, cell.width(), cell.height())
                }.getOrNull() ?: continue
                try {
                    val fingerprint = calculateFingerprint(crop) ?: continue
                    visibleIconCount += 1
                    val best = records.mapNotNull { record ->
                        val saved = record.visualHash ?: return@mapNotNull null
                        compareFingerprints(fingerprint, saved)?.let {
                            StickerIconMatch(record.name, record.marketPrice, it)
                        }
                    }.maxByOrNull { it.confidence }

                    if (best != null && best.confidence >= MATCH_THRESHOLD) {
                        matches += best
                    } else {
                        unknownCount += 1
                    }
                } finally {
                    crop.recycle()
                }
            }

            val recognized = visibleIconCount > 0 && unknownCount == 0 && matches.size == visibleIconCount
            val hasConfiguredSticker = matches.any { it.marketPrice >= configuredMinStickerPrice }
            val profit = StickerProfitCalculator.evaluate(
                cheapestBaseSkinPrice = cheapestBasePrice,
                purchasePrice = candidatePrice,
                stickerMarketPrices = matches.map { it.marketPrice },
                minimumStickerPrice = PROFIT_STICKER_THRESHOLD,
                minimumProfit = minimumProfit
            )

            OfferRowInspection(
                price = candidatePrice,
                centerY = located.bounds.centerY(),
                matches = matches,
                unknownIconCount = unknownCount,
                allIconsRecognized = recognized,
                estimatedProfit = profit.estimatedProfit,
                shouldBuy = recognized && hasConfiguredSticker && profit.shouldBuy
            )
        }
    }

    private fun iconCells(screen: Bitmap, price: android.graphics.Rect): List<android.graphics.Rect> {
        val regionRight = (price.left - screen.width * 0.008f).toInt()
        val regionLeft = (regionRight - screen.width * ICON_REGION_WIDTH).toInt()
        val top = (price.centerY() - screen.height * ICON_REGION_HEIGHT / 2f).toInt()
        val bottom = (price.centerY() + screen.height * ICON_REGION_HEIGHT / 2f).toInt()
        if (regionLeft < 0 || regionRight > screen.width || top < 0 || bottom > screen.height) return emptyList()

        val cellWidth = (regionRight - regionLeft) / 4
        if (cellWidth < 12 || bottom - top < 12) return emptyList()
        return (0 until 4).map { index ->
            val insetX = (cellWidth * 0.10f).toInt()
            val insetY = ((bottom - top) * 0.08f).toInt()
            android.graphics.Rect(
                regionLeft + index * cellWidth + insetX,
                top + insetY,
                regionLeft + (index + 1) * cellWidth - insetX,
                bottom - insetY
            )
        }
    }

    private fun calculateFingerprint(bitmap: Bitmap): String? {
        if (bitmap.width < 5 || bitmap.height < 5) return null
        var sum = 0.0
        var sumSquare = 0.0
        var sampleCount = 0
        var colorful = 0
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 10).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val gray = (red * 299 + green * 587 + blue * 114) / 1000.0
                sum += gray
                sumSquare += gray * gray
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 24) colorful += 1
                sampleCount += 1
            }
        }
        if (sampleCount == 0) return null
        val mean = sum / sampleCount
        val variance = (sumSquare / sampleCount) - mean * mean
        if (sqrt(variance.coerceAtLeast(0.0)) < 9.0 && colorful < 4) return null

        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = scaled.getPixel(x, y)
                    val right = scaled.getPixel(x + 1, y)
                    val grayLeft = (Color.red(left) * 299 + Color.green(left) * 587 + Color.blue(left) * 114) / 1000
                    val grayRight = (Color.red(right) * 299 + Color.green(right) * 587 + Color.blue(right) * 114) / 1000
                    if (grayLeft > grayRight) hash = hash or (1L shl bit)
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
            return "%016x-%02x%02x%02x".format(hash, red / count, green / count, blue / count)
        } finally {
            scaled.recycle()
        }
    }

    private fun compareFingerprints(first: String, second: String): Double? {
        val a = first.split('-')
        val b = second.split('-')
        if (a.size != 2 || b.size != 2 || a[1].length != 6 || b[1].length != 6) return null
        val hashA = runCatching { a[0].toULong(16).toLong() }.getOrNull() ?: return null
        val hashB = runCatching { b[0].toULong(16).toLong() }.getOrNull() ?: return null
        val hamming = java.lang.Long.bitCount(hashA xor hashB)
        val colorA = parseRgb(a[1]) ?: return null
        val colorB = parseRgb(b[1]) ?: return null
        val distance = sqrt(
            (colorA[0] - colorB[0]) * (colorA[0] - colorB[0]) +
                (colorA[1] - colorB[1]) * (colorA[1] - colorB[1]) +
                (colorA[2] - colorB[2]) * (colorA[2] - colorB[2])
        )
        val shapeScore = 1.0 - hamming / 64.0
        val colorScore = 1.0 - (distance / 441.673).coerceIn(0.0, 1.0)
        return shapeScore * 0.72 + colorScore * 0.28
    }

    private fun parseRgb(value: String): DoubleArray? {
        if (value.length != 6) return null
        return try {
            doubleArrayOf(
                value.substring(0, 2).toInt(16).toDouble(),
                value.substring(2, 4).toInt(16).toDouble(),
                value.substring(4, 6).toInt(16).toDouble()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    companion object {
        private const val GRID_START_X = 0.247f
        private const val GRID_STEP_X = 0.185f
        private const val GRID_CARD_WIDTH = 0.18f
        private const val GRID_IMAGE_TOP = 0.135f
        private const val OFFER_TOP = 0.40f
        private const val OFFER_BOTTOM = 0.82f
        private const val ICON_REGION_WIDTH = 0.155f
        private const val ICON_REGION_HEIGHT = 0.075f
        private const val MATCH_THRESHOLD = 0.86
        private const val PROFIT_STICKER_THRESHOLD = 500.0
    }
}
