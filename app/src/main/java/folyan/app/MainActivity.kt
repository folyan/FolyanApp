package folyan.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import com.chaquo.python.Python
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

private val DOWNLOAD_HEIGHTS = listOf(144, 240, 360, 480, 720, 1080)
private val DOWNLOAD_CODECS = listOf("vp", "avc1", "av01")
private val DOWNLOAD_CODEC_NAMES = listOf("VP9", "H.264", "AV1")
private val DOWNLOAD_FORMATS = listOf("mkv", "mp4", "wav", "mp3", "m4a", "opus")
private val CONVERT_FORMATS = listOf("mp4", "mkv", "mp3", "wav", "m4a", "opus", "ogg", "flac", "aac")
private val MERGE_FORMATS = listOf("mp4", "mkv")

// Иконки из JetBrains Mono Nerd Font (Mono)
private const val ICON_BACK = "\uF060"   // стрелка влево
private const val ICON_CLIP = "\uF0C6"   // скрепка
private const val ICON_SEND = "\uF04B"   // play / отправка

class MainActivity : ComponentActivity() {

    // Шрифт интерфейса: системный, без файла.
    // Свой шрифт: положи .ttf в res/font/ и замени на ResourcesCompat.getFont(this, R.font.имя)
        private val uiFont: Typeface by lazy {
            ResourcesCompat.getFont(this, R.font.ui_font) ?: Typeface.DEFAULT
        }

    // JetBrains Mono Nerd Font: только для интерпретатора
    private val appFont by lazy {
        ResourcesCompat.getFont(this, R.font.jetbrains_nerd_mono)
    }

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
            if (interpActive) drainInterp()
            main.postDelayed(this, 300)
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

    private fun applyFontTo(v: View, font: Typeface) {
        if (v is TextView) v.typeface = font
        if (v is ViewGroup) for (i in 0 until v.childCount) applyFontTo(v.getChildAt(i), font)
    }

    private fun applyFont(v: View) {
        if (v is TextView) v.typeface = uiFont
        if (v is ViewGroup) for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setScreen(view: View) {
        applyFont(view)
        content.removeAllViews()
        content.addView(view)
        atMenu = false
        interpActive = false
    }

    private fun screen(title: String, menu: Boolean = false, build: LinearLayout.() -> Unit): View {
        progressView = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Увеличиваем верхний отступ, чтобы кнопки были ниже
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }
        if (!menu) box.addView(arrowBack { showMenu() })

        if (menu) {
            box.addCoin()
        } else {
            box.label(title).apply {
                textSize = 20f
                typeface = uiFont
            }
        }

        box.build()
        return ScrollView(this).apply { addView(box) }
    }

