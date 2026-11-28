package com.retrom8.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
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
import com.swordfish.libretrodroid.Variable
import java.io.File

class PlayActivity : AppCompatActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var gameContainer: FrameLayout
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var dpadView: DPadView
    private lateinit var actionPadView: ActionPadView
    private lateinit var btnSizeChip: TextView
    private lateinit var btnPauseChip: TextView
    private lateinit var pauseOverlay: FrameLayout

    private val prefs by lazy { getSharedPreferences("retrom8_prefs", MODE_PRIVATE) }

    private var system = "NES"
    private var accentColor = Color.parseColor("#FFE53935")
    // 0 = Vừa (150dp), 1 = To (172dp), 2 = Siêu to (196dp)
    private var padSizeIndex = 0
    // 0 = Vừa khung (không đè phím), 1 = Tràn màn (mờ 65%), 2 = Tràn màn (mờ 40%)
    private var zoomMode = 0
    private var isFastForward = false
    private var isPaused = false
    private var savePrefix = "NES_game"

    private val rootDir by lazy { File(Environment.getExternalStorageDirectory(), "RetroM8") }
    private val savesDir by lazy { File(rootDir, "Saves").apply { mkdirs() } }
    private val biosDir by lazy { File(rootDir, "BIOS").apply { mkdirs() } }

    // Turbo bắn liên thanh cho NES & GBA (nút X -> Turbo A, nút Y -> Turbo B)
    private val turboHandler = Handler(Looper.getMainLooper())
    private var turboAActive = false
    private var turboBActive = false
    private var turboPhase = false

    private val turboRunnable = object : Runnable {
        override fun run() {
            if (isPaused || (!turboAActive && !turboBActive)) return
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

        padSizeIndex = prefs.getInt("pref_pad_size", 0).coerceIn(0, 2)
        zoomMode = prefs.getInt("pref_zoom_mode_v2", 0).coerceIn(0, 2)

        system = intent.getStringExtra("SYSTEM") ?: "NES"
        val romPath = intent.getStringExtra("ROM_PATH") ?: return finish()
        val gameName = intent.getStringExtra("GAME_NAME") ?: "game"
        savePrefix = "${system}_${gameName}"

        accentColor = when (system) {
            "SNES" -> Color.parseColor("#FF7E57C2")
            "GBA" -> Color.parseColor("#FF3949AB")
            "PS1" -> Color.parseColor("#FFD81B60")
            else -> Color.parseColor("#FFE53935")
        }

        if (system == "PS1") {
            preparePs1Bios()
        }

        val coreName = when (system) {
            "SNES" -> "libsnes9x.so"
            "GBA" -> "libmgba.so"
            "PS1" -> "libpcsx_rearmed.so"
            else -> "libfceumm.so"
        }
        val corePath = File(applicationInfo.nativeLibraryDir, coreName).absolutePath

        val sramFile = File(savesDir, "$savePrefix.srm")
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            gameFilePath = romPath
            systemDirectory = biosDir.absolutePath
            savesDirectory = savesDir.absolutePath
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
            if (system == "PS1") {
                variables = arrayOf(
                    Variable("pcsx_rearmed_bios", "auto"),
                    Variable("pcsx_rearmed_show_bios_bootlogo", "enabled")
                )
            }
            if (sramFile.exists()) {
                saveRAMState = runCatching { sramFile.readBytes() }.getOrNull()
            }
        }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        setupLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPaused) {
                    resumeGame()
                } else {
                    saveSramAndExit()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (isPaused) {
            retroView.post {
                if (isPaused) {
                    runCatching { retroView.onPause() }
                }
            }
        }
    }

    private fun preparePs1Bios() {
        val knownBios = setOf(
            "scph5500.bin",
            "scph5501.bin",
            "scph5502.bin",
            "scph1001.bin",
            "scph7001.bin",
            "psxonpsp660.bin"
        )
        val files = biosDir.listFiles()?.filter { it.isFile } ?: emptyList()
        files.forEach { file ->
            val lower = file.name.lowercase()
            if (lower in knownBios && file.name != lower) {
                val target = File(biosDir, lower)
                if (!target.exists()) {
                    runCatching { file.renameTo(target) }
                }
            }
        }
        val found = biosDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase() in knownBios }
            ?.map { it.name }
            .orEmpty()

        val msg = if (found.isNotEmpty()) {
            "Đã nạp BIOS PS1: ${found.first()}"
        } else {
            "Thiếu BIOS trong /RetroM8/BIOS (đang chạy BIOS giả dễ đen màn)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun panelWidthDp(): Int = when (padSizeIndex) {
        0 -> 150
        1 -> 172
        else -> 196
    }

    private fun padSizeLabel(): String = when (padSizeIndex) {
        0 -> "CỠ: M"
        1 -> "CỠ: L"
        else -> "CỠ: XL"
    }

    private fun setupLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0B0D"))
        }

        // 1. Màn hình game ở giữa
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

        val initPanelW = dp(panelWidthDp())

        // 2. Cụm điều khiển bên trái
        leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                initPanelW,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }

        when (system) {
            "PS1" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                }
                row.addView(createHoldKeyButton("L2", KeyEvent.KEYCODE_BUTTON_L2, 1f))
                row.addView(createHoldKeyButton("L1", KeyEvent.KEYCODE_BUTTON_L1, 1f))
                leftPanel.addView(row)
            }
            "SNES", "GBA" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                }
                row.addView(createHoldKeyButton("L", KeyEvent.KEYCODE_BUTTON_L1, 1f))
                leftPanel.addView(row)
            }
        }

        val selectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(4)
            }
        }
        selectRow.addView(createHoldKeyButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, 1f))
        leftPanel.addView(selectRow)

        dpadView = DPadView(this, accentColor) { keyCode, pressed ->
            if (!isPaused) {
                val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                retroView.sendKeyEvent(action, keyCode, 0)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        leftPanel.addView(dpadView)

        val leftBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
        }
        leftBottom.addView(createActionChip("MENU") { saveSramAndExit() })
        leftBottom.addView(createActionChip("ZOOM") { cycleZoomMode() })
        btnSizeChip = createActionChip(padSizeLabel()) { cyclePadSize() }
        leftBottom.addView(btnSizeChip)
        leftPanel.addView(leftBottom)

        // 3. Cụm điều khiển bên phải
        rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                initPanelW,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        }

        when (system) {
            "PS1" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                }
                row.addView(createHoldKeyButton("R1", KeyEvent.KEYCODE_BUTTON_R1, 1f))
                row.addView(createHoldKeyButton("R2", KeyEvent.KEYCODE_BUTTON_R2, 1f))
                rightPanel.addView(row)
            }
            "SNES", "GBA" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                }
                row.addView(createHoldKeyButton("R", KeyEvent.KEYCODE_BUTTON_R1, 1f))
                rightPanel.addView(row)
            }
        }

        val startRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(4)
            }
        }
        startRow.addView(createHoldKeyButton("START", KeyEvent.KEYCODE_BUTTON_START, 1f))
        rightPanel.addView(startRow)

        actionPadView = ActionPadView(this, system) { btn, pressed ->
            if (!isPaused) handleActionPad(btn, pressed)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rightPanel.addView(actionPadView)

        val rightBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
        }
        rightBottom.addView(createActionChip("SAVE") { saveState() })
        rightBottom.addView(createActionChip("LOAD") { loadState() })
        rightBottom.addView(createActionChip("2X") { toggleFastForward() })
        rightPanel.addView(rightBottom)

        // 4. Nút PAUSE nằm gọn ở chính giữa cạnh dưới
        btnPauseChip = createActionChip("⏸ PAUSE") { pauseGame() }.apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                dp(80),
                dp(28),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(6)
            }
        }

        // 5. Màn hình phủ khi tạm dừng
        pauseOverlay = buildPauseOverlay()

        root.addView(leftPanel)
        root.addView(rightPanel)
        root.addView(btnPauseChip)
        root.addView(pauseOverlay)
        setContentView(root)

        root.post { updateGameViewport() }
    }

    private fun buildPauseOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0F"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2181820"))
                cornerRadius = dp(14).toFloat()
                setStroke(2, accentColor)
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(240),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val heading = TextView(this).apply {
            text = "⏸  TẠM DỪNG"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }

        val btnResume = TextView(this).apply {
            text = "▶  TIẾP TỤC"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                hapticTick()
                resumeGame()
            }
        }

        val btnExit = TextView(this).apply {
            text = "🚪  THOÁT GAME"
            setTextColor(Color.parseColor("#D8D8E4"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF252530"))
                cornerRadius = dp(10).toFloat()
                setStroke(1, Color.parseColor("#FF3E3E4C"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                hapticTick()
                saveSramAndExit()
            }
        }

        card.addView(heading)
        card.addView(btnResume)
        card.addView(btnExit)
        overlay.addView(card)
        return overlay
    }

    private fun pauseGame() {
        if (isPaused) return
        isPaused = true
        stopAllTurbo()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { retroView.onPause() }
        pauseOverlay.visibility = View.VISIBLE
    }

    private fun resumeGame() {
        if (!isPaused) return
        isPaused = false
        pauseOverlay.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            retroView.onResume()
            retroView.frameSpeed = if (isFastForward) 2 else 1
        }
        hideSystemUI()
    }

    private fun stopAllTurbo() {
        turboAActive = false
        turboBActive = false
        turboHandler.removeCallbacks(turboRunnable)
        runCatching {
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0)
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, 0)
        }
    }

    private fun handleActionPad(btn: String, pressed: Boolean) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        if (system == "NES" || system == "GBA") {
            when (btn) {
                "A" -> retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_A, 0)
                "B" -> retroView.sendKeyEvent(action, KeyEvent.KEYCODE_BUTTON_B, 0)
                "X" -> setTurbo(isA = true, active = pressed)
                "Y" -> setTurbo(isA = false, active = pressed)
            }
        } else {
            val code = when (btn) {
                "A" -> KeyEvent.KEYCODE_BUTTON_A
                "B" -> KeyEvent.KEYCODE_BUTTON_B
                "X" -> KeyEvent.KEYCODE_BUTTON_X
                "Y" -> KeyEvent.KEYCODE_BUTTON_Y
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
            setColor(accentColor)
            cornerRadius = 12f
            setStroke(2, Color.WHITE)
        }

        return TextView(this).apply {
            text = label
            maxLines = 1
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = normalBg
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnTouchListener { _, event ->
                if (isPaused) return@setOnTouchListener true
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
            maxLines = 1
            setTextColor(Color.parseColor("#D0D0DC"))
            textSize = 9f
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
        val aspect = if (system == "GBA") 3f / 2f else 4f / 3f

        // Cập nhật chiều rộng 2 cụm điều khiển
        val panelW = minOf(dp(panelWidthDp()), (screenW * 0.34f).toInt())
        (leftPanel.layoutParams as FrameLayout.LayoutParams).width = panelW
        (rightPanel.layoutParams as FrameLayout.LayoutParams).width = panelW
        leftPanel.requestLayout()
        rightPanel.requestLayout()
        dpadView.invalidate()
        actionPadView.invalidate()

        val (targetW, targetH) = if (zoomMode != 0) {
            val h = screenH
            val w = minOf(screenW, (h * aspect).toInt())
            w to h
        } else {
            // Vừa khít khoảng trống giữa 2 cụm phím và không bị nút PAUSE ở dưới che
            val availW = (screenW - panelW * 2 - dp(8)).coerceAtLeast(dp(120))
            val availH = (screenH - dp(76)).coerceAtLeast(dp(120))
            val hFromW = (availW / aspect).toInt()
            if (hFromW <= availH) availW to hFromW else (availH * aspect).toInt() to availH
        }

        gameContainer.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)

        val alpha = when (zoomMode) {
            1 -> 0.65f
            2 -> 0.40f
            else -> 1.0f
        }
        leftPanel.alpha = alpha
        rightPanel.alpha = alpha
        btnPauseChip.alpha = alpha
    }

    private fun cyclePadSize() {
        padSizeIndex = (padSizeIndex + 1) % 3
        prefs.edit().putInt("pref_pad_size", padSizeIndex).apply()
        btnSizeChip.text = padSizeLabel()
        updateGameViewport()
        val msg = when (padSizeIndex) {
            0 -> "Cỡ phím: Vừa (M)"
            1 -> "Cỡ phím: To (L)"
            else -> "Cỡ phím: Siêu to (XL)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun cycleZoomMode() {
        zoomMode = (zoomMode + 1) % 3
        prefs.edit().putInt("pref_zoom_mode_v2", zoomMode).apply()
        updateGameViewport()
        val msg = when (zoomMode) {
            0 -> "Màn hình: Vừa khung (không đè phím)"
            1 -> "Màn hình: Tràn viền (Phím mờ vừa)"
            else -> "Màn hình: Tràn viền (Phím siêu mờ)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
                File(savesDir, "$savePrefix.state").writeBytes(bytes)
                true
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Đã lưu màn!" else "Lỗi lưu màn", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun loadState() {
        val stateFile = File(savesDir, "$savePrefix.state")
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
                File(savesDir, "$savePrefix.srm").writeBytes(sram)
            }
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) {
                File(savesDir, "$savePrefix.srm").writeBytes(sram)
            }
        }
    }

    override fun onDestroy() {
        turboHandler.removeCallbacks(turboRunnable)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
// END PlayActivity
