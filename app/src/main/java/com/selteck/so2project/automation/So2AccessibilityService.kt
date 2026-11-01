package com.selteck.so2project.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

class So2AccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var textAnalyzer: ScreenTextAnalyzer
    private val captureInProgress = AtomicBoolean(false)
    private var loopStarted = false

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
        textAnalyzer = ScreenTextAnalyzer()
        AutomationBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return
        AutomationBridge.update(foregroundPackage = packageName)
        if (packageName == AutomationBridge.targetPackage &&
            AutomationBridge.monitoringRequested && !loopStarted) {
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
                    textAnalyzer.recognize(safeBitmap)
                        .addOnSuccessListener { text ->
                            val reading = ScreenClassifier.classify(text)
                            AutomationBridge.update(
                                lastText = text,
                                lastScreen = reading.kind.name,
                                status = "Экран распознан: " + reading.kind
                            )
                        }
                        .addOnFailureListener { error ->
                            AutomationBridge.update(status = "Ошибка распознавания: " + error.message)
                        }
                        .addOnCompleteListener {
                            if (safeBitmap !== bitmap) safeBitmap.recycle()
                            captureInProgress.set(false)
                        }
                } catch (error: Exception) {
                    captureInProgress.set(false)
                    AutomationBridge.update(status = "Ошибка снимка экрана: " + error.message)
                }
            }
            override fun onFailure(errorCode: Int) {
                captureInProgress.set(false)
                AutomationBridge.update(status = "Android не разрешил снимок экрана (код " + errorCode + ")")
            }
        })
    }

    fun tapNormalized(xRatio: Float, yRatio: Float, durationMs: Long = 70L): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val x = (width * xRatio).coerceIn(0f, width)
        val y = (height * yRatio).coerceIn(0f, height)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(40L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
