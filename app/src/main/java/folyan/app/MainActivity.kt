package folyan.app

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.chaquo.python.Python
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private val DOWNLOAD_HEIGHTS = listOf(144, 240, 360, 480, 720, 1080)
private val DOWNLOAD_CODECS = listOf("vp", "avc1", "av01")
private val DOWNLOAD_CODEC_NAMES = listOf("VP9", "H.264", "AV1")
private val DOWNLOAD_FORMATS = listOf("mkv", "mp4", "wav", "mp3", "m4a", "opus")
private val CONVERT_FORMATS = listOf("mp4", "mkv", "mp3", "wav", "m4a", "opus", "ogg", "flac", "aac")
private val MERGE_FORMATS = listOf("mp4", "mkv")

class MainActivity : ComponentActivity() {

    private val py by lazy { Python.getInstance() }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val content by lazy { FrameLayout(this) }
    private val ffmpeg by lazy { applicationInfo.nativeLibraryDir + "/libffmpeg.so" }
    private val downloadsDir by lazy {
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.absolutePath
    }
    private val stateDir by lazy { filesDir.absolutePath }

    private var progressView: TextView? = null
    private var atMenu = true
    private var convSrc: String? = null
    private var convSrc2: String? = null
    private var pickHandler: ((Uri) -> Unit)? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickHandler?.invoke(uri)
    }

    private val poll = object : Runnable {
        override fun run() {
            progressView?.let { v ->
                try {
                    v.text = py.getModule("dl").callAttr("status").toString()
                } catch (e: Exception) {
                }
            }
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyIfAvailable(this)
        super.onCreate(savedInstanceState)
        py.getModule("native").callAttr("init", applicationInfo.nativeLibraryDir)
        setContentView(content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!atMenu) showMenu() else finish()
            }
        })
        showMenu()
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            py.getModule("dl").callAttr("cancel")
        } catch (e: Exception) {
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun setScreen(view: View) {
        content.removeAllViews()
        content.addView(view)
        atMenu = false
    }

    private fun screen(title: String, menu: Boolean = false, build: LinearLayout.() -> Unit): View {
        progressView = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        if (!menu) box.button("← Меню") { showMenu() }
        
        if (menu) {
            box.addCoin()
        } else {
            box.label(title).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        }
        
        box.build()
        return ScrollView(this).apply { addView(box) }
    }

    // Монетка: крутится через масштабирование scaleX и меняет 0 на 1
    private fun LinearLayout.addCoin() {
        val card = MaterialCardView(this@MainActivity).apply {
            radius = 160f
            strokeWidth = 6
            strokeColor = Color.parseColor("#4A5568")
            cardElevation = 12f
            layoutParams = LinearLayout.LayoutParams(280, 280).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 32, 0, 48)
            }
        }
        val text = TextView(this@MainActivity).apply {
            this.text = "0"
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        card.addView(text)
        addView(card)

        var side = 0
        var flipped = false
        ValueAnimator.ofFloat(1f, 0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                card.scaleX = scale
                if (scale < 0.1f && !flipped) {
                    side = if (side == 0) 1 else 0
                    text.text = side.toString()
                    flipped = true
                } else if (scale > 0.5f) {
                    flipped = false
                }
            }
            start()
        }
    }

    private fun LinearLayout.label(text: String): TextView {
        val v = TextView(this@MainActivity).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 16)
        }
        addView(v)
        return v
    }

    private fun LinearLayout.field(
        hint: String, value: String = "", lines: Int = 1, password: Boolean = false
    ): EditText {
        val e = EditText(this@MainActivity).apply {
            this.hint = hint
            setText(value)
            minLines = lines
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 16) }
        }
        addView(e)
        return e
    }

    // Круглая пилюля с нормальным регистром текста
    private fun LinearLayout.button(text: String, onClick: () -> Unit): Button {
        val b = MaterialButton(this@MainActivity).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = 72
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                160
            ).apply { setMargins(0, 8, 0, 16) }
            setOnClickListener { onClick() }
        }
        addView(b)
        return b
    }

    private fun LinearLayout.check(text: String, on: Boolean = false): CheckBox {
        val c = CheckBox(this@MainActivity).apply {
            this.text = text
            isChecked = on
            setPadding(16, 16, 16, 16)
        }
        addView(c)
        return c
    }

    private fun LinearLayout.spinner(items: List<String>, selected: Int = 0): Spinner {
        val s = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items
            )
            setSelection(selected)
            setPadding(0, 16, 0, 16)
        }
        addView(s)
        return s
    }

    private fun busy(out: TextView, start: String, job: () -> String) {
        out.text = "$start..."
        worker.execute {
            val text = try {
                job()
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            main.post { out.text = text }
        }
    }

    private fun showMenu() {
        setScreen(screen("Folyan", menu = true) {
            button("Интерпретатор") { showInterpreter() }
            button("Менеджер паролей") { showPasswords() }
            button("Стеганография") { showStego() }
            button("Качалка") { showDownloader() }
            button("Конвертер") { showConverter() }
            button("Выход") { finish() }
        })
        atMenu = true
    }

    private fun showInterpreter() {
        setScreen(screen("Интерпретатор") {
            val code = field("Python-код", lines = 8)
            val out = label("")
            button("Запустить") {
                val src = code.text.toString()
                busy(out, "Выполняю") {
                    py.getModule("features").callAttr("interp_run", src).toString()
                }
            }
        })
    }

    private fun loadState(name: String): JSONObject = try {
        JSONObject(py.getModule("state").callAttr("load", name, stateDir).toString())
    } catch (e: Exception) {
        JSONObject()
    }

    private fun showPasswords() {
        val saved = loadState("passwords.json")
        setScreen(screen("Менеджер паролей") {
            val salt = field("Соль", saved.optString("salt", "FolyanApp"))
            val master = field("Мастер-пароль", saved.optString("master", ""), password = true)
            val site = field("Сайт", saved.optString("site", ""))
            val iters = field("Итерации", saved.optString("iterations", "500000"))
            val rememberFields = check("Запоминать соль, сайт и итерации", saved.has("salt"))
            val rememberMaster = check("Запоминать мастер-пароль", saved.has("master"))
            val out = label("")
            button("Сгенерировать") {
                val n = iters.text.toString().toIntOrNull() ?: 0
                val m = master.text.toString()
                val s = salt.text.toString()
                val t = site.text.toString()
                if (n < 1) {
                    out.text = "Итерации: целое число от 1"
                    return@button
                }
                val data = JSONObject()
                if (rememberFields.isChecked) {
                    data.put("salt", s)
                    data.put("site", t)
                    data.put("iterations", n.toString())
                }
                if (rememberMaster.isChecked) data.put("master", m)
                busy(out, "Считаю") {
                    py.getModule("state").callAttr("save", "passwords.json", stateDir, data.toString())
                    py.getModule("features").callAttr("password", m, s, t, n).toString()
                }
            }
        })
    }

    private fun showStego() {
        setScreen(screen("Стеганография") {
            val cover = field("Маскировка (обычный текст)", lines = 3)
            val secret = field("Что спрятать", lines = 2)
            val out = label("")
            button("Спрятать") {
                val c = cover.text.toString()
                val s = secret.text.toString()
                busy(out, "Считаю") {
                    py.getModule("features").callAttr("stego_hide", c, s).toString()
                }
            }
            button("Достать") {
                val c = cover.text.toString()
                busy(out, "Читаю") {
                    py.getModule("features").callAttr("stego_reveal", c).toString()
                }
            }
            button("Копировать результат") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("folyan", out.text))
            }
        })
    }

    private fun showDownloader() {
        setScreen(screen("Качалка") {
            val url = field("Ссылка на видео")
            val path = field("Папка", downloadsDir)
            val res = spinner(DOWNLOAD_HEIGHTS.map { "${it}p" }, 4)
            val codec = spinner(DOWNLOAD_CODEC_NAMES, 0)
            val fmt = spinner(DOWNLOAD_FORMATS, 0)
            val ai = check("-sr форматы (-ai)")
            progressView = label("")
            val out = label("")
            button("Скачать") {
                val u = url.text.toString().trim()
                val h = DOWNLOAD_HEIGHTS[res.selectedItemPosition]
                val c = DOWNLOAD_CODECS[codec.selectedItemPosition]
                val f = DOWNLOAD_FORMATS[fmt.selectedItemPosition]
                val dir = path.text.toString().ifBlank { downloadsDir }
                val sr = ai.isChecked
                if (u.isEmpty()) {
                    out.text = "Вставь ссылку"
                    return@button
                }
                busy(out, "Скачиваю") {
                    py.getModule("dl").callAttr("download", u, h, c, sr, f, dir, ffmpeg).toString()
                }
            }
            button("Стоп") {
                py.getModule("dl").callAttr("cancel")
            }
        })
    }

    private fun pickFile(onPicked: (String) -> Unit) {
        pickHandler = { uri ->
            worker.execute {
                val path = try {
                    copyToCache(uri)
                } catch (e: Exception) {
                    null
                }
                main.post { if (path != null) onPicked(path) }
            }
        }
        picker.launch(arrayOf("*/*"))
    }

    private fun copyToCache(uri: Uri): String {
        val f = File(cacheDir, "pick_${System.currentTimeMillis()}")
        contentResolver.openInputStream(uri)!!.use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        return f.absolutePath
    }

    private fun showConverter() {
        setScreen(screen("Конвертер") {
            val mode = spinner(listOf("Конвертация", "Склейка видео+аудио"), 0)
            val fmt = spinner(CONVERT_FORMATS, 0)
            val dir = field("Папка", downloadsDir)
            val pick1 = label("Файл: не выбран")
            button("Выбрать файл") {
                pickFile { p ->
                    convSrc = p
                    pick1.text = "Файл выбран"
                }
            }
            val pick2 = label("Аудио: не выбран")
            button("Выбрать аудио") {
                pickFile { p ->
                    convSrc2 = p
                    pick2.text = "Аудио выбрано"
                }
            }
            progressView = label("")
            val out = label("")
            button("Запуск") {
                val merge = mode.selectedItemPosition == 1
                val f = fmt.selectedItem.toString()
                val d = dir.text.toString().ifBlank { downloadsDir }
                val v = convSrc
                val a = convSrc2
                if (v == null) {
                    out.text = "Выбери файл"
                    return@button
                }
                if (merge && a == null) {
                    out.text = "Выбери аудио"
                    return@button
                }
                if (merge && f !in MERGE_FORMATS) {
                    out.text = "Для склейки формат mp4 или mkv"
                    return@button
                }
                busy(out, if (merge) "Склеиваю" else "Конвертирую") {
                    if (merge) {
                        py.getModule("dl").callAttr("merge", v, a, d, f, ffmpeg).toString()
                    } else {
                        py.getModule("dl").callAttr("convert", v, f, d, ffmpeg).toString()
                    }
                }
            }
        })
    }
}