    // Монетка: смена цифры происходит СТРОГО в момент прохождения ребра (scaleX близко к 0)
    private fun LinearLayout.addCoin() {
        val size = dp(150)
        val card = MaterialCardView(this@MainActivity).apply {
            radius = (size / 2).toFloat()
            strokeWidth = dp(2)
            strokeColor = Color.parseColor("#4A5568")
            cardElevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(64), 0, dp(40))
            }
        }
        val text = TextView(this@MainActivity).apply {
            this.text = "0"
            textSize = 60f
            typeface = uiFont
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        card.addView(text)
        addView(card)

        var currentSide = 0
        var isTossing = false

        card.setOnClickListener {
            if (isTossing) return@setOnClickListener
            isTossing = true

            val targetSide = Random.nextInt(2) // 0 или 1
            val tossAnimator = ValueAnimator.ofFloat(0f, 8f).apply {
                duration = 900
                interpolator = DecelerateInterpolator()
                var lastCycle = -1
                var switchedInCurrentCycle = false

                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    val currentCycle = progress.toInt()
                    val cycleProgress = progress - currentCycle
                    val s = kotlin.math.abs(kotlin.math.cos(cycleProgress * Math.PI)).toFloat()
                    card.scaleX = s

                    if (currentCycle != lastCycle) {
                        lastCycle = currentCycle
                        switchedInCurrentCycle = false
                    }

                    // Меняем цифру строго в мертвой точке (когда монетка ребром s < 0.15)
                    if (s < 0.15f && !switchedInCurrentCycle) {
                        // На финальном цикле принудительно ставим целевую сторону
                        currentSide = if (currentCycle >= 7) targetSide else if (currentSide == 0) 1 else 0
                        text.text = currentSide.toString()
                        switchedInCurrentCycle = true
                    }
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        card.scaleX = 1f
                        currentSide = targetSide
                        text.text = currentSide.toString()
                        isTossing = false
                    }
                })
            }
            tossAnimator.start()
        }
    }

    private fun LinearLayout.label(text: String): TextView {
        val v = TextView(this@MainActivity).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
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
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2), 0, dp(8)) }
        }
        addView(e)
        return e
    }

    private fun LinearLayout.button(text: String, onClick: () -> Unit): Button {
        val b = MaterialButton(this@MainActivity).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = dp(23)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { 
                setMargins(dp(16), dp(4), dp(16), dp(8)) 
            }
            setOnClickListener { onClick() }
        }
        addView(b)
        return b
    }

    private fun LinearLayout.check(text: String, on: Boolean = false): CheckBox {
        val c = CheckBox(this@MainActivity).apply {
            this.text = text
            isChecked = on
            setPadding(dp(6), dp(6), dp(6), dp(6))
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
            setPadding(0, dp(6), 0, dp(6))
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
            addView(View(this@MainActivity).apply { minimumHeight = dp(96) })
            button("Интерпретатор") { showInterpreter() }
            button("Менеджер паролей") { showPasswords() }
            button("Стеганография") { showStego() }
            button("Качалка") { showDownloader() }
            button("Конвертер") { showConverter() }
            button("Выход") { finish() }
        })
        atMenu = true
    }

    // ---------- интерпретатор (чат, как в core.py) ----------

    private var interpActive = false
    private var interpChat: LinearLayout? = null
    private var interpScroll: ScrollView? = null
    private var interpFileLabel: TextView? = null
    private var interpCode: String? = null
    private var interpName = ""

    // Кнопка-иконка: без внутренних отступов Material, чтобы глиф не обрезался
    private fun iconButton(glyph: String, size: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = glyph
            textSize = 20f
            cornerRadius = dp(size / 2)
            insetTop = 0
            insetBottom = 0
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    private fun arrowBack(onClick: () -> Unit): MaterialButton =
        iconButton(ICON_BACK, 46, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(46))
        }

    private fun materialBtn(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        cornerRadius = dp(23)
        setOnClickListener { onClick() }
    }

    private fun showInterpreter() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(arrowBack { showMenu() })

        // история чата сохраняется при выходе и возврате на экран
        val chat = interpChat ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }.also { interpChat = it }
        (chat.parent as? ViewGroup)?.removeView(chat)
        val scroll = ScrollView(this).apply { addView(chat) }
        interpScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val fileLabel = TextView(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { clearInterpFile() }
        }
        interpFileLabel = fileLabel
        root.addView(fileLabel)

        val input = TextInputEditText(this).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInterp(input)
                true
            } else false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconButton(ICON_CLIP, 56) { pickInterpFile() },
            LinearLayout.LayoutParams(dp(56), dp(56)))
        input.apply {
            // одна высота с кнопками: TextInputLayout добавлял место снизу и сдвигал поле вверх
            minHeight = dp(56)
            setPadding(dp(16), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#E6E1E5"))
            setHintTextColor(Color.parseColor("#8A8F98"))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#2A2A33"))
                setStroke(dp(2), Color.parseColor("#4A5568"))
            }
        }
        row.addView(input,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        row.addView(iconButton(ICON_SEND, 56) { sendInterp(input) }.apply {
            // зажатие ▶ останавливает выполнение кода
            setOnLongClickListener {
                py.getModule("features").callAttr("interp_stop")
                true
            }
        },
            LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(row)

        setScreen(root)
        applyFontTo(root, appFont)
        interpActive = true
        refreshInterpFile()
    }

    private fun bubble(text: String, right: Boolean) {
        val chat = interpChat ?: return
        val tv = TextView(this).apply {
            this.text = text
            typeface = appFont
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnLongClickListener {
                copyToClipboard(text)
                true
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (right) Color.parseColor("#3B2F5C") else Color.parseColor("#2A2A33"))
            }
        }
        chat.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (right) Gravity.END else Gravity.START
            setMargins(0, dp(4), 0, dp(4))
        })
        interpScroll?.post { interpScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // Забираем вывод и запросы input() из Python
    private fun drainInterp() {
        val msgs = py.getModule("features").callAttr("interp_drain").asList()
        for (m in msgs) {
            bubble(m.asList()[0].toString(), right = false)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("folyan", text))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    // Возвращаем фокус и клавиатуру в поле, чтобы можно было писать сразу
    private fun keepFocus(input: EditText) {
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendInterp(input: EditText) {
        val text = input.text.toString().trim()
        val features = py.getModule("features")
        val code = interpCode

        if (features.callAttr("interp_is_running").toBoolean()) {
            if (text.isNotEmpty()) {
                bubble(text, right = true)
                features.callAttr("interp_answer", text)
                input.setText("")
            }
        } else if (code != null) {
            interpChat?.removeAllViews()
            bubble(interpName, right = true)
            if (text.isNotEmpty()) bubble(text, right = true)
            input.setText("")
            features.callAttr("interp_start", code, true, text.ifEmpty { null })
        } else if (text.isNotEmpty()) {
            bubble(text, right = true)
            input.setText("")
            features.callAttr("interp_start", text, false, null)
        }
        keepFocus(input)
    }

    private fun pickInterpFile() {
        pickHandler = { uri ->
            worker.execute {
                val name = displayName(uri)
                val text = try {
                    contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (text != null) {
                        interpName = name
                        interpCode = text
                        refreshInterpFile()
                    } else {
                        bubble("Не удалось открыть файл", right = false)
                    }
                }
            }
        }
        picker.launch(arrayOf("*/*"))
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "файл.py"

    private fun clearInterpFile() {
        interpCode = null
        interpName = ""
        py.getModule("features").callAttr("interp_reset")
        refreshInterpFile()
    }

    private fun refreshInterpFile() {
        val label = interpFileLabel ?: return
        label.visibility = if (interpCode != null) View.VISIBLE else View.GONE
        label.text = interpName
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
            val out = TextView(this@MainActivity).apply {
                setTextIsSelectable(true)
                setPadding(0, dp(16), 0, dp(16))
            }
            addView(View(this@MainActivity).apply { minimumHeight = dp(96) })
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
            addView(out)
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
