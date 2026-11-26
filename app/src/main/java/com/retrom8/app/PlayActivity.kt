package com.retrom8.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import java.io.File

class PlayActivity : AppCompatActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var gameContainer: FrameLayout
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout

    private var isNes = true
    private var isFullZoom = false
    private var isFastForward = false
    private var gameName = "game"

    private val rootDir by lazy { File(Environment.getExternalStorageDirectory(), "RetroM8") }
    private val savesDir by lazy { File(rootDir, "Saves").apply { mkdirs() } }
    private val biosDir by lazy { File(rootDir, "BIOS").apply { mkdirs() } }

    // Bộ xử lý bắn liên thanh Turbo cho NES (nút X -> Turbo A, nút Y -> Turbo B)
    private val turboHandler = Handler(Looper.getMainLooper())
    private var turboAActive = false
    private var turboBActive = false
    private var turboPhase = false

    private val turboRunnable = object : Runnable {
        override fun run() {
            if (!turboAActive && !turboBActive) return
            turboPhase = !turboPhase
            val action = if (turboPhase) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            if (turboAActive) retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_A, 0)
            if (turboBActive) retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_B, 0)
            turboHandler.postDelayed(this, 55)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val system = intent.getStringExtra("SYSTEM") ?: "NES"
        val romPath = intent.getStringExtra("ROM_PATH") ?: return finish()
        gameName = intent.getStringExtra("GAME_NAME") ?: "game"
        isNes = (system == "NES")

        val coreName = if (isNes) "libfceumm.so" else "libpcsx_rearmed.so"
        val corePath = File(applicationInfo.nativeLibraryDir, coreName).absolutePath

        val sramFile = File(savesDir, "$gameName.srm")
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            gameFilePath = romPath
            systemDirectory = biosDir.absolutePath
            savesDirectory = savesDir.absolutePath
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
            if (sramFile.exists()) {
                saveRAMState = runCatching { sramFile.readBytes() }.getOrNull()
            }
        }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        setupLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveSramAndExit()
            }
        })
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun setupLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0B0D"))
        }

        // 1. Màn hình game ở giữa (tỷ lệ 4:3)
        gameContainer = FrameLayout(this).apply {
            addView(
                retroView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(gameContainer)

        // 2. Cụm điều khiển bên trái (L1/L2, SELECT, D-PAD, MENU/ZOOM)
        leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(6), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                dp(152),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }

        if (!isNes) {
            val shouldersLeft = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
            }
            shouldersLeft.addView(createHoldKeyButton("L2", KeyEvent.KEYCODE_BUTTON_L2, 1f))
            shouldersLeft.addView(createHoldKeyButton("L1", KeyEvent.KEYCODE_BUTTON_L1, 1f))
            leftPanel.addView(shouldersLeft)
        }

        val selectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)).apply {
                topMargin = dp(6)
            }
        }
        selectRow.addView(createHoldKeyButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, 1f))
        leftPanel.addView(selectRow)

        val dpad = DPadView(this) { keyCode, pressed ->
            val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            retroView.sendKeyEvent(action, keyCode, 0)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        leftPanel.addView(dpad)

        val leftBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        }
        leftBottom.addView(createActionChip("MENU") { saveSramAndExit() })
        leftBottom.addView(createActionChip("ZOOM") { toggleZoom() })
        leftPanel.addView(leftBottom)

        // 3. Cụm điều khiển bên phải (R1/R2, START, XYAB, SAVE/LOAD/2X)
        rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                dp(152),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        }

        if (!isNes) {
            val shouldersRight = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
            }
            shouldersRight.addView(createHoldKeyButton("R1", KeyEvent.KEYCODE_BUTTON_R1, 1f))
            shouldersRight.addView(createHoldKeyButton("R2", KeyEvent.KEYCODE_BUTTON_R2, 1f))
            rightPanel.addView(shouldersRight)
        }

        val startRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)).apply {
                topMargin = dp(6)
            }
        }
        startRow.addView(createHoldKeyButton("START", KeyEvent.KEYCODE_BUTTON_START, 1f))
        rightPanel.addView(startRow)

        val actionPad = ActionPadView(this, isNes) { btn, pressed ->
            handleActionPad(btn, pressed)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rightPanel.addView(actionPad)

        val rightBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        }
        rightBottom.addView(createActionChip("SAVE") { saveState() })
        rightBottom.addView(createActionChip("LOAD") { loadState() })
        rightBottom.addView(createActionChip("2X") { toggleFastForward() })
        rightPanel.addView(rightBottom)

        root.addView(leftPanel)
        root.addView(rightPanel)
        setContentView(root)

        root.post { updateGameViewport() }
    }

    private fun handleActionPad(btn: String, pressed: Boolean) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        if (isNes) {
            when (btn) {
                "A" -> retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_A, 0)
                "B" -> retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_B, 0)
                "X" -> setTurbo(isA = true, active = pressed)
                "Y" -> setTurbo(isA = false, active = pressed)
            }
        } else {
            val code = when (btn) {
                "A" -> KeyEvent.KEYCODE_BUTTON_A // Circle
                "B" -> KeyEvent.KEYCODE_BUTTON_B // Cross
                "X" -> KeyEvent.KEYCODE_BUTTON_X // Triangle
                "Y" -> KeyEvent.KEYCODE_BUTTON_Y // Square
                else -> return
            }
            retroView.sendKeyEvent(action, code, 0)
        }
    }

    private fun setTurbo(isA: Boolean, active: Boolean) {
        val wasRunning = turboAActive || turboBActive
        if (isA) {
            turboAActive = active
            if (!active) retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0)
        } else {
            turboBActive = active
            if (!active) retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, 0)
        }
        val isRunning = turboAActive || turboBActive
        if (!wasRunning && isRunning) {
            turboHandler.post(turboRunnable)
        } else if (!isRunning) {
            turboHandler.removeCallbacks(turboRunnable)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHoldKeyButton(label: String, keyCode: Int, weight: Float): TextView {
        val normalBg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC232329"))
            cornerRadius = 12f
            setStroke(2, Color.parseColor("#FF42424C"))
        }
        val activeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#FFE53935"))
            cornerRadius = 12f
            setStroke(2, Color.WHITE)
        }

        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = normalBg
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        background = activeBg
                        hapticTick()
                        retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = normalBg
                        retroView.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, 0)
                    }
                }
                true
            }
        }
    }

    private fun createActionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#D0D0DC"))
            textSize = 10f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA1B1B22"))
                cornerRadius = 10f
                setStroke(1, Color.parseColor("#FF383844"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnClickListener {
                hapticTick()
                onClick()
            }
        }
    }

    private fun updateGameViewport() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val (targetW, targetH) = if (isFullZoom) {
            val h = screenH
            val w = minOf(screenW, (h * 4f / 3f).toInt())
            w to h
        } else {
            val availW = screenW - dp(304)
            val hFromW = (availW * 3f / 4f).toInt()
            if (hFromW <= screenH) availW to hFromW else (screenH * 4f / 3f).toInt() to screenH
        }

        gameContainer.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        val alpha = if (isFullZoom) 0.65f else 1.0f
        leftPanel.alpha = alpha
        rightPanel.alpha = alpha
    }

    private fun toggleZoom() {
        isFullZoom = !isFullZoom
        updateGameViewport()
    }

    private fun toggleFastForward() {
        isFastForward = !isFastForward
        retroView.frameSpeed = if (isFastForward) 2 else 1
        Toast.makeText(this, if (isFastForward) "Tua nhanh 2X" else "Tốc độ 1X", Toast.LENGTH_SHORT).show()
    }

    private fun saveState() {
        Thread {
            val ok = runCatching {
                val bytes = retroView.serializeState()
                File(savesDir, "$gameName.state").writeBytes(bytes)
                true
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Đã lưu màn!" else "Lỗi lưu màn", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun loadState() {
        val stateFile = File(savesDir, "$gameName.state")
        if (!stateFile.exists()) {
            Toast.makeText(this, "Chưa có file lưu màn", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val ok = runCatching {
                retroView.unserializeState(stateFile.readBytes())
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Đã nạp màn!" else "Không nạp được màn", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun saveSramAndExit() {
        runCatching {
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) {
                File(savesDir, "$gameName.srm").writeBytes(sram)
            }
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) {
                File(savesDir, "$gameName.srm").writeBytes(sram)
            }
        }
    }

    override fun onDestroy() {
        turboHandler.removeCallbacks(turboRunnable)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
