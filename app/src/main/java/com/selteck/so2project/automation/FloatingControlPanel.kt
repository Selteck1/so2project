package com.selteck.so2project.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Small draggable in-game control surface. Accessibility overlays do not need
 * the separate SYSTEM_ALERT_WINDOW permission.
 */
class FloatingControlPanel(private val service: So2AccessibilityService) {
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var statusText: TextView? = null
    private var detailsText: TextView? = null
    private var preview: ImageView? = null
    private var pauseButton: Button? = null
    private var detailsVisible = false
    private var lastPreview: Bitmap? = null

    private val params = WindowManager.LayoutParams(
        dp(188),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(48)
    }

    fun show() {
        if (root != null) return
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 18, 22, 29))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(87, 101, 119))
            }
        }
        root = panel

        val header = TextView(service).apply {
            text = "SO2  •  ПАНЕЛЬ  ↕"
            setTextColor(Color.rgb(255, 190, 90))
            textSize = 10f
            setPadding(dp(2), dp(2), dp(2), dp(3))
            setOnTouchListener(DragListener())
        }
        panel.addView(header)

        statusText = TextView(service).apply {
            text = "Ожидание экрана…"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
            setPadding(dp(3), 0, dp(3), dp(6))
        }
        panel.addView(statusText)

        val firstRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = makeButton("ПАУЗА") {
            AutomationBridge.paused = !AutomationBridge.paused
            AutomationBridge.update(
                status = if (AutomationBridge.paused) "Пауза включена. Касания отключены." else "Продолжаю мониторинг."
            )
            refreshButtons()
        }
        firstRow.addView(pauseButton, weightedButton())
        firstRow.addView(makeButton("СТОП") {
            AutomationBridge.paused = false
            AutomationBridge.monitoringRequested = false
            AutomationBridge.update(status = "Мониторинг остановлен из мини-меню.")
            refreshButtons()
        }, weightedButton())
        panel.addView(firstRow)

        val secondRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(makeButton("СКРИН") {
            service.captureCleanScreenshot()
        }, weightedButton())
        secondRow.addView(makeButton("ПРОСМОТР") {
            detailsVisible = !detailsVisible
            preview?.visibility = if (detailsVisible) View.VISIBLE else View.GONE
            if (detailsVisible) preview?.setImageBitmap(lastPreview)
            detailsText?.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        }, weightedButton())
        panel.addView(secondRow)

        preview = ImageView(service).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(6, 8, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            ).apply { topMargin = dp(7) }
        }
        panel.addView(preview)

        detailsText = TextView(service).apply {
            visibility = View.GONE
            setTextColor(Color.LTGRAY)
            textSize = 9f
            maxLines = 5
            setPadding(dp(2), dp(4), dp(2), dp(2))
        }
        panel.addView(detailsText)

        panel.addView(makeButton("СВЕРНУТЬ / ЗАКРЫТЬ") {
            remove()
        })

        windowManager.addView(panel, params)
        refreshButtons()
    }

    fun setVisible(visible: Boolean) {
        val view = root ?: return
        runCatching {
            if (visible && view.parent == null) windowManager.addView(view, params)
            else if (!visible && view.parent != null) windowManager.removeView(view)
        }
    }

    fun update(status: String, bitmap: Bitmap?, ocrText: String?) {
        statusText?.text = status.take(150)
        detailsText?.text = "Распознанный экран:\n" + (ocrText ?: "пока нет данных").take(280)
        if (bitmap != null) {
            val scaled = Bitmap.createScaledBitmap(bitmap, 480, 210, true)
            preview?.setImageDrawable(null)
            lastPreview?.recycle()
            lastPreview = scaled
            if (detailsVisible) preview?.setImageBitmap(scaled)
        }
        refreshButtons()
    }

    fun remove() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        statusText = null
        detailsText = null
        preview = null
        pauseButton = null
        lastPreview?.recycle()
        lastPreview = null
    }

    private fun refreshButtons() {
        pauseButton?.text = if (AutomationBridge.paused) "ПРОДОЛЖИТЬ" else "ПАУЗА"
        statusText?.text = AutomationBridge.state.value.status.take(150)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(service).apply {
            text = label
            textSize = 9f
            isAllCaps = false
            minHeight = dp(28)
            minimumHeight = dp(28)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    private fun weightedButton() = LinearLayout.LayoutParams(
        0, dp(30), 1f
    ).apply {
        marginEnd = dp(3)
        bottomMargin = dp(3)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downRawX).toInt()
                    params.y = startY + (event.rawY - downRawY).toInt()
                    root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    return true
                }
            }
            return false
        }
    }
}
