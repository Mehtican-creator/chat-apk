package com.overlaychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class Line(val kind: String, val name: String, val text: String, val time: String)

/** Sohbet sunucusuna arka planda bağlı kalan ön plan servisi; mesaj gelince bildirim gösterir. */
class ChatService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MSG = "messages"
        const val MSG_NOTIF_ID = 2

        val log = CopyOnWriteArrayList<Line>()
        private val recent = ArrayList<String>()

        @Volatile var listener: ((Line) -> Unit)? = null
        @Volatile var appVisible = false
        @Volatile var running = false
        @Volatile var out: OutputStream? = null
        private val sender = Executors.newSingleThreadExecutor()

        fun clear() {
            log.clear()
            clearRecent()
        }

        fun clearRecent() {
            synchronized(recent) { recent.clear() }
        }

        fun send(text: String) {
            sender.execute {
                try {
                    val o = out ?: return@execute
                    val data = (JSONObject().put("type", "msg").put("text", text).toString() + "\n")
                        .toByteArray(Charsets.UTF_8)
                    synchronized(o) {
                        o.write(data)
                        o.flush()
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    private var host = ""
    private var port = 0
    private var myName = ""
    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val notif = Notification.Builder(this, CH_SERVICE)
            .setContentTitle("Overlay Chat")
            .setContentText("Sohbete bağlı")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openIntent())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }

        if (intent == null && !running) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null && !running) {
            host = intent.getStringExtra("host") ?: ""
            port = intent.getIntExtra("port", 5555)
            myName = intent.getStringExtra("name") ?: "Misafir"
            running = true
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

    private fun loop() {
        while (running) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.keepAlive = true
                socket = s
                out = s.getOutputStream()
                val join = JSONObject().put("type", "join").put("name", myName).toString() + "\n"
                out?.write(join.toByteArray(Charsets.UTF_8))
                out?.flush()
                post(Line("sys", "", "Sunucuya bağlandın", now()))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
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
            }
            if (!running) break
            post(Line("sys", "", "Bağlantı yok, 5 sn sonra tekrar denenecek", now()))
            try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
        }
    }

    private fun handle(line: String) {
        try {
            val j = JSONObject(line)
            when (j.optString("type")) {
                "msg" -> post(Line("msg", j.optString("name"), j.optString("text"), now()))
                "sys" -> post(Line("sys", "", j.optString("text"), now()))
            }
        } catch (e: Exception) {
        }
    }

    private fun post(l: Line) {
        log.add(l)
        while (log.size > 500) log.removeAt(0)
        listener?.invoke(l)
        if (l.kind == "msg" && l.name != myName && !appVisible) notifyMessage(l)
    }

    private fun notifyMessage(l: Line) {
        val lines = synchronized(recent) {
            recent.add("${l.name}: ${l.text}")
            while (recent.size > 5) recent.removeAt(0)
            recent.toList()
        }
        val style = Notification.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = Notification.Builder(this, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(l.name)
            .setContentText(l.text)
            .setStyle(style)
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java).notify(MSG_NOTIF_ID, n)
    }

    private fun openIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Bağlantı", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Mesajlar", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        out = null
        super.onDestroy()
    }
}
