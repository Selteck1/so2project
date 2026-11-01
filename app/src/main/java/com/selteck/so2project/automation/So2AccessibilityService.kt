package com.selteck.so2project.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class So2AccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var textAnalyzer: ScreenTextAnalyzer
    private lateinit var automationEngine: ScreenAutomationEngine
    val catalogScanner: StickerCatalogScanner by lazy { StickerCatalogScanner(this) }

    private val captureInProgress = AtomicBoolean(false)
    private var loopStarted = false
    private var floatingPanel: FloatingControlPanel? = null
    private var lastScreenBitmap: Bitmap? = null
    @Volatile private var lastScreenshotWidth = 0
    @Volatile private var lastScreenshotHeight = 0

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!AutomationBridge.monitoringRequested) {
                loopStarted = false
                return
            }
            if (AutomationBridge.state.value.foregroundPackage == AutomationBridge.targetPackage) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) captureScreen()
            } else {
                AutomationBridge.update(status = "Ожидание Standoff 2 на переднем плане")
            }
            handler.postDelayed(this, 1800L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        textAnalyzer = ScreenTextAnalyzer(this)
        automationEngine = ScreenAutomationEngine(this)
        AutomationBridge.attach(this)
        AutomationBridge.update(status = "Загрузка русской и английской OCR-моделей…")
        textAnalyzer.initialize().whenComplete { _, error ->
            handler.post {
                if (error == null) {
                    AutomationBridge.update(status = "OCR ru+en готова. Ожидаю Standoff 2.")
                } else {
                    AutomationBridge.update(
                        status = "Не удалось подготовить OCR: " +
                            (error.cause?.message ?: error.message)
                    )
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return
        AutomationBridge.update(foregroundPackage = packageName)
        if (packageName == AutomationBridge.targetPackage && AutomationBridge.monitoringRequested) {
            if (floatingPanel == null) {
                floatingPanel = FloatingControlPanel(this).also { it.show() }
            }
        }
        if (packageName == AutomationBridge.targetPackage &&
            AutomationBridge.monitoringRequested && !loopStarted
        ) {
            loopStarted = true
            handler.post(captureLoop)
            AutomationBridge.update(status = "Standoff 2 обнаружена; включено распознавание экрана")
        }
    }

    override fun onInterrupt() {
        AutomationBridge.update(status = "Android прервал службу специальных возможностей")
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureLoop)
        if (::textAnalyzer.isInitialized) textAnalyzer.close()
        floatingPanel?.remove()
        floatingPanel = null
        lastScreenBitmap?.recycle()
        lastScreenBitmap = null
        AutomationBridge.detach(this)
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreen() {
        if (!captureInProgress.compareAndSet(false, true)) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    buffer.close()
                    if (bitmap == null) {
                        AutomationBridge.update(status = "Android не вернул изображение экрана")
                        captureInProgress.set(false)
                        return
                    }

                    val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
                    lastScreenshotWidth = safeBitmap.width
                    lastScreenshotHeight = safeBitmap.height
                    synchronized(this@So2AccessibilityService) {
                        lastScreenBitmap?.recycle()
                        lastScreenBitmap = runCatching {
                            safeBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                    }
                    textAnalyzer.recognize(safeBitmap).whenComplete { page, error ->
                        handler.post {
                            try {
                                if (error == null && page != null) {
                                    val reading = ScreenClassifier.classify(page)
                                    AutomationBridge.update(
                                        lastText = page.fullText,
                                        lastScreen = reading.kind.name,
                                        status = "Распознан экран: " + reading.kind
                                    )
                                    automationEngine.onFrame(page, safeBitmap)
                                    floatingPanel?.update(
                                        status = AutomationBridge.state.value.status,
                                        bitmap = safeBitmap,
                                        ocrText = page.fullText
                                    )
                                } else {
                                    AutomationBridge.update(
                                        status = "Ошибка OCR: " + (error?.cause?.message ?: error?.message)
                                    )
                                }
                            } finally {
                                if (safeBitmap !== bitmap) safeBitmap.recycle()
                                captureInProgress.set(false)
                            }
                        }
                    }
                } catch (error: Exception) {
                    captureInProgress.set(false)
                    AutomationBridge.update(status = "Ошибка снимка экрана: " + error.message)
                }
            }

            override fun onFailure(errorCode: Int) {
                captureInProgress.set(false)
                AutomationBridge.update(
                    status = "Android не разрешил снимок экрана (код " + errorCode + ")"
                )
            }
        })
    }

    fun tapNormalized(xRatio: Float, yRatio: Float, durationMs: Long = 70L): Boolean {
        val width = (lastScreenshotWidth.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels).toFloat()
        val height = (lastScreenshotHeight.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()
        val x = (width * xRatio).coerceIn(0f, width - 1f)
        val y = (height * yRatio).coerceIn(0f, height - 1f)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceAtLeast(40L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeNormalized(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 420L
    ): Boolean {
        val width = (lastScreenshotWidth.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels).toFloat()
        val height = (lastScreenshotHeight.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()
        val path = Path().apply {
            moveTo(width * startX.coerceIn(0f, 1f), height * startY.coerceIn(0f, 1f))
            lineTo(width * endX.coerceIn(0f, 1f), height * endY.coerceIn(0f, 1f))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(120L, 1500L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapConfigured(key: String, defaultX: Float, defaultY: Float): Boolean {
        val point = AutomationCoordinates.get(this, key)
        val spec = AutomationCoordinates.specs.firstOrNull { it.key == key }
        val x = if (spec == null) defaultX else point.x
        val y = if (spec == null) defaultY else point.y
        return tapNormalized(x, y)
    }

    fun swipeConfigured(
        startKey: String,
        endKey: String,
        defaultStart: ScreenPoint,
        defaultEnd: ScreenPoint,
        durationMs: Long = 420L
    ): Boolean {
        val start = AutomationCoordinates.get(this, startKey)
        val end = AutomationCoordinates.get(this, endKey)
        return swipeNormalized(start.x, start.y, end.x, end.y, durationMs)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun saveLastScreenshot(): String? {
        val snapshot = synchronized(this) {
            lastScreenBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null
        return try {
            val directory = File(filesDir, "screenshots")
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, "screen_" + System.currentTimeMillis() + ".png")
            FileOutputStream(file).use { output ->
                snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.flush()
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            snapshot.recycle()
        }
    }

    /**
     * Attempts ACTION_SET_TEXT first. If the game exposes only an input-focused
     * node, clipboard paste is used as a second option.
     */
    fun replaceFocusedText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditableNode(root)
        if (editable != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value
                )
            }
            if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("SO2 search", value))
        return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Counts bright pixels only in the inner checkbox area, not its outer border.
     * Returns null if the sampled patch is outside the screenshot.
     */
    fun isCheckboxChecked(bitmap: Bitmap, centerX: Int, centerY: Int): Boolean? {
        val innerHalfWidth = (bitmap.width * 0.0045f).toInt().coerceAtLeast(3)
        val innerHalfHeight = (bitmap.height * 0.009f).toInt().coerceAtLeast(4)
        val left = centerX - innerHalfWidth
        val top = centerY - innerHalfHeight
        val right = centerX + innerHalfWidth
        val bottom = centerY + innerHalfHeight
        if (left < 0 || top < 0 || right >= bitmap.width || bottom >= bitmap.height) return null

        var brightPixels = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 210 &&
                    android.graphics.Color.green(pixel) > 210 &&
                    android.graphics.Color.blue(pixel) > 210
                ) {
                    brightPixels += 1
                }
            }
        }
        return brightPixels >= CHECKED_PIXEL_THRESHOLD
    }

    fun handleLowBalance(bitmap: Bitmap) {
        runCatching {
            val directory = File(filesDir, "alerts")
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, "low_balance_" + System.currentTimeMillis() + ".png")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.flush()
                output.fd.sync()
            }
            AutomationBridge.update(
                status = "Недостаточно голды. Снимок сохранён: " + file.name +
                    ". Мониторинг остановлен, экран телефона блокируется."
            )
        }.onFailure { error ->
            AutomationBridge.update(status = "Нехватка голды; ошибка сохранения снимка: " + error.message)
        }
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        private const val CHECKED_PIXEL_THRESHOLD = 5
    }
}
