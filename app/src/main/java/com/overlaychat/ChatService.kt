package com.overlaychat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Bir dosya aktarımının (gönderme/alma) ortak durumu. */
class Xfer(val id: String, val fname: String, val size: Long, val tx: Boolean) {
    @Volatile var done = 0L
    @Volatile var state = "active"      // active | done | error | abort
    @Volatile var error = ""
    @Volatile var cancel = false
    val startMs = System.currentTimeMillis()
}

/**
 * kind: "msg" | "sys" | "file"
 * fileKind (kind == "file" ise): "voice" | "audio" | "file"
 * xfer != null ise aktarım satırı (ilerleme çubuğu, bitince path dolar).
 */
class Line(
    val kind: String,
    val name: String,
    var text: String,
    val time: String,
    var fileKind: String = "",
    var fname: String = "",
    var path: String = "",
    var dur: Int = 0,
    var size: Long = 0L,
    val xfer: Xfer? = null
)

/** Sohbet sunucusuna arka planda bağlı kalan ön plan servisi: mesaj, dosya, sesli arama, bildirimler. */
class ChatService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MSG = "messages"
        const val CH_CALL = "calls"
        const val MSG_NOTIF_ID = 2
        const val CALL_NOTIF_ID = 3
        const val ACT_DECLINE = "com.overlaychat.DECLINE"
        const val ACT_HANGUP = "com.overlaychat.HANGUP"
        const val CHUNK = 192 * 1024              // 3'ün katı -> base64 dolgusuz
        const val MAX_FILE = 1024L * 1024 * 1024  // 1 GB
        const val RING_MS = 45000L
        val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr", "wma")

        val log = CopyOnWriteArrayList<Line>()
        val activeX = CopyOnWriteArrayList<Line>()
        private val recent = ArrayList<String>()

        @Volatile var listener: ((Line) -> Unit)? = null
        @Volatile var callListener: (() -> Unit)? = null
        @Volatile var appVisible = false
        @Volatile var running = false
        @Volatile var out: OutputStream? = null
        @Volatile var inst: ChatService? = null

        // kullanıcı listesi
        @Volatile var myId = 0
        @Volatile var users: List<Pair<Int, String>> = emptyList()

        // arama durumu
        @Volatile var callId = ""
        @Volatile var callGroup = false
        @Volatile var callJoined: Map<Int, String> = emptyMap()
        @Volatile var callPending: Set<Int> = emptySet()
        @Volatile var callStartMs = 0L
        @Volatile var inviteCid = ""
        @Volatile var inviteName = ""
        @Volatile var inviteGroup = false
        @Volatile var muted = false
        @Volatile var speaker = false

        private val sender = Executors.newSingleThreadExecutor()
        private val fileExec = Executors.newCachedThreadPool()

        fun clear() {
            log.clear()
            activeX.clear()
            clearRecent()
        }

        fun clearRecent() {
            synchronized(recent) { recent.clear() }
        }

        private fun nowStr(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        fun sysLine(text: String) {
            val l = Line("sys", "", text, nowStr())
            log.add(l)
            listener?.invoke(l)
        }

        /** Tek satır JSON yazar (satır bütünlüğü için kilitli). */
        private fun writeRaw(s: String): Boolean {
            val o = out ?: return false
            val data = (s + "\n").toByteArray(Charsets.UTF_8)
            synchronized(o) {
                o.write(data)
                o.flush()
            }
            return true
        }

        private fun write(obj: JSONObject) {
            if (!writeRaw(obj.toString())) sysLine("Gönderilemedi (bağlantı yok)")
        }

        fun send(text: String) {
            sender.execute {
                try {
                    write(JSONObject().put("type", "msg").put("text", text))
                } catch (e: Exception) {
                    sysLine("Mesaj gönderilemedi")
                }
            }
        }

        /** Sesli mesaj (m4a) — tek parça, sunucu herkese (bize de) geri yollar. */
        fun sendVoice(bytes: ByteArray, durMs: Int) {
            sender.execute {
                try {
                    write(
                        JSONObject().put("type", "voice").put("ext", "m4a").put("dur", durMs)
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    )
                } catch (e: Exception) {
                    sysLine("Sesli mesaj gönderilemedi")
                }
            }
        }

        /** Dosyayı parça parça gönderir; ilerleme Xfer üzerinden izlenir. */
        fun sendFile(ctx: Context, uri: android.net.Uri, name: String, size: Long, myName: String) {
            val tr = Xfer(System.currentTimeMillis().toString(36) + (0..999).random(), name, size, true)
            val line = Line("file", myName, "📎 $name", nowStr(), "file", name, "", 0, size, tr)
            log.add(line)
            activeX.add(line)
            listener?.invoke(line)
            fileExec.execute {
                try {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Dosya açılamadı")
                    input.use { ins ->
                        val head = JSONObject().put("type", "fstart").put("id", tr.id)
                            .put("fname", name).put("size", size)
                        if (!writeRaw(head.toString())) throw java.io.IOException("Bağlantı yok")
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            if (tr.cancel) {
                                writeRaw("{\"type\":\"fabort\",\"id\":\"${tr.id}\"}")
                                tr.state = "abort"
                                tr.error = "İptal edildi"
                                return@execute
                            }
                            var n = 0
                            while (n < CHUNK) {
                                val r = ins.read(buf, n, CHUNK - n)
                                if (r < 0) break
                                n += r
                            }
                            if (n <= 0) break
                            val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                            if (!writeRaw("{\"type\":\"fchunk\",\"id\":\"${tr.id}\",\"data\":\"$b64\"}")) {
                                throw java.io.IOException("Bağlantı koptu")
                            }
                            tr.done += n
                            if (n < CHUNK) break
                        }
                    }
                    if (tr.done != size && size > 0) {
                        writeRaw("{\"type\":\"fabort\",\"id\":\"${tr.id}\"}")
                        tr.state = "error"
                        tr.error = "Dosya gönderilirken değişti"
                        return@execute
                    }
                    writeRaw("{\"type\":\"fend\",\"id\":\"${tr.id}\"}")
                    tr.state = "done"
                } catch (e: Exception) {
                    tr.state = "error"
                    tr.error = "Gönderilemedi (${e.message ?: "hata"})"
                }
            }
        }

        // ------------------------------------------------------------ arama komutları (arayüzden)
        fun startCall(to: List<Int>?) {
            sender.execute {
                try {
                    val o = JSONObject().put("type", "call_start")
                    if (to == null) o.put("to", "all") else o.put("to", JSONArray(to))
                    write(o)
                } catch (e: Exception) {
                    sysLine("Arama başlatılamadı")
                }
            }
        }

        fun acceptCall() { inst?.doAccept() }
        fun declineCall() { inst?.doDecline() }
        fun hangUp() { inst?.doHangUp() }
        fun setMuted(v: Boolean) { muted = v }
        fun setSpeaker(v: Boolean) { inst?.applySpeaker(v) }
        fun inCall(): Boolean = callId.isNotEmpty()
    }

    private var host = ""
    private var port = 0
    private var myName = ""
    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val rx = HashMap<String, Triple<Xfer, Line, FileOutputStream>>()
    private var audio: CallAudio? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val ringHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ringStop = Runnable { stopRing() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        inst = this
        refreshForeground()

        if (intent == null && !running) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACT_DECLINE -> { doDecline(); return START_NOT_STICKY }
            ACT_HANGUP -> { doHangUp(); return START_NOT_STICKY }
        }
        if (intent != null && !running) {
            host = intent.getStringExtra("host") ?: ""
            port = intent.getIntExtra("port", 5555)
            myName = intent.getStringExtra("name") ?: "Misafir"
            running = true
            cleanOldFiles()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overlaychat:conn").apply {
                setReferenceCounted(false)
                acquire()
            }
            worker = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_NOT_STICKY
    }

    /** Ön plan bildirimi; aramadayken mikrofon türüyle. */
    private fun refreshForeground() {
        val inCall = callId.isNotEmpty()
        val b = Notification.Builder(this, CH_SERVICE)
            .setContentTitle("Overlay Chat")
            .setContentText(if (inCall) "Sesli aramada" else "Sohbete bağlı")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(openIntent(0, null))
            .setOngoing(true)
        if (inCall) {
            b.setCategory(Notification.CATEGORY_CALL)
            val hang = PendingIntent.getService(
                this, 11, Intent(this, ChatService::class.java).setAction(ACT_HANGUP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            @Suppress("DEPRECATION")
            b.addAction(0, "Aramayı bitir", hang)
        }
        val notif = b.build()
        val mic = inCall && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, notif, type)
            } else if (Build.VERSION.SDK_INT >= 30 && mic) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            try { startForeground(1, notif) } catch (e2: Exception) {}
        }
    }

    private fun cleanOldFiles() {
        try {
            val dir = File(filesDir, "rx")
            val limit = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ bağlantı döngüsü
    private fun loop() {
        while (running) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.keepAlive = true
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                val join = JSONObject().put("type", "join").put("name", myName).toString() + "\n"
                out?.write(join.toByteArray(Charsets.UTF_8))
                out?.flush()
                post(Line("sys", "", "Sunucuya bağlandın", now()))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 65536)
                while (running) {
                    val line = reader.readLine() ?: break
                    handle(line)
                }
            } catch (e: Exception) {
                if (running) post(Line("sys", "", "Bağlanılamadı: ${e.message ?: "hata"}", now()))
            } finally {
                try { socket?.close() } catch (e: Exception) {}
                socket = null
                out = null
                abortAllRx("Bağlantı koptu")
                ringHandler.post { endCallLocal(true) }
            }
            if (!running) break
            post(Line("sys", "", "Bağlantı yok, 5 sn sonra tekrar denenecek", now()))
            try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
        }
    }

    private fun safeName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[:*?\"<>|\\p{Cntrl}]"), "_").trim()
        if (n.length > 80) n = n.takeLast(80)
        return if (n.isEmpty()) "dosya" else n
    }

    private fun abortAllRx(why: String) {
        val items = synchronized(rx) { val l = rx.values.toList(); rx.clear(); l }
        for ((tr, line, fos) in items) failRx(tr, line, fos, why, "error")
    }

    private fun failRx(tr: Xfer, line: Line, fos: FileOutputStream?, why: String, state: String) {
        try { fos?.close() } catch (e: Exception) {}
        if (line.path.isNotEmpty()) try { File(line.path).delete() } catch (e: Exception) {}
        line.path = ""
        tr.error = why
        tr.state = state
    }

    // ------------------------------------------------------------ gelen mesajlar
    private fun handle(line: String) {
        try {
            val j = JSONObject(line)
            when (val t = j.optString("type")) {
                "msg" -> post(Line("msg", j.optString("name"), j.optString("text"), now()))
                "sys" -> post(Line("sys", "", j.optString("text"), now()))
                "voice" -> onVoice(j)
                "fstart" -> onFstart(j)
                "fchunk" -> onFchunk(j)
                "fend" -> onFend(j)
                "fabort" -> onFabort(j)
                "hello" -> { myId = j.optInt("id"); users = parseUsers(j.optJSONArray("users")); notifyCall() }
                "users" -> { users = parseUsers(j.optJSONArray("users")); notifyCall() }
                else -> if (t.startsWith("call_")) onCall(t, j)
            }
        } catch (e: Exception) {
        }
    }

    private fun parseUsers(a: JSONArray?): List<Pair<Int, String>> {
        val r = ArrayList<Pair<Int, String>>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            r.add(Pair(o.optInt("id"), o.optString("name")))
        }
        return r
    }

    private fun onVoice(j: JSONObject) {
        val bytes = Base64.decode(j.optString("data"), Base64.DEFAULT)
        val ext = j.optString("ext", "m4a").filter { it.isLetterOrDigit() }.ifEmpty { "m4a" }
        val dir = File(filesDir, "rx")
        dir.mkdirs()
        val f = File(dir, "${System.currentTimeMillis()}_ses.$ext")
        f.writeBytes(bytes)
        post(Line("file", j.optString("name"), "🎤 Sesli mesaj", now(), "voice", "ses.$ext", f.absolutePath, j.optInt("dur"), bytes.size.toLong()))
    }

    private fun onFstart(j: JSONObject) {
        val id = j.optString("id")
        val fname = safeName(j.optString("fname", "dosya"))
        val size = j.optLong("size")
        val tr = Xfer(id, fname, size, false)
        val ext = fname.substringAfterLast('.', "").lowercase()
        val kind = if (ext in AUDIO_EXT) "audio" else "file"
        val line = Line("file", j.optString("name"), if (kind == "audio") "🎵 $fname" else "📎 $fname", now(), kind, fname, "", 0, size, tr)
        try {
            val dir = File(filesDir, "rx")
            dir.mkdirs()
            if (dir.usableSpace < size + 64L * 1024 * 1024) throw java.io.IOException("Telefonda yeterli yer yok")
            val f = File(dir, "${System.currentTimeMillis()}_$fname")
            val os = FileOutputStream(f)
            line.path = f.absolutePath
            synchronized(rx) { rx[id] = Triple(tr, line, os) }
        } catch (e: Exception) {
            tr.error = e.message ?: "Dosya açılamadı"
            tr.state = "error"
        }
        activeX.add(line)
        post(line, false)
    }

    private fun onFchunk(j: JSONObject) {
        val item = synchronized(rx) { rx[j.optString("id")] } ?: return
        val (tr, line, fos) = item
        if (tr.state != "active") return
        try {
            val raw = Base64.decode(j.optString("data"), Base64.DEFAULT)
            fos.write(raw)
            tr.done += raw.size
            if (tr.done > tr.size) {
                synchronized(rx) { rx.remove(tr.id) }
                failRx(tr, line, fos, "Fazla veri geldi", "error")
            }
        } catch (e: Exception) {
            synchronized(rx) { rx.remove(tr.id) }
            failRx(tr, line, fos, "Yazılamadı: ${e.message}", "error")
        }
    }

    private fun onFend(j: JSONObject) {
        val item = synchronized(rx) { rx.remove(j.optString("id")) } ?: return
        val (tr, line, fos) = item
        try { fos.close() } catch (e: Exception) {}
        if (tr.done == tr.size) {
            tr.state = "done"
            if (!appVisible) notifyMessage(line.name, line.text)
        } else {
            failRx(tr, line, null, "Dosya eksik geldi", "error")
        }
    }

    private fun onFabort(j: JSONObject) {
        val item = synchronized(rx) { rx.remove(j.optString("id")) } ?: return
        failRx(item.first, item.second, item.third, "Gönderen iptal etti", "abort")
    }

    private fun post(l: Line, notify: Boolean = true) {
        log.add(l)
        while (log.size > 500) log.removeAt(0)
        listener?.invoke(l)
        if (notify && (l.kind == "msg" || l.kind == "file") && l.name != myName && !appVisible) {
            notifyMessage(l.name, l.text)
        }
    }

    private fun notifyMessage(who: String, what: String) {
        val lines = synchronized(recent) {
            recent.add("$who: $what")
            while (recent.size > 5) recent.removeAt(0)
            recent.toList()
        }
        val style = Notification.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = Notification.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(who)
            .setContentText(what)
            .setStyle(style)
            .setContentIntent(openIntent(0, null))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java).notify(MSG_NOTIF_ID, n)
    }

    // ------------------------------------------------------------ sesli arama
    private fun notifyCall() {
        callListener?.invoke()
    }

    private fun nameOf(id: Int): String =
        callJoined[id] ?: users.firstOrNull { it.first == id }?.second ?: "?"

    private fun onCall(t: String, j: JSONObject) {
        when (t) {
            "call_started", "call_state" -> {
                val cid = j.optString("cid")
                if (t == "call_state" && cid != callId) return
                val joined = LinkedHashMap<Int, String>()
                val a = j.optJSONArray("joined")
                if (a != null) for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    joined[o.optInt("id")] = o.optString("name")
                }
                val pend = HashSet<Int>()
                val p = j.optJSONArray("pending")
                if (p != null) for (i in 0 until p.length()) pend.add(p.optInt(i))
                ringHandler.post {
                    val wasActive = callId.isNotEmpty()
                    callId = cid
                    callGroup = j.optBoolean("group")
                    callJoined = joined
                    callPending = pend
                    if (joined.size >= 2 && callStartMs == 0L) callStartMs = System.currentTimeMillis()
                    if (joined.size < 2) callStartMs = 0L
                    if (!wasActive) beginCallAudioMode()
                    if (joined.containsKey(myId) && joined.size >= 2) startAudio()
                    refreshForeground()
                    notifyCall()
                }
            }
            "call_invite" -> {
                val cid = j.optString("cid")
                if (callId.isNotEmpty() || inviteCid.isNotEmpty()) {
                    sender.execute { writeRaw("{\"type\":\"call_decline\",\"cid\":\"$cid\"}") }
                    return
                }
                inviteCid = cid
                inviteName = j.optString("name")
                inviteGroup = j.optBoolean("group")
                ringHandler.post { startRing() }
                if (!appVisible) showIncomingNotification()
                notifyCall()
            }
            "call_cancel" -> {
                if (j.optString("cid") == inviteCid && inviteCid.isNotEmpty()) {
                    val who = inviteName
                    inviteCid = ""
                    ringHandler.post { stopRing() }
                    getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID)
                    post(Line("sys", "", "Cevapsız arama: $who", now()), false)
                    if (!appVisible) notifyMessage(who, "📞 Cevapsız arama")
                    notifyCall()
                }
            }
            "call_declined" -> {
                val nm = j.optString("name")
                val why = if (j.optString("reason") == "timeout") "cevap vermedi" else "aramayı reddetti"
                post(Line("sys", "", "$nm $why", now()), false)
            }
            "call_end" -> {
                val cid = j.optString("cid")
                val reason = j.optString("reason")
                when (reason) {
                    "busy" -> post(Line("sys", "", "Aranan kişi meşgul", now()), false)
                    "nobody" -> post(Line("sys", "", "Aranacak kimse yok", now()), false)
                    "busy-self" -> post(Line("sys", "", "Zaten bir aramadasın", now()), false)
                }
                if (cid.isEmpty() || cid == callId) {
                    ringHandler.post { endCallLocal(callId.isNotEmpty()) }
                }
            }
            "call_audio" -> {
                if (j.optString("cid") == callId) {
                    val d = j.optString("d")
                    audio?.feed(j.optInt("id"), Base64.decode(d, Base64.DEFAULT))
                }
            }
        }
    }

    private fun beginCallAudioMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = speaker
        } catch (e: Exception) {
        }
    }

    fun applySpeaker(v: Boolean) {
        speaker = v
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = v
        } catch (e: Exception) {
        }
        notifyCall()
    }

    private fun startAudio() {
        if (audio != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            post(Line("sys", "", "Mikrofon izni yok: sen konuşamazsın", now()), false)
        }
        refreshForeground()
        val a = CallAudio(this) { frame ->
            val cid = callId
            if (cid.isNotEmpty() && !muted) {
                try {
                    writeRaw("{\"type\":\"call_audio\",\"cid\":\"$cid\",\"d\":\"" +
                        Base64.encodeToString(frame, Base64.NO_WRAP) + "\"}")
                } catch (e: Exception) {
                }
            }
        }
        audio = a
        a.start()
    }

    fun doAccept() {
        val cid = inviteCid
        if (cid.isEmpty()) return
        inviteCid = ""
        stopRing()
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID)
        callId = cid
        callJoined = emptyMap()
        callPending = emptySet()
        beginCallAudioMode()
        refreshForeground()
        sender.execute { writeRaw("{\"type\":\"call_accept\",\"cid\":\"$cid\"}") }
        notifyCall()
    }

    fun doDecline() {
        val cid = inviteCid
        if (cid.isEmpty()) return
        inviteCid = ""
        stopRing()
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID)
        sender.execute { writeRaw("{\"type\":\"call_decline\",\"cid\":\"$cid\"}") }
        notifyCall()
    }

    fun doHangUp() {
        val cid = callId
        if (cid.isNotEmpty()) sender.execute { writeRaw("{\"type\":\"call_leave\",\"cid\":\"$cid\"}") }
        endCallLocal(true)
    }

    private fun endCallLocal(announce: Boolean) {
        val had = callId.isNotEmpty()
        try { audio?.stop() } catch (e: Exception) {}
        audio = null
        callId = ""
        callJoined = emptyMap()
        callPending = emptySet()
        callStartMs = 0L
        if (had) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {
            }
            if (announce) post(Line("sys", "", "Arama bitti", now()), false)
            refreshForeground()
        }
        notifyCall()
    }

    private fun startRing() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(this, uri)
            if (r != null) {
                if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
                ringtone = r
            }
        } catch (e: Exception) {
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0))
            vibrator = v
        } catch (e: Exception) {
        }
        ringHandler.removeCallbacks(ringStop)
        ringHandler.postDelayed(ringStop, RING_MS)
    }

    private fun stopRing() {
        ringHandler.removeCallbacks(ringStop)
        try { ringtone?.stop() } catch (e: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (e: Exception) {}
        vibrator = null
    }

    private fun showIncomingNotification() {
        val accept = openIntent(21, "accept")
        val decline = PendingIntent.getService(
            this, 22, Intent(this, ChatService::class.java).setAction(ACT_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$inviteName arıyor")
            .setContentText(if (inviteGroup) "Grup sesli araması" else "Sesli arama")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openIntent(20, "incoming"))
            .setFullScreenIntent(openIntent(23, "incoming"), true)
        @Suppress("DEPRECATION")
        b.addAction(0, "Kabul", accept)
        @Suppress("DEPRECATION")
        b.addAction(0, "Reddet", decline)
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIF_ID, b.build())
    }

    private fun openIntent(code: Int, extra: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (extra != null) putExtra("call", extra)
        }
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Bağlantı", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Mesajlar", NotificationManager.IMPORTANCE_HIGH))
        val call = NotificationChannel(CH_CALL, "Gelen aramalar", NotificationManager.IMPORTANCE_HIGH)
        call.setSound(null, null)
        nm.createNotificationChannel(call)
    }

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        worker?.interrupt()
        stopRing()
        try { audio?.stop() } catch (e: Exception) {}
        audio = null
        callId = ""
        inviteCid = ""
        callJoined = emptyMap()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
        }
        abortAllRx("Bağlantı kapandı")
        activeX.forEach { it.xfer?.cancel = true }
        wakeLock?.let { if (it.isHeld) it.release() }
        out = null
        inst = null
        users = emptyList()
        myId = 0
        super.onDestroy()
    }
}
