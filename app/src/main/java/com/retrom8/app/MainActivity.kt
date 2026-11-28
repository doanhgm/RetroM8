package com.retrom8.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private var currentSystem = "NES"
    private val tabButtons = mutableMapOf<String, Button>()
    private lateinit var btnWifi: Button
    private lateinit var wifiBanner: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    private val rootDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "RetroM8")
    }

    private var wifiServer: WifiRomServer? = null

    private val systemColors = mapOf(
        "NES" to "#FFE53935",
        "SNES" to "#FF7E57C2",
        "GBA" to "#FF3949AB",
        "PS1" to "#FFD81B60"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        buildLayout()
        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111114"))
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }

        // Thanh tiêu đề gọn ở trên cùng (chỉ hiển thị thông tin, không có nút bấm)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val title = TextView(this).apply {
            text = "RETRO M8"
            setTextColor(Color.parseColor("#FFE53935"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(12), 0)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9EA8"))
            textSize = 12f
            maxLines = 1
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerRow.addView(title)
        headerRow.addView(statusText)
        root.addView(headerRow)

        wifiBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundBg("#FF1B4D3E", "#FF43A047")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        root.addView(wifiBanner)

        // Danh sách game chiếm toàn bộ phần giữa
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        // Thanh chọn hệ game + Wi-Fi + Quét lại nằm gọn ở dưới cùng
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(8) }
        }

        val tabs = listOf(
            "NES" to "NES",
            "SNES" to "SNES",
            "GBA" to "GBA",
            "PS1" to "PS1"
        )
        tabs.forEach { (sys, label) ->
            val btn = createBottomButton(label, 1f) { switchSystem(sys) }
            tabButtons[sys] = btn
            bottomBar.addView(btn)
        }

        btnWifi = createBottomButton("📡 Wi-Fi", 1.15f) { toggleWifiServer() }.apply {
            background = roundBg("#FF1E3A2F", "#FF2E7D32")
        }
        val btnReload = createBottomButton("↻ Quét", 0.9f) { loadGames() }.apply {
            background = roundBg("#FF24242B", "#FF383844")
        }

        bottomBar.addView(btnWifi)
        bottomBar.addView(btnReload)
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun createBottomButton(label: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            maxLines = 1
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            ).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun roundBg(fillHex: String, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(8).toFloat()
            if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
        }
    }

    private fun updateTabStyles() {
        tabButtons.forEach { (sys, btn) ->
            val color = if (sys == currentSystem) systemColors[sys] ?: "#FFE53935" else "#FF1D1D24"
            val stroke = if (sys == currentSystem) "#FFFFFFFF" else "#FF2E2E38"
            btn.background = roundBg(color, stroke)
        }
    }

    private fun switchSystem(sys: String) {
        currentSystem = sys
        updateTabStyles()
        loadGames()
    }

    private fun toggleWifiServer() {
        val current = wifiServer
        if (current != null && current.isRunning) {
            current.stop()
            wifiServer = null
            wifiBanner.visibility = View.GONE
            btnWifi.text = "📡 Wi-Fi"
            btnWifi.background = roundBg("#FF1E3A2F", "#FF2E7D32")
            return
        }

        ensureFolders()
        val server = WifiRomServer(rootDir, 8080) { sys, fileName ->
            runOnUiThread {
                Toast.makeText(this, "Đã nhận [$sys]: $fileName", Toast.LENGTH_SHORT).show()
                if (sys == currentSystem) loadGames()
            }
        }
        val ip = server.start()
        if (ip == null) {
            Toast.makeText(this, "Hãy bật Wi-Fi trên HTC M8 trước!", Toast.LENGTH_SHORT).show()
            return
        }
        wifiServer = server
        wifiBanner.text = "📡 Mở trình duyệt trên PC vào:  http://$ip:8080  để gửi ROM"
        wifiBanner.visibility = View.VISIBLE
        btnWifi.text = "⏹ Tắt Wi-Fi"
        btnWifi.background = roundBg("#FF6D1B1B", "#FFE53935")
    }

    private fun checkPermissionsAndLoad() {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val granted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            ensureFolders()
            switchSystem("NES")
        } else {
            ActivityCompat.requestPermissions(this, perms, 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensureFolders()
        switchSystem("NES")
    }

    private fun ensureFolders() {
        listOf("NES", "SNES", "GBA", "PS1", "BIOS", "Saves").forEach {
            File(rootDir, it).mkdirs()
        }
    }

    private fun loadGames() {
        listContainer.removeAllViews()
        val folder = File(rootDir, currentSystem)
        if (!folder.exists()) folder.mkdirs()

        val validExts = when (currentSystem) {
            "NES" -> setOf("nes", "zip")
            "SNES" -> setOf("sfc", "smc", "zip")
            "GBA" -> setOf("gba", "zip")
            else -> setOf("chd", "pbp", "cue", "iso", "img")
        }

        val allFiles = folder.listFiles()?.filter { it.isFile } ?: emptyList()
        val cueNames = allFiles.filter { it.extension.equals("cue", true) }
            .map { it.nameWithoutExtension.lowercase() }.toSet()

        val roms = allFiles.filter { file ->
            val ext = file.extension.lowercase()
            when {
                ext in validExts -> true
                currentSystem == "PS1" && ext == "bin" && file.nameWithoutExtension.lowercase() !in cueNames -> true
                else -> false
            }
        }.sortedBy { it.name.lowercase() }

        statusText.text = "/RetroM8/$currentSystem  •  ${roms.size} game"

        if (roms.isEmpty()) {
            val hintText = when (currentSystem) {
                "NES" -> "Chép file .nes / .zip vào RetroM8/NES hoặc bấm '📡 Wi-Fi' ở dưới."
                "SNES" -> "Chép file .sfc / .smc / .zip vào RetroM8/SNES hoặc bấm '📡 Wi-Fi' ở dưới."
                "GBA" -> "Chép file .gba / .zip vào RetroM8/GBA hoặc bấm '📡 Wi-Fi' ở dưới."
                else -> "Chép file .chd / .pbp / .iso / .cue+.bin vào RetroM8/PS1.\nChép thêm scph5500.bin / scph5501.bin vào RetroM8/BIOS để tránh đen màn hình."
            }
            val hint = TextView(this).apply {
                text = "Chưa có game $currentSystem nào.\n\n$hintText"
                setTextColor(Color.parseColor("#B0B0BC"))
                textSize = 14f
                setPadding(dp(8), dp(16), dp(8), dp(12))
            }
            listContainer.addView(hint)
            return
        }

        roms.forEach { rom ->
            val item = Button(this).apply {
                text = "▸  ${rom.nameWithoutExtension}"
                isAllCaps = false
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                minHeight = 0
                minimumHeight = 0
                background = roundBg("#FF1C1C23", "#FF2E2E38")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    val intent = Intent(this@MainActivity, PlayActivity::class.java).apply {
                        putExtra("SYSTEM", currentSystem)
                        putExtra("ROM_PATH", rom.absolutePath)
                        putExtra("GAME_NAME", rom.nameWithoutExtension)
                    }
                    startActivity(intent)
                }
            }
            listContainer.addView(item)
        }
    }

    override fun onDestroy() {
        wifiServer?.stop()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
// END MainActivity
