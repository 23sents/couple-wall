package com.example.couplewall

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.couplewall.core.PairCode
import kotlin.math.min

/** Three tabs: wallpapers, shared drawing, pairing. Everything is built in code (no XML layouts). */
class MainActivity : Activity(), SyncHub.Listener {
    private val io = Work.io
    private val thumbs = LruCache<String, Bitmap>(30)
    private val tiles = TileAdapter()

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var pages: Array<View>
    private lateinit var tabViews: Array<TextView>
    private lateinit var drawView: DrawView
    private lateinit var pairColumn: LinearLayout
    private lateinit var colorDots: List<ColorDot>
    private lateinit var brushView: BrushView
    private lateinit var homeBtn: ImageView
    private lateinit var lockBtn: ImageView
    private var chip: TextView? = null
    private var overlay: View? = null
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncHub.init(this)
        window.statusBarColor = Color.rgb(8, 12, 28)
        window.navigationBarColor = Color.rgb(28, 14, 52)

        root = FrameLayout(this)
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(
            buildTabBar(),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14f), dp(6f), dp(14f), dp(12f)) }
        )
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        pages = arrayOf(buildWallpapersPage(), buildDrawPage(), buildPairPage())
        showTab(0)
    }

    override fun onStart() {
        super.onStart()
        SyncHub.addListener(this)
        SyncHub.acquire("ui")
        drawView.refresh()
        updateChip()
    }

    override fun onStop() {
        SyncHub.removeListener(this)
        SyncHub.release("ui")
        SyncHub.flushWallpaper()
        super.onStop()
    }

    override fun onBackPressed() {
        if (overlay != null) closePreview() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ SyncHub.Listener
    override fun onCanvasChanged() = drawView.refresh()

    override fun onStatusChanged(status: SyncHub.Status) {
        updateChip()
        if (currentTab == 2) refreshPair()
    }

    // ------------------------------------------------------------------ tabs
    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.drawable(this@MainActivity, 28f, 0x33FFFFFF)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        val labels = listOf("Обои", "Рисунок", "Пара")
        val icons = intArrayOf(R.drawable.ic_wallpaper, R.drawable.ic_brush, R.drawable.ic_heart)
        tabViews = Array(3) { i ->
            TextView(this).apply {
                text = labels[i]
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                compoundDrawablePadding = dp(2f)
                setCompoundDrawablesWithIntrinsicBounds(0, icons[i], 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(0, dp(6f), 0, dp(6f))
                setOnClickListener { showTab(i) }
            }
        }
        for (t in tabViews) bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun showTab(i: Int) {
        currentTab = i
        content.removeAllViews()
        content.addView(pages[i], FrameLayout.LayoutParams(MATCH, MATCH))
        for ((k, t) in tabViews.withIndex()) {
            t.background = if (k == i) Glass.drawable(this, 22f, 0x40FFFFFF) else null
        }
        if (i != 2) setSecure(false)
        if (i == 1) {
            drawView.reloadBackground()
            drawView.refresh()
            refreshToggles()
        }
        if (i == 2) refreshPair()
        updateChip()
    }

    // ------------------------------------------------------------------ wallpapers tab
    private fun buildWallpapersPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(18f), dp(16f), 0)
        }
        col.addView(label("Обои", 30f, bold = true), lp())
        col.addView(label("Выберите обои и поставьте на главный экран или экран блокировки", 14f, 170), lp(2))
        tiles.refresh()
        val grid = GridView(this).apply {
            numColumns = 2
            horizontalSpacing = dp(12f)
            verticalSpacing = dp(12f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(16f), 0, dp(8f))
            clipToPadding = false
            adapter = tiles
            setOnItemClickListener { _, _, pos, _ -> onTile(tiles.entries[pos]) }
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun onTile(id: String) {
        if (id == ADD) pickPhoto() else showPreview(id)
    }

    private inner class TileAdapter : BaseAdapter() {
        var entries: List<String> = emptyList()

        fun refresh() {
            val custom = if (LockRenderer.customFile(this@MainActivity).exists()) listOf("custom") else emptyList()
            entries = listOf(ADD) + custom + Wallpapers.items.map { it.id }
            notifyDataSetChanged()
        }

        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val id = entries[position]
            val grid = parent as GridView
            val colW = if (grid.columnWidth > 0) grid.columnWidth else (resources.displayMetrics.widthPixels - dp(44f)) / 2
            val (sw, sh) = LockRenderer.screenSize(this@MainActivity)
            val tile = (convertView as? FrameLayout) ?: makeTile()
            tile.layoutParams = AbsListView.LayoutParams(colW, (colW * sh.toFloat() / sw).toInt())
            val iv = tile.getChildAt(0) as ImageView
            val tv = tile.getChildAt(1) as TextView
            iv.tag = id
            if (id == ADD) {
                iv.setImageDrawable(null)
                iv.background = Glass.drawable(this@MainActivity, 20f)
                tv.text = "Своё фото"
                tv.gravity = Gravity.CENTER
                tv.textSize = 16f
                tv.compoundDrawablePadding = dp(6f)
                tv.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_add, 0, 0)
                tv.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                tv.setCompoundDrawables(null, null, null, null)
                iv.background = null
                iv.setImageDrawable(null)
                tv.text = Wallpapers.nameOf(id)
                tv.gravity = Gravity.BOTTOM or Gravity.START
                tv.textSize = 14f
                loadThumb(id, iv)
            }
            return tile
        }
    }

    private fun makeTile(): FrameLayout {
        val f = FrameLayout(this)
        f.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(18f).toFloat())
            }
        }
        f.clipToOutline = true
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        f.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))
        return f
    }

    private fun loadThumb(id: String, iv: ImageView) {
        thumbs.get(id)?.let { iv.setImageBitmap(it); return }
        io.execute {
            val (sw, sh) = LockRenderer.screenSize(this)
            val w = 270
            val bmp = LockRenderer.background(this, id, w, (w * sh.toFloat() / sw).toInt())
            thumbs.put(id, bmp)
            runOnUiThread { if (iv.tag == id) iv.setImageBitmap(bmp) }
        }
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("Не найдено приложение для выбора фото")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Загружаю фото…")
        io.execute {
            val ok = try { LockRenderer.saveCustom(this, uri) } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) {
                    thumbs.remove("custom")
                    tiles.refresh()
                    showPreview("custom")
                } else {
                    toast("Не удалось открыть фото")
                }
            }
        }
    }

    // ------------------------------------------------------------------ preview + apply
    private fun showPreview(id: String) {
        closePreview()
        val ov = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(238, 8, 10, 22))
            isClickable = true
        }
        val (sw, sh) = LockRenderer.screenSize(this)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        ov.addView(
            iv,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(48f), dp(60f), dp(48f), dp(190f)) }
        )
        ov.addView(
            label(Wallpapers.nameOf(id), 22f, bold = true),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(22f), dp(22f), 0, 0) }
        )
        ov.addView(
            iconButton(R.drawable.ic_close) { closePreview() },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16f), dp(16f), 0) }
        )
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = dp(8f)
        val names = listOf("Главный" to (true to false), "Блокировка" to (false to true), "Оба" to (true to true))
        for ((k, entry) in names.withIndex()) {
            val (text, target) = entry
            row.addView(
                Glass.button(this, text, 0x3AFFFFFF, 0x66FFFFFF) {
                    applyWallpaper(id, target.first, target.second)
                    closePreview()
                }.apply { textSize = 15f; setPadding(dp(8f), dp(14f), dp(8f), dp(14f)) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (k > 0) leftMargin = gap }
            )
        }
        buttons.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        val hint = when {
            id.startsWith("pair_") -> "Парные обои: себе — одну половинку, партнёру — вторую. Рядом они складываются в одну картинку"
            SyncHub.isPaired -> "Поверх обоев будет ваш общий рисунок"
            else -> "Создайте пару на вкладке «Пара», чтобы рисовать вдвоём"
        }
        buttons.addView(label(hint, 12f, 150).apply { gravity = Gravity.CENTER }, lp(10))
        ov.addView(
            buttons,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(18f), 0, dp(18f), dp(26f)) }
        )
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov

        io.execute {
            val w = 540
            val bmp = LockRenderer.background(this, id, w, (w * sh.toFloat() / sw).toInt())
            runOnUiThread { iv.setImageBitmap(bmp) }
        }
    }

    private fun closePreview() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    private fun applyWallpaper(id: String, home: Boolean, lock: Boolean) {
        toast("Ставлю обои…")
        io.execute {
            try {
                val (w, h) = LockRenderer.screenSize(this)
                if (home) {
                    SyncHub.prefs.homeBackground = id
                    SyncHub.prefs.autoHome = true
                    val bmp = LockRenderer.render(this, id, SyncHub.store.snapshot(), w, h)
                    LockRenderer.setWallpaper(this, bmp, WallpaperManager.FLAG_SYSTEM)
                    bmp.recycle()
                }
                if (lock) {
                    SyncHub.prefs.lockBackground = id
                    SyncHub.prefs.autoLock = true
                    val bmp = LockRenderer.render(this, id, SyncHub.store.snapshot(), w, h)
                    LockRenderer.setWallpaper(this, bmp, WallpaperManager.FLAG_LOCK)
                    bmp.recycle()
                }
                runOnUiThread {
                    toast("Готово")
                    drawView.reloadBackground()
                    if (currentTab == 2) refreshPair()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось поставить обои") }
            }
        }
    }

    // ------------------------------------------------------------------ drawing tab
    private fun buildDrawPage(): View {
        val prefs = SyncHub.prefs
        val f = FrameLayout(this)
        drawView = DrawView(this).also {
            it.color = COLORS[prefs.colorIndex.coerceIn(0, COLORS.size - 1)]
            it.brushIndex = prefs.brushIndex
        }
        f.addView(drawView, FrameLayout.LayoutParams(MATCH, MATCH))

        val c = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            background = Glass.drawable(this@MainActivity, 16f, 0x66101028)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { showTab(2) }
        }
        chip = c
        f.addView(
            c,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(20f), dp(20f), 0, 0) }
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.drawable(this@MainActivity, 26f, 0x77101028)
            setPadding(dp(10f), dp(8f), dp(10f), dp(6f))
        }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorDots = COLORS.mapIndexed { i, col ->
            ColorDot(this, col).also { dot ->
                dot.chosen = i == prefs.colorIndex
                dot.setOnClickListener {
                    prefs.colorIndex = i
                    drawView.color = col
                    colorDots.forEachIndexed { k, d -> d.chosen = k == i }
                }
                colorRow.addView(dot, LinearLayout.LayoutParams(0, dp(36f), 1f))
            }
        }
        bar.addView(colorRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        brushView = BrushView(this).also {
            it.index = prefs.brushIndex
            it.setOnClickListener {
                val next = (prefs.brushIndex + 1) % DrawView.BRUSHES.size
                prefs.brushIndex = next
                drawView.brushIndex = next
                brushView.index = next
            }
        }
        actions.addView(brushView, LinearLayout.LayoutParams(0, dp(44f), 1f))
        actions.addView(actionIcon(R.drawable.ic_undo) { if (!SyncHub.undo()) toast("Нечего отменять") }, LinearLayout.LayoutParams(0, dp(44f), 1f))
        homeBtn = actionIcon(R.drawable.ic_home) { toggleHome() }
        lockBtn = actionIcon(R.drawable.ic_lock) { toggleLock() }
        actions.addView(homeBtn, LinearLayout.LayoutParams(0, dp(44f), 1f))
        actions.addView(lockBtn, LinearLayout.LayoutParams(0, dp(44f), 1f))
        actions.addView(actionIcon(R.drawable.ic_delete) { confirmClear() }, LinearLayout.LayoutParams(0, dp(44f), 1f))
        refreshToggles()
        bar.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        f.addView(
            bar,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(14f), 0, dp(14f), dp(12f)) }
        )
        return f
    }

    private fun actionIcon(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        background = Glass.drawable(this@MainActivity, 24f, 0x24FFFFFF)
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        setOnClickListener { onClick() }
    }

    private fun refreshToggles() {
        if (!::homeBtn.isInitialized) return
        homeBtn.alpha = if (SyncHub.prefs.autoHome) 1f else 0.4f
        lockBtn.alpha = if (SyncHub.prefs.autoLock) 1f else 0.4f
    }

    private fun toggleHome() {
        val on = !SyncHub.prefs.autoHome
        SyncHub.prefs.autoHome = on
        if (on) SyncHub.flushWallpaper()
        refreshToggles()
        toast(if (on) "Рисунок ставится на главный экран" else "Главный экран больше не обновляется")
    }

    private fun toggleLock() {
        val on = !SyncHub.prefs.autoLock
        SyncHub.prefs.autoLock = on
        if (on) SyncHub.flushWallpaper()
        refreshToggles()
        toast(if (on) "Рисунок ставится на экран блокировки" else "Экран блокировки больше не обновляется")
    }

    private fun setSecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(10f), dp(10f))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить рисунок?")
            .setMessage("Рисунок исчезнет у вас обоих.")
            .setPositiveButton("Очистить") { _, _ -> SyncHub.clearAll() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateChip() {
        val c = chip ?: return
        val (text, color) = when (SyncHub.status) {
            SyncHub.Status.UNPAIRED -> "Пара не создана — нажмите" to Color.rgb(255, 159, 10)
            SyncHub.Status.CONNECTING -> "подключение…" to Color.rgb(255, 214, 10)
            SyncHub.Status.ONLINE -> "на связи" to Color.rgb(48, 209, 88)
            SyncHub.Status.OFFLINE -> "нет связи" to Color.rgb(142, 142, 147)
        }
        c.text = text
        c.compoundDrawablePadding = dp(8f)
        c.setCompoundDrawablesWithIntrinsicBounds(dot(color), null, null, null)
    }

    // ------------------------------------------------------------------ pairing tab
    private fun buildPairPage(): View {
        val sv = ScrollView(this).apply { isFillViewport = true }
        pairColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        sv.addView(pairColumn)
        return sv
    }

    private fun refreshPair() {
        val col = pairColumn
        col.removeAllViews()
        col.addView(label("Пара", 30f, bold = true), lp())
        val code = SyncHub.prefs.pairCode
        setSecure(code != null)
        if (code == null) {
            col.addView(
                label("Создайте пару и отправьте код любимому человеку — или введите его код. Рисунок зашифрован: видите его только вы двое.", 14f, 180),
                lp(4)
            )
            col.addView(
                Glass.button(this, "Создать пару", 0x553A7BFF, 0x883A7BFF.toInt()) {
                    SyncHub.pair(PairCode.generate())
                    refreshPair()
                },
                lp(24)
            )
            col.addView(label("или введите код", 13f, 150).apply { gravity = Gravity.CENTER }, lp(24))
            val input = EditText(this).apply {
                hint = "XXXXX-XXXXX-XXXXX-XXXXX"
                setHintTextColor(Color.argb(110, 255, 255, 255))
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = Glass.drawable(this@MainActivity, 22f)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            col.addView(input, lp(8))
            col.addView(Glass.button(this, "Подключиться") { connectWith(input.text.toString()) }, lp(10))
            col.addView(
                Glass.button(this, "Вставить из буфера") { clipboardText()?.let { input.setText(it) } },
                lp(10)
            )
            addServerCard(col)
            return
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.drawable(this@MainActivity, 26f)
            setPadding(dp(20f), dp(18f), dp(20f), dp(20f))
        }
        card.addView(label("КОД ПАРЫ", 12f, 150).apply { letterSpacing = 0.15f }, lp())
        card.addView(label(PairCode.format(code), 20f, bold = true).apply { setTextIsSelectable(true) }, lp(6))
        val (statusText, statusColor) = when (SyncHub.status) {
            SyncHub.Status.ONLINE -> "на связи" to Color.rgb(48, 209, 88)
            SyncHub.Status.CONNECTING -> "подключение…" to Color.rgb(255, 214, 10)
            else -> "нет связи" to Color.rgb(142, 142, 147)
        }
        card.addView(
            label(statusText, 14f, 200).apply {
                compoundDrawablePadding = dp(8f)
                setCompoundDrawablesWithIntrinsicBounds(dot(statusColor), null, null, null)
                gravity = Gravity.CENTER_VERTICAL
            },
            lp(10)
        )
        pairColumn.addView(card, lp(16))

        col.addView(Glass.button(this, "Поделиться кодом") { shareCode(code) }, lp(14))
        col.addView(Glass.button(this, "Скопировать") {
            clipboard().setPrimaryClip(ClipData.newPlainText("code", PairCode.format(code)))
            toast("Скопировано")
        }, lp(10))

        col.addView(
            switchRow("Рисунок на главном экране", SyncHub.prefs.autoHome) { on ->
                SyncHub.prefs.autoHome = on
                if (on) SyncHub.flushWallpaper()
            },
            lp(18)
        )
        col.addView(
            switchRow("Рисунок на экране блокировки", SyncHub.prefs.autoLock) { on ->
                SyncHub.prefs.autoLock = on
                if (on) SyncHub.flushWallpaper()
            },
            lp(10)
        )
        col.addView(
            switchRow("Обновлять, даже когда приложение закрыто", SyncHub.prefs.liveService) { on -> setLive(on) },
            lp(10)
        )
        col.addView(
            label("Фоновая синхронизация держит соединение и немного расходует батарею; в шторке будет значок.", 12f, 140),
            lp(6)
        )
        addServerCard(col)
        col.addView(
            Glass.button(this, "Разорвать пару", 0x33FF453A, 0x66FF453A) { confirmUnpair() },
            lp(24)
        )
    }

    /** Server address and key (normally baked into the build); lets you point the app at your own Railway relay. */
    private fun addServerCard(col: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.drawable(this@MainActivity, 22f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        card.addView(label("СЕРВЕР", 12f, 150).apply { letterSpacing = 0.15f }, lp())
        val current = SyncHub.prefs.effectiveRelayUrl
        card.addView(
            label(if (current.isBlank()) "не задан" else current.removePrefix("https://"), 14f, 200),
            lp(6)
        )
        val urlIn = textField("https://имя.up.railway.app", SyncHub.prefs.relayUrl.orEmpty(), false)
        val keyIn = textField("Ключ сервера (APP_KEY)", "", true)
        card.addView(urlIn, lp(10))
        card.addView(keyIn, lp(8))
        card.addView(
            Glass.button(this, "Сохранить сервер") {
                val url = urlIn.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("https://", ignoreCase = true)) {
                    toast("Адрес должен начинаться с https://")
                } else {
                    SyncHub.setServer(url, keyIn.text.toString())
                    toast(if (url.isEmpty()) "Сервер по умолчанию" else "Сервер сохранён")
                    refreshPair()
                }
            }.apply { textSize = 15f },
            lp(10)
        )
        col.addView(card, lp(18))
    }

    private fun textField(hintText: String, value: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        setText(value)
        setHintTextColor(Color.argb(110, 255, 255, 255))
        setTextColor(Color.WHITE)
        textSize = 15f
        isSingleLine = true
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        background = Glass.drawable(this@MainActivity, 18f)
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
    }

    private fun connectWith(text: String) {
        val code = PairCode.normalize(text)
        if (code == null) {
            toast("Неверный код")
            return
        }
        SyncHub.pair(code)
        toast("Пара подключена")
        showTab(1)
    }

    private fun shareCode(code: String) {
        val text = "Давай рисовать друг у друга на экранах! Код пары: ${PairCode.format(code)}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Разорвать пару?")
            .setMessage("Общий рисунок на этом телефоне будет сброшен.")
            .setPositiveButton("Разорвать") { _, _ ->
                SyncHub.prefs.liveService = false
                stopService(Intent(this, SyncService::class.java))
                SyncHub.store.reset()
                SyncHub.unpair()
                drawView.refresh()
                refreshPair()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setLive(on: Boolean) {
        SyncHub.prefs.liveService = on
        val i = Intent(this, SyncService::class.java)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            stopService(i)
        }
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = checked
        background = Glass.drawable(this@MainActivity, 22f)
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    // ------------------------------------------------------------------ helpers
    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardText(): String? =
        clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun label(text: String, sp: Float, alpha: Int = 255, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.argb(alpha, 255, 255, 255))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top.toFloat()) }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ADD = "+"
        const val REQ_PICK = 11
        const val REQ_NOTIF = 12
        val COLORS = intArrayOf(
            Color.rgb(255, 255, 255), Color.rgb(255, 105, 160), Color.rgb(255, 69, 58), Color.rgb(255, 159, 10),
            Color.rgb(255, 214, 10), Color.rgb(48, 209, 88), Color.rgb(100, 210, 255), Color.rgb(191, 90, 242),
        )
    }
}

/** Round colour swatch with a ring when chosen. */
private class ColorDot(ctx: Context, private val color: Int) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    var chosen = false
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val r = min(width, height) / 2f - 5f * d
        fill.color = color
        c.drawCircle(width / 2f, height / 2f, r, fill)
        if (chosen) {
            ring.strokeWidth = 2f * d
            c.drawCircle(width / 2f, height / 2f, r + 3.5f * d, ring)
        }
    }
}

/** Shows the current brush size as a dot; tap to cycle. */
private class BrushView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var index = 1
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        c.drawCircle(width / 2f, height / 2f, (3f + 3.5f * index) * d, paint)
    }
}
