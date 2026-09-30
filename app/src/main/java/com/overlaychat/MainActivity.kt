package com.overlaychat

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0F0F14")
    private val BG2 = Color.parseColor("#1A1A23")
    private val FG = Color.parseColor("#E8E8EE")
    private val MUTED = Color.parseColor("#8B8B99")
    private val ACCENT = Color.parseColor("#FF3D5A")
    private val COLORS = intArrayOf(
        Color.parseColor("#FF6B6B"), Color.parseColor("#FECA57"), Color.parseColor("#48DBFB"),
        Color.parseColor("#1DD1A1"), Color.parseColor("#FF9FF3"), Color.parseColor("#54A0FF"),
        Color.parseColor("#F368E0"), Color.parseColor("#00D2D3"), Color.parseColor("#FF9F43"),
        Color.parseColor("#A29BFE")
    )

    private var tv: TextView? = null
    private var scroll: ScrollView? = null
    private var chatShown = false
    private var pending: Triple<String, Int, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ChatService.running) showChat() else showLogin()
    }

    override fun onStart() {
        super.onStart()
        ChatService.appVisible = true
        ChatService.clearRecent()
        getSystemService(NotificationManager::class.java).cancel(ChatService.MSG_NOTIF_ID)
        if (chatShown && tv != null) {
            renderAll()
            attachListener()
        }
    }

    override fun onStop() {
        ChatService.appVisible = false
        ChatService.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------ yardımcılar
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

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
        tv = null
        ChatService.listener = null
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "💬 Overlay Chat"
            setTextColor(FG)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "Bilgisayardaki sunucunun oda koduyla katıl"
            setTextColor(MUTED)
            textSize = 13f
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
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                startChat()
            }
        }

        root.addView(title)
        root.addView(sub, lp(4))
        root.addView(etName, lp(24))
        root.addView(etAddr, lp(12))
        root.addView(join, lp(20))
        setContentView(root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (pending != null) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bildirim izni verilmedi, bildirim gelmeyecek", Toast.LENGTH_LONG).show()
            }
            startChat()
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
        val leave = button("Ayrıl", BG2).apply { setTextColor(MUTED) }
        leave.setOnClickListener {
            stopService(Intent(this, ChatService::class.java))
            showLogin()
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(leave)

        val text = TextView(this).apply {
            setTextColor(FG)
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val sv = ScrollView(this).apply { addView(text) }
        tv = text
        scroll = sv

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG2)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val etMsg = edit("Mesaj yaz...").apply {
            setBackgroundColor(BG)
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val sendBtn = button("➤", ACCENT)
        val doSend = {
            val t = etMsg.text.toString().trim()
            if (t.isNotEmpty()) {
                ChatService.send(t)
                etMsg.setText("")
            }
        }
        sendBtn.setOnClickListener { doSend() }
        etMsg.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
        }
        row.addView(etMsg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sendBtn)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        renderAll()
        attachListener()
    }

    private fun attachListener() {
        ChatService.listener = { line -> runOnUiThread { appendLine(line) } }
    }

    private fun renderAll() {
        val t = tv ?: return
        t.text = ""
        ChatService.log.forEach { t.append(format(it)) }
        scrollDown()
    }

    private fun appendLine(line: Line) {
        tv?.append(format(line))
        scrollDown()
    }

    private fun scrollDown() {
        scroll?.post { scroll?.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun format(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        if (l.kind == "sys") {
            val s = sb.length
            sb.append(l.text + "\n")
            sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        sb.append("  " + l.text + "\n")
        return sb
    }
}
