package com.selteck.so2project.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single-threaded Tesseract instance. Returns word boxes as well as text,
 * allowing the automation layer to tap actual detected UI labels.
 */
class ScreenTextAnalyzer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val tess = TessBaseAPI()
    private val initFuture = CompletableFuture<Unit>()

    init {
        worker.execute {
            try {
                val dataRoot = File(appContext.filesDir, "tesseract")
                val tessData = File(dataRoot, "tessdata")
                if (!tessData.exists() && !tessData.mkdirs()) {
                    throw IOException("Не удалось создать папку OCR")
                }
                ensureLanguageModel(
                    File(tessData, "rus.traineddata"),
                    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
                )
                ensureLanguageModel(
                    File(tessData, "eng.traineddata"),
                    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
                )
                if (!tess.init(dataRoot.absolutePath, "rus+eng")) {
                    throw IOException("Tesseract не смог загрузить модели rus+eng")
                }
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                initFuture.complete(Unit)
            } catch (error: Throwable) {
                runCatching { tess.recycle() }
                initFuture.completeExceptionally(error)
            }
        }
    }

    fun initialize(): CompletableFuture<Unit> = initFuture

    fun recognize(bitmap: Bitmap): CompletableFuture<OcrPage> {
        return initFuture.thenApplyAsync({
            tess.setImage(bitmap)
            try {
                // Force recognition before requesting the reading-order iterator.
                tess.getUTF8Text()
                val words = mutableListOf<OcrWord>()
                val iterator = tess.resultIterator
                if (iterator != null) {
                    try {
                        iterator.begin()
                        var lineIndex = 0
                        var first = true
                        do {
                            if (!first && iterator.isAtBeginningOf(PageIteratorLevel.RIL_TEXTLINE)) {
                                lineIndex += 1
                            }
                            first = false

                            val text = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD)
                                ?.replace('\n', ' ')
                                ?.trim()
                                .orEmpty()
                            val rect = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                            val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)

                            if (text.isNotBlank() && rect != null &&
                                rect.width() > 0 && rect.height() > 0
                            ) {
                                words += OcrWord(
                                    text = text,
                                    bounds = Rect(rect),
                                    confidence = confidence,
                                    lineIndex = lineIndex
                                )
                            }
                        } while (iterator.next(PageIteratorLevel.RIL_WORD))
                    } finally {
                        iterator.delete()
                    }
                }

                val lineGroups = words.groupBy { it.lineIndex }.toSortedMap().mapNotNull { (index, group) ->
                    val valid = group.filter { it.text.isNotBlank() }
                    if (valid.isEmpty()) return@mapNotNull null
                    val bounds = Rect(valid.first().bounds)
                    valid.drop(1).forEach { bounds.union(it.bounds) }
                    OcrLine(
                        text = valid.joinToString(" ") { it.text },
                        bounds = bounds,
                        confidence = valid.map { it.confidence }.average().toFloat(),
                        lineIndex = index
                    )
                }

                OcrPage(
                    width = bitmap.width,
                    height = bitmap.height,
                    words = words,
                    lines = lineGroups
                )
            } finally {
                tess.clear()
            }
        }, worker)
    }

    override fun close() {
        runCatching {
            worker.execute { runCatching { tess.recycle() } }
            worker.shutdown()
        }
    }

    private fun ensureLanguageModel(destination: File, sourceUrl: String) {
        if (destination.exists() && destination.length() > MIN_MODEL_BYTES) return
        val temporary = File(destination.parentFile, destination.name + ".download")
        if (temporary.exists()) temporary.delete()

        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SO2Project-Android/0.1")
        }
        try {
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK) {
                throw IOException("Загрузка OCR модели завершилась с HTTP $response")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (temporary.length() <= MIN_MODEL_BYTES) {
                throw IOException("Файл OCR модели слишком маленький: " + destination.name)
            }
            if (!temporary.renameTo(destination)) {
                destination.outputStream().use { output ->
                    temporary.inputStream().use { it.copyTo(output) }
                }
                temporary.delete()
            }
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        private const val MIN_MODEL_BYTES = 1_000_000L
    }
}
