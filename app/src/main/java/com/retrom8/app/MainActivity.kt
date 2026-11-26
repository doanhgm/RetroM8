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
    private lateinit var btnNes: Button
    private lateinit var btnPs1: Button
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    private val rootDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "RetroM8")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        buildLayout()
        checkPermissionsAndLoad()
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
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111114"))
            setPadding(24, 18, 24, 18)
        }

        // Cột trái: Logo + Chọn hệ máy
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(0, 0, 20, 0)
        }

        val title = TextView(this).apply {
            text = "RETRO M8"
            setTextColor(Color.parseColor("#FFE53935"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(4, 0, 0, 20)
        }

        btnNes = createTabButton("NES (4 Nút)") { switchSystem("NES") }
        btnPs1 = createTabButton("PS1 (PlayStation)") { switchSystem("PS1") }
        val btnReload = createTabButton("↻ Quét lại ROM") { loadGames() }.apply {
            setBackgroundColor(Color.parseColor("#24242B"))
        }

        sidebar.addView(title)
        sidebar.addView(btnNes)
        sidebar.addView(btnPs1)
        sidebar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        sidebar.addView(btnReload)

        // Cột phải: Danh sách game
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9EA8"))
            textSize = 13f
            setPadding(8, 0, 8, 12)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        rightCol.addView(statusText)
        rightCol.addView(scrollView)

        root.addView(sidebar)
        root.addView(rightCol)
        setContentView(root)
    }

    private fun createTabButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(24, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
            setOnClickListener { onClick() }
        }
    }

    private fun updateTabStyles() {
        val activeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#FFE53935"))
            cornerRadius = 14f
        }
        val normalBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1D1D24"))
            cornerRadius = 14f
        }
        btnNes.background = if (currentSystem == "NES") activeBg else normalBg
        btnPs1.background = if (currentSystem == "PS1") activeBg else normalBg
    }

    private fun switchSystem(sys: String) {
        currentSystem = sys
        updateTabStyles()
        loadGames()
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
        listOf("NES", "PS1", "BIOS", "Saves").forEach {
            File(rootDir, it).mkdirs()
        }
    }

    private fun loadGames() {
        listContainer.removeAllViews()
        val folder = File(rootDir, currentSystem)
        if (!folder.exists()) folder.mkdirs()

        val validExts = if (currentSystem == "NES") {
            setOf("nes", "zip")
        } else {
            setOf("chd", "pbp", "cue", "iso", "img")
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

        statusText.text = "Thư mục: /RetroM8/$currentSystem  •  Tìm thấy ${roms.size} game"

        if (roms.isEmpty()) {
            val hint = TextView(this).apply {
                text = "Chưa có game nào trong /RetroM8/$currentSystem\n\n" +
                    if (currentSystem == "NES") {
                        "Copy file .nes hoặc .zip vào thư mục RetroM8/NES rồi bấm Quét lại ROM."
                    } else {
                        "Copy file .chd, .pbp, .iso hoặc .cue+.bin vào RetroM8/PS1.\n(Khuyên dùng .chd hoặc .pbp cho gọn 1 file duy nhất; có thể bỏ thêm scph1001.bin vào RetroM8/BIOS)."
                    }
                setTextColor(Color.parseColor("#B0B0BC"))
                textSize = 14f
                setPadding(16, 24, 16, 16)
            }
            listContainer.addView(hint)
            return
        }

        roms.forEach { rom ->
            val item = Button(this).apply {
                text = "▸  ${rom.nameWithoutExtension}"
                isAllCaps = false
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(24, 18, 24, 18)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C23"))
                    cornerRadius = 12f
                    setStroke(2, Color.parseColor("#2E2E38"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
