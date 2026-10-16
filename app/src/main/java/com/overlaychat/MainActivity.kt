package com.overlaychat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val REQ_PICK = 10
        const val REQ_SAVE = 11
        const val PERM_NOTIF = 1
        const val PERM_MIC = 2
        const val MAX_REC = 180
    }

    private val BG = Color.parseColor("#0F0F14")
    private val BG2 = Color.parseColor("#1A1A23")
    private val FG = Color.parseColor("#E8E8EE")
    private val MUTED = Color.parseColor("#8B8B99")
    private val ACCENT = Color.parseColor("#FF3D5A")
    private val GREEN = Color.parseColor("#22A559")
    private val BLUE = Color.parseColor("#3B82F6")
    private val COLORS = intArrayOf(
        Color.parseColor("#FF6B6B"), Color.parseColor("#FECA57"), Color.parseColor("#48DBFB"),
        Color.parseColor("#1DD1A1"), Color.parseColor("#FF9FF3"), Color.parseColor("#54A0FF"),
        Color.parseColor("#F368E0"), Color.parseColor("#00D2D3"), Color.parseColor("#FF9F43"),
        Color.parseColor("#A29BFE")
    )

    private val ui = Handler(Looper.getMainLooper())

    // sohbet ekranı
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var etMsg: EditText? = null
    private var micBtn: Button? = null
    private var cancelBtn: Button? = null
    private var callBox: LinearLayout? = null
    private var callTimeTv: TextView? = null
    private var chatShown = false
    private var myName = "Misafir"
    private var pending: Triple<String, Int, String>? = null
    private var pendingSave: String? = null
    private var pendingMic: ((Boolean) -> Unit)? = null

    private val rows = HashMap<Line, LinearLayout>()
    private val progRefs = HashMap<Line, Pair<ProgressBar, TextView>>()

    // ses kaydı / oynatma
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var mp: MediaPlayer? = null
    private var curPath: String? = null
    private var curBtn: Button? = null
    private var curSeek: SeekBar? = null
    private var curTime: TextView? = null
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            if (!chatShown) return
            updateTransfers()
            updatePlayer()
            updateRec()
            updateCallTimer()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ChatService.running) showChat() else showLogin()
        handleCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    private fun handleCallIntent(i: Intent?) {
        val what = i?.getStringExtra("call") ?: return
        i?.removeExtra("call")
        if (what == "incoming" && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (what == "accept" && ChatService.inviteCid.isNotEmpty()) {
            withMic { ChatService.acceptCall() }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatService.appVisible = true
        ChatService.clearRecent()
        getSystemService(NotificationManager::class.java).cancel(ChatService.MSG_NOTIF_ID)
        if (chatShown && list != null) {
            renderAll()
            attachListener()
        }
    }

    override fun onStop() {
        ChatService.appVisible = false
        ChatService.listener = null
        ChatService.callListener = null
        ui.removeCallbacks(tick)
        if (recorder != null) finishRec(false)
        stopPlayback()
        super.onStop()
    }

    // ------------------------------------------------------------ yardımcılar
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtDur(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    private fun human(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> String.format("%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format("%.1f MB", n / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", n / (1024.0 * 1024.0 * 1024.0))
    }

    private fun edit(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(FG)
        setBackgroundColor(BG2)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setSingleLine(true)
    }

    private fun button(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
    }

    private fun smallButton(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun iconButton(text: String) = Button(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(FG)
        setBackgroundColor(BG2)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Mikrofon izni varsa hemen, yoksa sorup (izin verilsin verilmesin) callback'i çağırır. */
    private fun withMic(cb: (Boolean) -> Unit) {
        if (micGranted()) {
            cb(true)
        } else {
            pendingMic = cb
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_MIC)
        }
    }

    /** Oda kodu (ABCDE-FGHIJ) veya IP:port -> (host, port) */
    private fun parseAddr(text: String): Pair<String, Int> {
        val t = text.trim()
        if (t.contains('.') || t.contains(':')) {
            val parts = t.split(":")
            val host = parts[0].trim()
            require(host.isNotEmpty())
            return host to (parts.getOrNull(1)?.trim()?.toInt() ?: 5555)
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val bytes = ArrayList<Int>()
        for (ch in t.uppercase()) {
            val v = alphabet.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes.add((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        require(bytes.size >= 6)
        return "${bytes[0]}.${bytes[1]}.${bytes[2]}.${bytes[3]}" to ((bytes[4] shl 8) or bytes[5])
    }

    // ------------------------------------------------------------ giriş ekranı
    private fun showLogin() {
        chatShown = false
        list = null
        scroll = null
        etMsg = null
        micBtn = null
        cancelBtn = null
        callBox = null
        ChatService.listener = null
        ChatService.callListener = null
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        val title = TextView(this).apply {
            text = "Overlay Chat"
            setTextColor(FG)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "Bilgisayardaki sunucunun oda koduyla katıl"
            setTextColor(MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val etName = edit("Adın").apply { setText(prefs.getString("name", "Misafir")) }
        val etAddr = edit("Oda kodu veya IP:port")
        val join = button("Katıl", ACCENT)

        join.setOnClickListener {
            val name = etName.text.toString().trim().take(20).ifEmpty { "Misafir" }
            val hp = try { parseAddr(etAddr.text.toString()) } catch (e: Exception) { null }
            if (hp == null) {
                Toast.makeText(this, "Kod / adres anlaşılamadı", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("name", name).apply()
            pending = Triple(hp.first, hp.second, name)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIF)
            } else {
                startChat()
            }
        }

        root.addView(logo, LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(title, lp(12))
        root.addView(sub, lp(4))
        root.addView(etName, lp(24))
        root.addView(etAddr, lp(12))
        root.addView(join, lp(20))
        setContentView(root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == PERM_NOTIF && pending != null) {
            if (!granted) Toast.makeText(this, "Bildirim izni verilmedi, bildirim gelmeyecek", Toast.LENGTH_LONG).show()
            startChat()
        } else if (requestCode == PERM_MIC) {
            val cb = pendingMic
            pendingMic = null
            cb?.invoke(granted)
        }
    }

    private fun startChat() {
        val (host, port, name) = pending ?: return
        pending = null
        ChatService.clear()
        val i = Intent(this, ChatService::class.java)
            .putExtra("host", host).putExtra("port", port).putExtra("name", name)
        startForegroundService(i)
        showChat()
    }

    // ------------------------------------------------------------ sohbet ekranı
    private fun showChat() {
        chatShown = true
        myName = getSharedPreferences("cfg", MODE_PRIVATE).getString("name", "Misafir") ?: "Misafir"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(6))
        }
        val title = TextView(this).apply {
            text = "● CANLI SOHBET"
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
        }
        val callBtn = iconButton("📞")
        callBtn.setOnClickListener { pickCallTarget() }
        val leave = button("Ayrıl", BG2).apply { setTextColor(MUTED) }
        leave.setOnClickListener {
            if (recorder != null) finishRec(false)
            stopPlayback()
            if (ChatService.inCall()) ChatService.hangUp()
            stopService(Intent(this, ChatService::class.java))
            showLogin()
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(callBtn)
        header.addView(leave)

        val cb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        callBox = cb

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val sv = ScrollView(this).apply { addView(box) }
        list = box
        scroll = sv

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG2)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val attach = iconButton("📎")
        val et = edit("Mesaj yaz...").apply {
            setBackgroundColor(BG)
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val mic = iconButton("🎤")
        val cancel = iconButton("✕").apply {
            setTextColor(MUTED)
            visibility = View.GONE
        }
        val sendBtn = button("➤", ACCENT)
        etMsg = et
        micBtn = mic
        cancelBtn = cancel

        val doSend = {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                ChatService.send(t)
                et.setText("")
            }
        }
        sendBtn.setOnClickListener { doSend() }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
        }
        attach.setOnClickListener { pickFile() }
        mic.setOnClickListener { onMic() }
        cancel.setOnClickListener { finishRec(false) }

        row.addView(attach)
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cancel)
        row.addView(mic)
        row.addView(sendBtn)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        renderAll()
        attachListener()
    }

    private fun attachListener() {
        ChatService.listener = { line -> runOnUiThread { appendLine(line) } }
        ChatService.callListener = { runOnUiThread { refreshCallUi() } }
        refreshCallUi()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private fun renderAll() {
        val l = list ?: return
        l.removeAllViews()
        rows.clear()
        progRefs.clear()
        ChatService.log.forEach { l.addView(buildRow(it)) }
        scrollDown()
    }

    private fun appendLine(line: Line) {
        list?.addView(buildRow(line))
        scrollDown()
    }

    private fun scrollDown() {
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ mesaj satırları
    private fun buildRow(l: Line): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(3), dp(14), dp(3))
        }
        rows[l] = box
        fillRow(box, l)
        return box
    }

    private fun fillRow(box: LinearLayout, l: Line) {
        box.removeAllViews()
        progRefs.remove(l)
        val head = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            text = format(l)
        }
        box.addView(head)
        if (l.kind == "file") {
            val x = l.xfer
            val body: View = when {
                x == null -> fileCard(l)
                x.state == "active" -> progressCard(l, x)
                x.state == "done" -> if (x.tx) noteCard("✓ Gönderildi: ${l.fname} (${human(l.size)})") else fileCard(l)
                else -> noteCard("✗ ${if (x.tx) "Gönderilemedi" else "Alınamadı"}: ${x.error}")
            }
            box.addView(body, lp(4))
        }
    }

    private fun format(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        if (l.kind == "sys") {
            sb.append(l.text)
            sb.setSpan(ForegroundColorSpan(MUTED), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.85f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        var s = sb.length
        sb.append(l.time + " ")
        sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s = sb.length
        sb.append(l.name)
        sb.setSpan(ForegroundColorSpan(COLORS[l.name.sumOf { it.code } % COLORS.size]), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (l.kind == "msg") sb.append("  " + l.text)
        return sb
    }

    private fun noteCard(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        textSize = 13f
        setBackgroundColor(BG2)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    // ------------------------------------------------------------ aktarım ilerlemesi
    private fun progressCard(l: Line, x: Xfer): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val title = TextView(this).apply {
            setTextColor(FG)
            textSize = 14f
            text = (if (x.tx) "⬆ Gönderiliyor: " else "⬇ Alınıyor: ") + l.fname
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val stat = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 12f
        }
        card.addView(title)
        card.addView(bar, lp(8))
        card.addView(stat, lp(4))
        if (x.tx) {
            val cancel = smallButton("✕ İptal", Color.parseColor("#2A2A36"))
            cancel.setOnClickListener { x.cancel = true }
            card.addView(cancel, lp(8))
        }
        progRefs[l] = Pair(bar, stat)
        updateOne(x, bar, stat)
        return card
    }

    private fun updateOne(x: Xfer, bar: ProgressBar, stat: TextView) {
        val total = if (x.size > 0) x.size else 1L
        val pct = (x.done * 100.0 / total).toInt().coerceIn(0, 100)
        bar.progress = (x.done * 1000L / total).toInt().coerceIn(0, 1000)
        val secs = (System.currentTimeMillis() - x.startMs) / 1000.0
        val speed = if (secs > 0.5) human((x.done / secs).toLong()) + "/sn" else ""
        stat.text = "%$pct · ${human(x.done)} / ${human(x.size)}  $speed"
    }

    private fun updateTransfers() {
        for (l in ChatService.activeX) {
            val x = l.xfer ?: continue
            if (x.state == "active") {
                val p = progRefs[l]
                if (p != null) updateOne(x, p.first, p.second)
            } else {
                ChatService.activeX.remove(l)
                val box = rows[l]
                if (box != null) fillRow(box, l)
                scrollDown()
            }
        }
    }

    // ------------------------------------------------------------ dosya kartı + oynatıcı
    private fun fileCard(l: Line): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val title = TextView(this).apply {
            setTextColor(FG)
            textSize = 14f
            text = when (l.fileKind) {
                "voice" -> "🎤 Sesli mesaj"
                "audio" -> "🎵 ${l.fname}  (${human(l.size)})"
                else -> "📎 ${l.fname}  (${human(l.size)})"
            }
        }
        card.addView(title)

        val isAudio = l.fileKind == "voice" || l.fileKind == "audio"
        if (isAudio) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val play = smallButton("▶", ACCENT)
            val seek = SeekBar(this).apply { max = 1000 }
            val first = if (l.dur > 0) fmtDur(l.dur / 1000) else ""
            val time = TextView(this).apply {
                setTextColor(MUTED)
                textSize = 12f
                text = first
                tag = first
            }
            play.setOnClickListener { togglePlay(l.path, play, seek, time) }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && curSeek === s) {
                        val m = mp ?: return
                        val d = m.duration
                        if (d > 0) {
                            try { m.seekTo((p * d.toLong() / 1000).toInt()) } catch (e: Exception) {}
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false }
            })
            row.addView(play)
            row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            })
            row.addView(time)
            card.addView(row, lp(8))
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (l.fileKind != "voice") {
            val open = smallButton("Aç", BLUE)
            open.setOnClickListener { openFile(l.path, l.fname) }
            actions.addView(open)
        }
        val save = smallButton("Kaydet", Color.parseColor("#2A2A36"))
        save.setOnClickListener { saveFile(l.path, l.fname) }
        actions.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (l.fileKind != "voice") leftMargin = dp(8) })
        card.addView(actions, lp(8))
        return card
    }

    private fun togglePlay(path: String, btn: Button, seek: SeekBar, time: TextView) {
        val cur = mp
        if (cur != null && curPath == path) {
            try {
                if (cur.isPlaying) { cur.pause(); btn.text = "▶" } else { cur.start(); btn.text = "⏸" }
            } catch (e: Exception) {
                stopPlayback()
            }
            return
        }
        stopPlayback()
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.setOnCompletionListener { stopPlayback() }
            p.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            p.prepare()
            p.start()
            mp = p
            curPath = path
            curBtn = btn
            curSeek = seek
            curTime = time
            btn.text = "⏸"
        } catch (e: Exception) {
            try { p.release() } catch (e2: Exception) {}
            toast("Bu ses çalınamadı")
        }
    }

    private fun updatePlayer() {
        val p = mp ?: return
        try {
            val d = p.duration
            val pos = p.currentPosition
            if (d > 0 && !dragging) curSeek?.progress = (pos * 1000L / d).toInt()
            curTime?.text = "${fmtDur(pos / 1000)} / ${fmtDur(d / 1000)}"
        } catch (e: Exception) {
        }
    }

    private fun stopPlayback() {
        try { mp?.stop() } catch (e: Exception) {}
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        curBtn?.text = "▶"
        curSeek?.progress = 0
        curTime?.text = (curTime?.tag as? String) ?: ""
        curPath = null
        curBtn = null
        curSeek = null
        curTime = null
    }

    // ------------------------------------------------------------ dosya aç / kaydet / gönder
    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun openFile(path: String, fname: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(fname))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast("Bu dosyayı açacak uygulama yok, Kaydet'i dene")
        } catch (e: Exception) {
            toast("Dosya açılamadı")
        }
    }

    private fun saveFile(path: String, fname: String) {
        pendingSave = path
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_TITLE, fname)
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (e: Exception) {
            toast("Kaydetme ekranı açılamadı")
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("Dosya seçme ekranı açılamadı")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK -> sendUri(uri)
            REQ_SAVE -> {
                val src = pendingSave ?: return
                pendingSave = null
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { o ->
                            File(src).inputStream().use { it.copyTo(o) }
                        }
                        runOnUiThread { toast("Kaydedildi") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Kaydedilemedi") }
                    }
                }.start()
            }
        }
    }

    private fun queryName(uri: Uri): Pair<String, Long> {
        var name = "dosya"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }
        if (size <= 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (e: Exception) {
            }
        }
        return Pair(name, size)
    }

    private fun sendUri(uri: Uri) {
        val (name, size) = queryName(uri)
        if (size <= 0) {
            toast("Dosya boş ya da okunamadı")
            return
        }
        if (size > ChatService.MAX_FILE) {
            toast("Dosya çok büyük (${human(size)}). En fazla ${human(ChatService.MAX_FILE)}")
            return
        }
        ChatService.sendFile(applicationContext, uri, name, size, myName)
    }

    // ------------------------------------------------------------ sesli mesaj (m4a)
    private fun onMic() {
        if (recorder != null) {
            finishRec(true)
            return
        }
        if (ChatService.inCall()) {
            toast("Aramadayken sesli mesaj kaydedilemez")
            return
        }
        withMic { ok -> if (ok) startRec() else toast("Mikrofon izni verilmedi") }
    }

    private fun startRec() {
        stopPlayback()
        val f = File(cacheDir, "ses_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (e2: Exception) {}
            f.delete()
            toast("Mikrofon başlatılamadı")
            return
        }
        recorder = r
        recFile = f
        recStart = SystemClock.elapsedRealtime()
        micBtn?.text = "⏹"
        micBtn?.setTextColor(ACCENT)
        cancelBtn?.visibility = View.VISIBLE
        etMsg?.hint = "● Kayıt 0:00  —  göndermek için ⏹"
    }

    private fun updateRec() {
        if (recorder == null) return
        val s = ((SystemClock.elapsedRealtime() - recStart) / 1000).toInt()
        if (s >= MAX_REC) {
            finishRec(true)
            return
        }
        etMsg?.hint = "● Kayıt ${fmtDur(s)}  —  göndermek için ⏹"
    }

    private fun finishRec(send: Boolean) {
        val r = recorder ?: return
        val f = recFile
        val durMs = (SystemClock.elapsedRealtime() - recStart).toInt()
        recorder = null
        recFile = null
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        try { r.release() } catch (e: Exception) {}
        micBtn?.text = "🎤"
        micBtn?.setTextColor(FG)
        cancelBtn?.visibility = View.GONE
        etMsg?.hint = "Mesaj yaz..."
        if (f == null) return
        if (!send) {
            f.delete()
            return
        }
        if (!ok || durMs < 800) {
            f.delete()
            toast("Kayıt çok kısaydı")
            return
        }
        Thread {
            try {
                val bytes = f.readBytes()
                ChatService.sendVoice(bytes, durMs)
            } catch (e: Exception) {
                runOnUiThread { toast("Kayıt gönderilemedi") }
            }
            f.delete()
        }.start()
    }

    // ------------------------------------------------------------ sesli arama arayüzü
    private fun pickCallTarget() {
        if (ChatService.inCall() || ChatService.inviteCid.isNotEmpty()) {
            toast("Zaten bir arama var")
            return
        }
        val others = ChatService.users.filter { it.first != ChatService.myId }
        if (others.isEmpty()) {
            toast("Odada başka kimse yok")
            return
        }
        val labels = ArrayList<String>()
        labels.add("👥 Herkesi ara (grup araması)")
        others.forEach { labels.add("👤 ${it.second}") }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sesli arama")
            .setItems(labels.toTypedArray()) { _, which ->
                val to: List<Int>? = if (which == 0) null else listOf(others[which - 1].first)
                withMic { ok ->
                    if (!ok) toast("Mikrofon izni yok: sadece dinleyebilirsin")
                    ChatService.startCall(to)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun refreshCallUi() {
        val box = callBox ?: return
        box.removeAllViews()
        callTimeTv = null
        if (ChatService.inviteCid.isNotEmpty()) {
            val p = callPanel(Color.parseColor("#2A2418"))
            val t = TextView(this).apply {
                setTextColor(FG)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                text = "📞 ${ChatService.inviteName} seni arıyor" + if (ChatService.inviteGroup) " (grup)" else ""
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ok = button("Kabul", GREEN)
            val no = button("Reddet", ACCENT)
            ok.setOnClickListener { withMic { ChatService.acceptCall() } }
            no.setOnClickListener { ChatService.declineCall() }
            row.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            row.addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            p.addView(t)
            p.addView(row, lp(8))
            box.addView(p)
        } else if (ChatService.inCall()) {
            val p = callPanel(Color.parseColor("#17261C"))
            val names = ChatService.callJoined.values.joinToString(", ")
            val t = TextView(this).apply {
                setTextColor(FG)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            callTimeTv = t
            val who = TextView(this).apply {
                setTextColor(MUTED)
                textSize = 12f
                text = if (names.isEmpty()) "Bağlanıyor…" else names
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val mute = smallButton(if (ChatService.muted) "🔇 Sessiz" else "🎙 Açık", Color.parseColor("#2A2A36"))
            val spk = smallButton(if (ChatService.speaker) "🔊 Hoparlör" else "📱 Ahize", Color.parseColor("#2A2A36"))
            val end = smallButton("Bitir", ACCENT)
            mute.setOnClickListener { ChatService.setMuted(!ChatService.muted); refreshCallUi() }
            spk.setOnClickListener { ChatService.setSpeaker(!ChatService.speaker) }
            end.setOnClickListener { ChatService.hangUp() }
            row.addView(mute)
            row.addView(spk, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
            row.addView(end, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
            p.addView(t)
            p.addView(who)
            p.addView(row, lp(8))
            box.addView(p)
            updateCallTimer()
        }
        box.visibility = if (box.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun callPanel(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun updateCallTimer() {
        val t = callTimeTv ?: return
        val st = ChatService.callStartMs
        t.text = if (st > 0) {
            "📞 Aramada · " + fmtDur(((System.currentTimeMillis() - st) / 1000).toInt())
        } else {
            "📞 Aranıyor…"
        }
    }
}
