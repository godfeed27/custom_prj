package dev.walkdac.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import dev.walkdac.core.FrameHeader
import dev.walkdac.core.StreamStats
import dev.walkdac.core.TimeSync
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service: finds the Mac, keeps the audio and control connections alive,
 * plays through [AudioEngine], owns the MediaSession (hardware buttons) and the notification.
 */
class WalkDacService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): WalkDacService = this@WalkDacService
    }

    private val binder = LocalBinder()
    /** All outgoing control writes happen here: callers are often on the main thread, where sockets are forbidden. */
    private val netExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "walkdac-control-tx").apply { isDaemon = true } }
    lateinit var prefs: Prefs
        private set
    val discovery = Discovery()

    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var running = false
    private var managerThread: Thread? = null
    private val linkDown = AtomicBoolean(false)

    @Volatile var status: String = "Dừng"
        private set
    @Volatile var connectedHost: String? = null
        private set
    @Volatile var reconnects: Int = 0
        private set
    @Volatile private var audioClient: AudioClient? = null
    @Volatile private var controlClient: ControlClient? = null
    @Volatile private var engine: AudioEngine? = null
    val stats = StreamStats()
    val timeSync = TimeSync()
    @Volatile private var lastCaptureNs = 0L
    @Volatile private var lastArrivalNs = 0L

    @Volatile var source: SourceInfo? = null
        private set
    @Volatile var nowPlaying: NowPlayingInfo = NowPlayingInfo()
        private set
    @Volatile var artwork: Bitmap? = null
        private set
    @Volatile var lastCommand: String = ""
        private set
    private var timeSeq = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "WalkDAC", NotificationManager.IMPORTANCE_LOW))
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkdac:play")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "walkdac:wifi")
        multicastLock = wm.createMulticastLock("walkdac:beacon").apply { setReferenceCounted(false) }
        setupSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> command("toggle")
            ACTION_NEXT -> command("next")
            ACTION_PREV -> command("prev")
            else -> startManager()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        netExecutor.shutdownNow()
        session?.release()
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ public API for the activity

    fun isRunning(): Boolean = running

    fun start() = startManager()

    fun stop() {
        stopEverything()
        stopForeground(true)
        stopSelf()
    }

    fun setTargetMs(ms: Int) {
        prefs.targetMs = ms
        engine?.pipeline?.targetMs = ms.toDouble()
    }

    /** Change the manual host (empty = discovery) and reconnect. */
    fun setHost(host: String) {
        prefs.host = host.trim()
        linkDown.set(true)
    }

    fun reconnectNow() { linkDown.set(true) }

    /** play | pause | toggle | next | prev | seek. Safe to call from any thread (UI, MediaSession callback). */
    fun command(op: String, posUs: Long = -1) {
        if (op == "pause" || op == "next" || op == "prev" || op == "seek") engine?.flush()
        val msg = JSONObject().put("t", "cmd").put("op", op)
        if (op == "seek") msg.put("pos_us", posUs)
        netExecutor.execute {
            val sent = controlClient?.send(msg) ?: false
            lastCommand = if (sent) "$op → đã gửi" else "$op → chưa kết nối"
            if (sent) {
                val np = nowPlaying
                nowPlaying = when (op) {
                    "play" -> np.copy(playing = true)
                    "pause" -> np.copy(playing = false)
                    "toggle" -> np.copy(playing = !np.playing)
                    else -> np
                }
                updatePlaybackState()
            }
        }
    }

    fun snapshot(): StatsSnapshot {
        val e = engine
        val p = e?.pipeline
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifi = try { wm.connectionInfo } catch (ex: Exception) { null }
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = getSystemService(BatteryManager::class.java)
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val offset = timeSync.offsetNs
        val networkMs = if (lastArrivalNs != 0L && timeSync.samples > 0) ((lastArrivalNs + offset) - lastCaptureNs) / 1e6 else Double.NaN
        return StatsSnapshot(
            status = status,
            host = connectedHost,
            reconnects = reconnects,
            senders = discovery.senders(),
            source = source,
            nowPlaying = nowPlaying,
            positionUs = currentPositionUs(),
            artwork = artwork,
            engineRate = e?.sampleRate ?: 0,
            pipelineState = p?.state?.name ?: "-",
            fillMs = p?.fillMs ?: 0.0,
            targetMs = p?.targetMs ?: prefs.targetMs.toDouble(),
            driftPpm = p?.ppm ?: 0.0,
            underruns = p?.underruns ?: 0,
            hardResyncs = p?.hardResyncs ?: 0,
            insertedFrames = p?.insertedFrames ?: 0,
            droppedFrames = p?.droppedFrames ?: 0,
            bufferOverflowFrames = p?.buffer?.overflowFrames ?: 0,
            bitrateKbps = stats.bitrateBps / 1000.0,
            blocksReceived = stats.blocksReceived,
            lostBlocks = stats.lostBlocks,
            jitterMs = stats.jitterMs,
            networkMs = networkMs,
            rttMs = if (timeSync.samples > 0) timeSync.lastRttNs / 1e6 else Double.NaN,
            clockOffsetMs = offset / 1e6,
            outputLatencyMs = e?.outputLatencyMs ?: 0.0,
            trackUnderruns = e?.trackUnderruns ?: 0,
            routedDevice = deviceTypeName(e?.routedDeviceType() ?: 0),
            mixerRate = AudioEngine.mixerRate(am),
            mixerFramesPerBuffer = AudioEngine.mixerFramesPerBuffer(am),
            audioError = e?.lastError,
            wifiRssi = wifi?.rssi ?: 0,
            wifiLinkMbps = wifi?.linkSpeed ?: 0,
            wifiFreqMhz = wifi?.frequency ?: 0,
            batteryPercent = if (level >= 0) level * 100 / maxOf(1, scale) else -1,
            batteryTempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            batteryVolts = (batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0,
            batteryCurrentMa = if (currentUa == Int.MIN_VALUE) 0 else currentUa / 1000,
            charging = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            lastCommand = lastCommand,
        )
    }

    // ------------------------------------------------------------------ connection manager

    @Synchronized
    private fun startManager() {
        if (running) return
        running = true
        wakeLock?.acquire()
        wifiLock?.acquire()
        multicastLock?.acquire()
        discovery.start()
        status = "Đang tìm Mac…"
        managerThread = Thread({ managerLoop() }, "walkdac-manager").apply { isDaemon = true; start() }
        updatePlaybackState()
        updateNotification()
    }

    @Synchronized
    private fun stopEverything() {
        if (!running) return
        running = false
        linkDown.set(true)
        teardownConnections()
        discovery.stop()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { /* ignore */ }
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (e: Exception) { /* ignore */ }
        status = "Dừng"
        connectedHost = null
        session?.isActive = false
        updateNotification()
    }

    private fun pickTarget(): Triple<String, Int, Int>? {
        val manual = prefs.host
        if (manual.isNotEmpty()) return Triple(manual, prefs.audioPort, prefs.controlPort)
        if (!prefs.autoConnect) return null
        val b = discovery.senders().firstOrNull() ?: return null
        return Triple(b.host, b.audioPort, b.controlPort)
    }

    private fun managerLoop() {
        while (running) {
            val target = pickTarget()
            if (target == null) {
                status = if (prefs.host.isEmpty() && !prefs.autoConnect) "Nhập IP của Mac" else "Đang tìm Mac…"
                SystemClock.sleep(1000)
                continue
            }
            val (host, audioPort, controlPort) = target
            linkDown.set(false)
            try {
                status = "Đang kết nối $host"
                val control = ControlClient(host, controlPort, ::onControlMessage) { reason -> onLinkDown("control: $reason") }
                control.connect()
                controlClient = control
                control.send(JSONObject().put("t", "hello").put("name", "NW-A105 WalkDAC").put("ver", 1))
                val audio = AudioClient(host, audioPort, ::onFrame) { reason -> onLinkDown("audio: $reason") }
                audio.connect()
                audioClient = audio
                connectedHost = host
                status = "Đã kết nối $host"
                updateNotification()
                pingLoop(control)
            } catch (e: Exception) {
                status = "Lỗi: ${e.message ?: e.toString()}"
                Log.w(TAG, "connection failed: $e")
            } finally {
                teardownConnections()
                if (running) {
                    reconnects++
                    SystemClock.sleep(2000)
                }
            }
        }
    }

    /** Sends time-sync requests (50 quick ones, then 1/s) and stats every 2 s until the link drops. */
    private fun pingLoop(control: ControlClient) {
        var n = 0
        var lastStats = 0L
        while (running && !linkDown.get()) {
            val sendNs = System.nanoTime()
            val ok = control.send(JSONObject().put("t", "time").put("id", ++timeSeq).put("a105_ns", sendNs))
            if (!ok) break
            n++
            val now = SystemClock.elapsedRealtime()
            if (now - lastStats > 2000) {
                lastStats = now
                sendStats(control)
            }
            SystemClock.sleep(if (n < 50) 100 else 1000)
        }
    }

    private fun sendStats(control: ControlClient) {
        val p = engine?.pipeline
        val wifi = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo } catch (e: Exception) { null }
        control.send(JSONObject()
            .put("t", "stats")
            .put("buffer_ms", p?.fillMs?.toInt() ?: 0)
            .put("target_ms", p?.targetMs?.toInt() ?: 0)
            .put("underruns", p?.underruns ?: 0)
            .put("lost", stats.lostBlocks)
            .put("drift_ppm", p?.ppm?.toInt() ?: 0)
            .put("jitter_ms", stats.jitterMs)
            .put("rssi", wifi?.rssi ?: 0))
    }

    private fun onLinkDown(reason: String) {
        if (running && !linkDown.getAndSet(true)) {
            status = "Mất kết nối ($reason)"
            Log.w(TAG, "link down: $reason")
        }
    }

    private fun teardownConnections() {
        audioClient?.close(); audioClient = null
        controlClient?.close(); controlClient = null
        engine?.stop(); engine = null
        connectedHost = null
        timeSync.reset()
        stats.reset()
    }

    // ------------------------------------------------------------------ audio frames (network thread)

    private fun onFrame(h: FrameHeader, samples: FloatArray, n: Int, arrivalNs: Long) {
        var e = engine
        if (e == null || e.sampleRate != h.sampleRate) {
            e?.stop()
            e = AudioEngine(h.sampleRate, prefs.targetMs.toDouble())
            try {
                e.start()
            } catch (ex: Exception) {
                status = "Lỗi AudioTrack: ${ex.message}"
                throw ex
            }
            engine = e
            stats.reset()
            status = "Đang phát ${h.sampleRate} Hz / ${h.bits}-bit"
        }
        e.pipeline.push(samples, 0, n)
        stats.onBlock(h.seq, h.frames, dev.walkdac.core.Protocol.HEADER_SIZE + h.payloadLen, h.captureNs, arrivalNs)
        lastCaptureNs = h.captureNs
        lastArrivalNs = arrivalNs
    }

    // ------------------------------------------------------------------ control messages (control thread)

    /** org.json returns the string "null" for JSON null from optString; we want "". */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun onControlMessage(m: JSONObject) {
        when (m.optString("t")) {
            "source" -> {
                source = SourceInfo(
                    name = m.str("name"), device = m.str("device"), capture = m.str("capture"),
                    rate = m.optInt("rate"), codec = m.str("codec"), bits = m.optInt("bits"),
                    channels = m.optInt("channels", 2), blockMs = m.optInt("block_ms"),
                    mediaControl = m.optBoolean("media_control", false),
                )
                updateNotification()
            }
            "now_playing" -> {
                val old = nowPlaying
                nowPlaying = NowPlayingInfo(
                    app = m.str("app"), playing = m.optBoolean("playing", false),
                    title = m.str("title"), artist = m.str("artist"), album = m.str("album"),
                    durationUs = if (m.isNull("duration_us")) -1 else m.optLong("duration_us", -1),
                    elapsedUs = if (m.isNull("elapsed_us")) -1 else m.optLong("elapsed_us", -1),
                    rate = m.optDouble("rate", 0.0), atMacNs = m.optLong("at_mac_ns", 0),
                )
                if (m.has("artwork_b64") && !m.isNull("artwork_b64")) {
                    artwork = decodeArtwork(m.optString("artwork_b64"))
                } else if (old.title != nowPlaying.title || old.app != nowPlaying.app) {
                    // keep the old artwork only while the track is the same
                    if (old.album != nowPlaying.album) artwork = null
                }
                updateMetadata()
                updatePlaybackState()
                updateNotification()
            }
            "time_ack" -> {
                val sendNs = m.optLong("a105_ns", -1)
                val macNs = m.optLong("mac_ns", -1)
                if (sendNs > 0 && macNs > 0) timeSync.add(sendNs, System.nanoTime(), macNs)
            }
            "flush" -> engine?.flush()
            "cmd_ack" -> {
                val op = m.optString("op")
                lastCommand = if (m.optBoolean("ok")) "$op → OK" else "$op → lỗi: ${m.str("error")}"
            }
        }
    }

    private fun decodeArtwork(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    /** Current track position estimated from the last report and the clock offset. */
    fun currentPositionUs(): Long {
        val np = nowPlaying
        if (np.elapsedUs < 0) return -1
        if (!np.playing || np.atMacNs == 0L || timeSync.samples == 0) return np.elapsedUs
        val macNow = timeSync.remoteNow(System.nanoTime())
        val pos = np.elapsedUs + ((macNow - np.atMacNs) / 1000.0 * np.rate).toLong()
        return if (np.durationUs > 0) pos.coerceIn(0, np.durationUs) else maxOf(0, pos)
    }

    // ------------------------------------------------------------------ media session + notification

    private fun setupSession() {
        val s = MediaSession(this, "WalkDAC")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command("play")
            override fun onPause() = command("pause")
            override fun onSkipToNext() = command("next")
            override fun onSkipToPrevious() = command("prev")
            override fun onStop() = command("pause")
            override fun onSeekTo(pos: Long) = command("seek", pos * 1000)
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN) {
                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { command("next"); return true }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> { command("prev"); return true }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        session = s
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val s = session ?: return
        val np = nowPlaying
        val state = if (!running) PlaybackState.STATE_STOPPED else if (np.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val pos = currentPositionUs().let { if (it < 0) PlaybackState.PLAYBACK_POSITION_UNKNOWN else it / 1000 }
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(state, pos, if (np.playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        s.isActive = running
    }

    private fun updateMetadata() {
        val s = session ?: return
        val np = nowPlaying
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, np.title.ifEmpty { "WalkDAC" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
        if (np.durationUs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, np.durationUs / 1000)
        artwork?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        s.setMetadata(b.build())
    }

    private fun pendingAction(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, WalkDacService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(): Notification {
        val np = nowPlaying
        val title = if (np.title.isNotEmpty()) np.title else "WalkDAC"
        val text = if (np.artist.isNotEmpty()) "${np.artist} · $status" else status
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Prev", pendingAction(ACTION_PREV, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, if (np.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), "Play/Pause", pendingAction(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", pendingAction(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", pendingAction(ACTION_STOP, 4)).build())
        session?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) }
        artwork?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notification update failed: $e")
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        0 -> "-"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "jack 3.5 mm"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "jack 3.5 mm (headset)"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line out"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "loa trong"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        else -> "type $type"
    }

    companion object {
        private const val TAG = "WalkDAC.Service"
        const val CHANNEL = "walkdac"
        const val NOTIF_ID = 1
        const val ACTION_START = "dev.walkdac.receiver.START"
        const val ACTION_STOP = "dev.walkdac.receiver.STOP"
        const val ACTION_TOGGLE = "dev.walkdac.receiver.TOGGLE"
        const val ACTION_NEXT = "dev.walkdac.receiver.NEXT"
        const val ACTION_PREV = "dev.walkdac.receiver.PREV"
    }
}
