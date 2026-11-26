package com.retrom8.app

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

fun Context.hapticTick() {
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(14, 90))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(14)
    }
}

/**
 * Cụm D-Pad 8 hướng bên trái (hỗ trợ trượt ngón tay không cần nhấc)
 */
class DPadView(
    context: Context,
    private val onDirectionChange: (keyCode: Int, pressed: Boolean) -> Unit
) : View(context) {

    private var up = false
    private var down = false
    private var left = false
    private var right = false

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC232329")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF42424C")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE53935")
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height) * 0.44f
        val arm = size * 0.36f
        val r = arm * 0.3f

        // Vẽ khung chữ thập
        val hRect = RectF(cx - size, cy - arm, cx + size, cy + arm)
        val vRect = RectF(cx - arm, cy - size, cx + arm, cy + size)
        canvas.drawRoundRect(hRect, r, r, basePaint)
        canvas.drawRoundRect(vRect, r, r, basePaint)
        canvas.drawRoundRect(hRect, r, r, borderPaint)
        canvas.drawRoundRect(vRect, r, r, borderPaint)

        // Highlight hướng đang bấm
        if (up) canvas.drawRoundRect(RectF(cx - arm, cy - size, cx + arm, cy - arm * 0.3f), r, r, activePaint)
        if (down) canvas.drawRoundRect(RectF(cx - arm, cy + arm * 0.3f, cx + arm, cy + size), r, r, activePaint)
        if (left) canvas.drawRoundRect(RectF(cx - size, cy - arm, cx - arm * 0.3f, cy + arm), r, r, activePaint)
        if (right) canvas.drawRoundRect(RectF(cx + arm * 0.3f, cy - arm, cx + size, cy + arm), r, r, activePaint)

        // Mũi tên chỉ hướng
        arrowPaint.textSize = arm * 0.65f
        val dy = arrowPaint.textSize * 0.35f
        canvas.drawText("▲", cx, cy - size * 0.66f + dy, arrowPaint)
        canvas.drawText("▼", cx, cy + size * 0.66f + dy, arrowPaint)
        canvas.drawText("◄", cx - size * 0.66f, cy + dy, arrowPaint)
        canvas.drawText("►", cx + size * 0.66f, cy + dy, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            updateState(false, false, false, false)
            return true
        }

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.46f
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = hypot(dx, dy)

        if (dist < radius * 0.16f) {
            updateState(false, false, false, false)
        } else {
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            val nRight = deg in -67.5..67.5
            val nDown = deg in 22.5..157.5
            val nLeft = deg >= 112.5 || deg <= -112.5
            val nUp = deg in -157.5..-22.5
            updateState(nUp, nDown, nLeft, nRight)
        }
        return true
    }

    private fun updateState(nUp: Boolean, nDown: Boolean, nLeft: Boolean, nRight: Boolean) {
        var changed = false
        var newlyPressed = false
        if (up != nUp) { up = nUp; onDirectionChange(KeyEvent.KEYCODE_DPAD_UP, nUp); changed = true; if (nUp) newlyPressed = true }
        if (down != nDown) { down = nDown; onDirectionChange(KeyEvent.KEYCODE_DPAD_DOWN, nDown); changed = true; if (nDown) newlyPressed = true }
        if (left != nLeft) { left = nLeft; onDirectionChange(KeyEvent.KEYCODE_DPAD_LEFT, nLeft); changed = true; if (nLeft) newlyPressed = true }
        if (right != nRight) { right = nRight; onDirectionChange(KeyEvent.KEYCODE_DPAD_RIGHT, nRight); changed = true; if (nRight) newlyPressed = true }
        if (newlyPressed) context.hapticTick()
        if (changed) invalidate()
    }
}

/**
 * Cụm 4 nút X Y A B bên phải (hỗ trợ đa điểm giữ 2-3 nút cùng lúc)
 */
class ActionPadView(
    context: Context,
    private val isNesMode: Boolean,
    private val onButtonChange: (btn: String, pressed: Boolean) -> Unit
) : View(context) {

    private data class PadBtn(
        val id: String,
        val label: String,
        val subLabel: String,
        val accent: Int,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var pressed: Boolean = false
    )

    private val buttons = listOf(
        PadBtn("X", "X", if (isNesMode) "T-A" else "△", Color.parseColor("#FFFB8C00")),
        PadBtn("Y", "Y", if (isNesMode) "T-B" else "□", Color.parseColor("#FF8E24AA")),
        PadBtn("A", "A", if (isNesMode) "" else "○", Color.parseColor("#FFE53935")),
        PadBtn("B", "B", if (isNesMode) "" else "✕", Color.parseColor("#FF1E88E5"))
    )

    private var btnRadius = 40f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val span = min(w, h) * 0.30f
        btnRadius = min(w, h) * 0.165f

        buttons[0].apply { this.cx = cx; this.cy = cy - span } // X (Trên)
        buttons[1].apply { this.cx = cx - span; this.cy = cy } // Y (Trái)
        buttons[2].apply { this.cx = cx + span; this.cy = cy } // A (Phải)
        buttons[3].apply { this.cx = cx; this.cy = cy + span } // B (Dưới)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        textPaint.textSize = btnRadius * 0.85f
        subPaint.textSize = btnRadius * 0.38f

        for (btn in buttons) {
            fillPaint.color = if (btn.pressed) btn.accent else Color.parseColor("#CC232329")
            strokePaint.color = if (btn.pressed) Color.WHITE else btn.accent
            canvas.drawCircle(btn.cx, btn.cy, btnRadius, fillPaint)
            canvas.drawCircle(btn.cx, btn.cy, btnRadius, strokePaint)

            val yOffset = if (btn.subLabel.isEmpty()) textPaint.textSize * 0.35f else textPaint.textSize * 0.18f
            canvas.drawText(btn.label, btn.cx, btn.cy + yOffset, textPaint)
            if (btn.subLabel.isNotEmpty()) {
                canvas.drawText(btn.subLabel, btn.cx, btn.cy + btnRadius * 0.68f, subPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val upIndex = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            event.actionIndex
        } else -1

        val newStates = BooleanArray(buttons.size)
        if (action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (i == upIndex) continue
                val px = event.getX(i)
                val py = event.getY(i)
                buttons.forEachIndexed { idx, btn ->
                    if (hypot(px - btn.cx, py - btn.cy) <= btnRadius * 1.28f) {
                        newStates[idx] = true
                    }
                }
            }
        }

        var changed = false
        var newlyPressed = false
        buttons.forEachIndexed { idx, btn ->
            if (btn.pressed != newStates[idx]) {
                btn.pressed = newStates[idx]
                onButtonChange(btn.id, btn.pressed)
                changed = true
                if (btn.pressed) newlyPressed = true
            }
        }
        if (newlyPressed) context.hapticTick()
        if (changed) invalidate()
        return true
    }
}
