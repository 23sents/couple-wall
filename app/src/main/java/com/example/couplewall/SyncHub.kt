package com.example.couplewall

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.couplewall.core.Message
import com.example.couplewall.core.PairCode
import com.example.couplewall.core.Protocol
import com.example.couplewall.core.Stroke
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Process-wide coordinator: owns the shared drawing, the relay connection and the lock-screen updates.
 * The UI and the optional foreground service just "acquire" it; network threads run while anyone holds it.
 */
object SyncHub {
    enum class Status { UNPAIRED, CONNECTING, ONLINE, OFFLINE }

    interface Listener {
        fun onCanvasChanged()
        fun onStatusChanged(status: Status)
    }

    /** Public relay; messages are end-to-end encrypted. Change to your own ntfy server if you like. */
    private const val BASE_URL = "https://ntfy.sh"
    private const val MIN_WALLPAPER_GAP_MS = 8_000L

    private var appContext: Context? = null
    lateinit var prefs: Prefs
        private set
    lateinit var store: CanvasStore
        private set

    private val relay = RelayClient(BASE_URL)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val holders = HashSet<String>()
    private val control = ConcurrentLinkedQueue<Message>()
    private val main = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var wallpaperTask: ScheduledFuture<*>? = null
    private var saveTask: ScheduledFuture<*>? = null
    private var lastWallpaperAt = 0L
    private var running = false

    @Volatile private var generation = 0

    @Volatile var status = Status.UNPAIRED
        private set

    @Synchronized fun init(context: Context) {
        if (appContext != null) return
        val c = context.applicationContext
        appContext = c
        prefs = Prefs(c)
        store = CanvasStore(File(c.filesDir, "canvas.bin")).also { it.load() }
        status = if (prefs.pairCode == null) Status.UNPAIRED else Status.OFFLINE
    }

    val isPaired: Boolean get() = prefs.pairCode != null

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    @Synchronized fun acquire(tag: String) {
        holders.add(tag)
        startIfNeeded()
    }

    @Synchronized fun release(tag: String) {
        holders.remove(tag)
        if (holders.isEmpty()) stopThreads()
    }

    // ------------------------------------------------------------------ pairing
    @Synchronized fun pair(code: String) {
        stopThreads()
        prefs.pairCode = code
        prefs.lastMessageId = null
        store.reset()
        control.clear()
        scheduleSave()
        notifyCanvas()
        setStatus(Status.OFFLINE)
        startIfNeeded()
    }

    @Synchronized fun unpair() {
        stopThreads()
        prefs.pairCode = null
        prefs.lastMessageId = null
        prefs.autoLock = false
        control.clear()
        setStatus(Status.UNPAIRED)
    }

    // ------------------------------------------------------------------ local edits
    fun addLocal(stroke: Stroke) {
        store.addLocal(stroke)
        scheduleSave()
        notifyCanvas()
        scheduleWallpaper(5_000)
    }

    fun undo(): Boolean {
        val s = store.ownLast(prefs.deviceId) ?: return false
        store.remove(listOf(s.id))
        control.add(Message.Remove(listOf(s.id)))
        scheduleSave()
        notifyCanvas()
        scheduleWallpaper(5_000)
        return true
    }

    fun clearAll() {
        val t = max(System.currentTimeMillis(), store.latestTime())
        store.clear(t)
        control.add(Message.Clear(t, prefs.deviceId))
        scheduleSave()
        notifyCanvas()
        scheduleWallpaper(2_000)
    }

    /** Re-render the lock screen soon (e.g. when the app goes to the background). */
    fun flushWallpaper() = scheduleWallpaper(0)

    // ------------------------------------------------------------------ threads
    private fun startIfNeeded() {
        if (running || holders.isEmpty() || prefs.pairCode == null) return
        running = true
        val gen = ++generation
        Thread({ subscribeLoop(gen) }, "hub-subscribe").start()
        Thread({ publishLoop(gen) }, "hub-publish").start()
    }

    private fun stopThreads() {
        running = false
        generation++
        relay.abort()
        setStatus(if (prefs.pairCode == null) Status.UNPAIRED else Status.OFFLINE)
    }

    private fun subscribeLoop(gen: Int) {
        var backoff = 2_000L
        while (gen == generation) {
            val code = prefs.pairCode ?: return
            val topic = PairCode.topic(code)
            val key = PairCode.key(code)
            setStatus(Status.CONNECTING)
            try {
                relay.subscribe(
                    topic, prefs.lastMessageId ?: "all",
                    active = { gen == generation },
                    onOpen = { if (gen == generation) { setStatus(Status.ONLINE); backoff = 2_000L } },
                    onMessage = { id, text -> if (gen == generation) handleIncoming(id, text, key) },
                )
            } catch (e: Exception) {
                // dropped connection: fall through to the retry below
            }
            if (gen != generation) return
            setStatus(Status.OFFLINE)
            if (!pause(backoff)) return
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    private fun publishLoop(gen: Int) {
        while (gen == generation) {
            val code = prefs.pairCode ?: return
            val topic = PairCode.topic(code)
            val key = PairCode.key(code)
            var ok = true
            while (ok) {
                val msg = control.peek() ?: break
                ok = relay.publish(topic, Protocol.seal(msg, key))
                if (ok) control.poll()
            }
            if (ok) {
                for (packed in Protocol.pack(store.unsent(), key)) {
                    if (gen != generation) return
                    if (relay.publish(topic, packed.text)) {
                        store.markSent(packed.strokes.map { it.id })
                    } else {
                        ok = false
                        break
                    }
                }
            }
            if (!pause(if (ok) 1_200L else 6_000L)) return
        }
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun handleIncoming(id: String, text: String, key: ByteArray) {
        if (id.isNotEmpty()) prefs.lastMessageId = id
        val changed = when (val msg = Protocol.open(text, key)) {
            is Message.Strokes -> store.addRemote(msg.strokes)
            is Message.Clear -> store.clear(msg.time)
            is Message.Remove -> store.remove(msg.ids)
            null -> false
        }
        if (changed) {
            scheduleSave()
            notifyCanvas()
            scheduleWallpaper(5_000)
        }
    }

    // ------------------------------------------------------------------ side effects
    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        main.post { for (l in listeners) l.onStatusChanged(s) }
    }

    private fun notifyCanvas() {
        main.post { for (l in listeners) l.onCanvasChanged() }
    }

    @Synchronized private fun scheduleSave() {
        saveTask?.cancel(false)
        saveTask = scheduler.schedule({ store.save() }, 1, TimeUnit.SECONDS)
    }

    @Synchronized private fun scheduleWallpaper(delayMs: Long) {
        if (!prefs.autoLock || appContext == null) return
        wallpaperTask?.cancel(false)
        val gap = MIN_WALLPAPER_GAP_MS - (System.currentTimeMillis() - lastWallpaperAt)
        val wait = max(delayMs, gap).coerceAtLeast(0L)
        wallpaperTask = scheduler.schedule({ applyLockNow() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun applyLockNow() {
        val c = appContext ?: return
        lastWallpaperAt = System.currentTimeMillis()
        try {
            LockRenderer.applyLock(c, prefs.lockBackground, store.snapshot())
        } catch (e: Exception) {
            // wallpaper service busy or permission problem: the next change retries
        }
    }
}
