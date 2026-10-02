package dev.walkdac.receiver

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/** Single screen: now playing + live technical parameters + transport buttons. Built in code (no resources). */
class MainActivity : Activity() {

    private var service: WalkDacService? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var artView: ImageView
    private lateinit var positionView: TextView
    private lateinit var bufferBar: ProgressBar
    private lateinit var statsView: TextView
    private lateinit var playButton: Button
    private lateinit var hostEdit: EditText
    private lateinit var targetLabel: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as WalkDacService.LocalBinder).service()
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildUi())
        startForegroundService(Intent(this, WalkDacService::class.java).setAction(WalkDacService.ACTION_START))
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, WalkDacService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        if (service != null) unbindService(connection)
        service = null
        super.onStop()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int = Color.WHITE, mono: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 20))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusView = text(13f, Color.rgb(140, 200, 255))
        root.addView(statusView)

        val np = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(40, 40, 44)) }
        np.addView(artView, LinearLayout.LayoutParams(dp(96), dp(96)))
        val npText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titleView = text(17f).apply { maxLines = 2 }
        artistView = text(14f, Color.rgb(200, 200, 200)).apply { maxLines = 2 }
        positionView = text(13f, Color.rgb(160, 160, 160), mono = true)
        npText.addView(titleView); npText.addView(artistView); npText.addView(positionView)
        np.addView(npText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(np)

        bufferBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(bufferBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }
        buttons.addView(btn("⏮") { service?.command("prev") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playButton = btn("⏯") { service?.command("toggle") }
        buttons.addView(playButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(btn("⏭") { service?.command("next") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        statsView = text(12.5f, Color.rgb(220, 220, 220), mono = true)
        val scroll = ScrollView(this)
        scroll.addView(statsView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- settings row: host + connect + auto
        val hostRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hostEdit = EditText(this).apply {
            hint = "IP của Mac (trống = tự tìm)"
            setText(prefs.host)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        hostRow.addView(hostEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hostRow.addView(btn("Kết nối") {
            val s = service ?: return@btn
            s.setHost(hostEdit.text.toString())
            if (!s.isRunning()) s.start() else s.reconnectNow()
        })
        root.addView(hostRow)

        val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val auto = Switch(this).apply {
            text = "Tự tìm Mac"; setTextColor(Color.WHITE); isChecked = prefs.autoConnect
            setOnCheckedChangeListener { _, checked -> prefs.autoConnect = checked }
        }
        autoRow.addView(auto, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        autoRow.addView(btn("Dừng") { service?.stop() })
        root.addView(autoRow)

        targetLabel = text(12f, Color.rgb(180, 180, 180))
        root.addView(targetLabel)
        val seek = SeekBar(this).apply {
            max = (2000 - 200) / 50
            progress = (prefs.targetMs - 200) / 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val ms = 200 + p * 50
                    targetLabel.text = "Bộ đệm mục tiêu: $ms ms"
                    if (fromUser) service?.setTargetMs(ms)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        targetLabel.text = "Bộ đệm mục tiêu: ${prefs.targetMs} ms"
        root.addView(seek)
        return root
    }

    // ------------------------------------------------------------------ refresh

    private fun fmtTime(us: Long): String {
        if (us < 0) return "--:--"
        val s = us / 1_000_000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun refresh() {
        val s = service ?: run { statusView.text = "Đang khởi động dịch vụ…"; return }
        val st = s.snapshot()
        statusView.text = st.status + (st.host?.let { "  ·  $it" } ?: "") + if (st.reconnects > 0) "  ·  nối lại ${st.reconnects}" else ""
        val np = st.nowPlaying
        titleView.text = np.title.ifEmpty { if (st.source?.mediaControl == false) "(Mac không có media-control)" else "—" }
        artistView.text = listOf(np.artist, np.album).filter { it.isNotEmpty() }.joinToString(" · ")
        positionView.text = (if (np.playing) "▶ " else "⏸ ") + fmtTime(st.positionUs) + " / " + fmtTime(np.durationUs) +
            (if (np.app.isNotEmpty()) "   " + np.app.substringAfterLast('.') else "")
        playButton.text = if (np.playing) "⏸" else "▶"
        if (st.artwork != null) artView.setImageBitmap(st.artwork) else artView.setImageDrawable(null)
        bufferBar.progress = if (st.targetMs > 0) (st.fillMs / (st.targetMs * 2) * 1000).toInt().coerceIn(0, 1000) else 0

        val src = st.source
        val sb = StringBuilder()
        sb.append("NGUỒN  ").append(src?.let { "${it.name} · ${it.device}\n       ${it.codec} ${it.rate} Hz · ${it.blockMs} ms/khối" } ?: "—").append('\n')
        sb.append(String.format(Locale.US, "       %.0f kb/s · %d khối · mất %d\n", st.bitrateKbps, st.blocksReceived, st.lostBlocks))
        sb.append(String.format(Locale.US, "MẠNG   %s · %d dBm · %d Mb/s\n", if (st.wifiFreqMhz >= 5000) "5 GHz" else if (st.wifiFreqMhz > 0) "2.4 GHz" else "?", st.wifiRssi, st.wifiLinkMbps))
        sb.append(String.format(Locale.US, "       RTT %s · jitter %.1f ms · mạng %s\n", fmtMs(st.rttMs), st.jitterMs, fmtMs(st.networkMs)))
        sb.append(String.format(Locale.US, "ĐỆM    %.0f / %.0f ms · %s\n", st.fillMs, st.targetMs, st.pipelineState))
        sb.append(String.format(Locale.US, "       trôi %+.0f ppm · chèn %d · bỏ %d\n", st.driftPpm, st.insertedFrames, st.droppedFrames))
        sb.append(String.format(Locale.US, "       underrun %d · resync %d · tràn %d\n", st.underruns, st.hardResyncs, st.bufferOverflowFrames))
        sb.append(String.format(Locale.US, "RA     float %d Hz → mixer %d Hz (%d fr)\n", st.engineRate, st.mixerRate, st.mixerFramesPerBuffer))
        sb.append(String.format(Locale.US, "       %s · trễ ra %.0f ms · underrun %d\n", st.routedDevice, st.outputLatencyMs, st.trackUnderruns))
        val total = (if (st.networkMs.isNaN()) 0.0 else st.networkMs) + st.fillMs + st.outputLatencyMs
        sb.append(String.format(Locale.US, "       tổng trễ ≈ %.0f ms\n", total))
        sb.append(String.format(Locale.US, "MÁY    pin %d%%%s · %.1f °C · %.2f V · %d mA\n", st.batteryPercent, if (st.charging) " ⚡" else "", st.batteryTempC, st.batteryVolts, st.batteryCurrentMa))
        if (st.lastCommand.isNotEmpty()) sb.append("LỆNH   ").append(st.lastCommand).append('\n')
        st.audioError?.let { sb.append("LỖI    ").append(it).append('\n') }
        if (st.senders.isNotEmpty()) {
            sb.append("THẤY   ").append(st.senders.joinToString(", ") { "${it.name} (${it.host})" }).append('\n')
        }
        statsView.text = sb.toString()
    }

    private fun fmtMs(v: Double): String = if (v.isNaN()) "?" else String.format(Locale.US, "%.0f ms", v)
}
