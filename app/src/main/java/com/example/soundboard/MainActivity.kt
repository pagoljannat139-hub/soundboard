package com.example.soundboard

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

// ---------- ডাটা ও সেভ ----------
data class Clip(
    val id: String, val name: String, val audio: String,
    val image: String?, val startMs: Int, val endMs: Int
)

object Store {
    fun load(c: Context): MutableList<Clip> {
        val arr = JSONArray(c.getSharedPreferences("sb", 0).getString("clips", "[]"))
        val out = mutableListOf<Clip>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Clip(
                    o.getString("id"), o.getString("name"), o.getString("audio"),
                    o.optString("image", "").ifEmpty { null }, o.getInt("s"), o.getInt("e")
                )
            )
        }
        return out
    }

    fun save(c: Context, list: List<Clip>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("audio", it.audio)
                    .put("image", it.image ?: "").put("s", it.startMs).put("e", it.endMs)
            )
        }
        c.getSharedPreferences("sb", 0).edit().putString("clips", arr.toString()).apply()
    }

    fun game(c: Context): String? = c.getSharedPreferences("sb", 0).getString("game", null)
    fun gameName(c: Context): String? = c.getSharedPreferences("sb", 0).getString("gname", null)
    fun setGame(c: Context, pkg: String, name: String) =
        c.getSharedPreferences("sb", 0).edit().putString("game", pkg).putString("gname", name).apply()
}

// ---------- ছোট সাহায্যকারী ----------
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.round(color: Int, r: Int = 14) =
    GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

fun Context.btn(label: String, color: Int = 0xFF2E7D32.toInt()) = Button(this).apply {
    text = label
    setTextColor(Color.WHITE)
    isAllCaps = false
    background = round(color)
}

fun Context.copyIn(uri: Uri, ext: String): String {
    val f = File(filesDir, UUID.randomUUID().toString() + ext)
    contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    return f.absolutePath
}

fun Context.fileName(uri: Uri): String {
    try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i).substringBeforeLast('.')
        }
    } catch (e: Exception) { }
    return "ভয়েস"
}

fun thumb(path: String?): Bitmap? {
    if (path == null) return null
    return try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 })
    } catch (e: Exception) { null }
}

fun Context.thumbView(path: String?, size: Int): android.view.View {
    val bm = thumb(path)
    return if (bm != null) {
        ImageView(this).apply {
            setImageBitmap(bm)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(0xFF333333.toInt(), 10)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    } else {
        TextView(this).apply {
            text = "🎵"
            textSize = 20f
            gravity = Gravity.CENTER
            background = round(0xFF333333.toInt(), 10)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }
}

// ---------- মূল অ্যাপ ----------
class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var gameText: TextView
    private var pendingAudio: String? = null
    private var pendingName = "ভয়েস"
    private var pendingImage: String? = null
    private var imageBtn: Button? = null

    private fun lpm() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val root = ScrollView(this).apply { setBackgroundColor(0xFF121212.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }
        root.addView(col)

        col.addView(TextView(this).apply {
            text = "🎤 Soundboard"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        // গেম অংশ
        gameText = TextView(this).apply { textSize = 15f; setTextColor(0xFFBBBBBB.toInt()) }
        col.addView(gameText, lpm())
        col.addView(btn("➕ ফ্রি ফায়ার যোগ করুন", 0xFF37474F.toInt()).apply {
            setOnClickListener { chooseGame() }
        }, lpm())
        col.addView(btn("🎮 গেমে ঢুকুন (বাবল চালু হবে)").apply {
            setOnClickListener { startAll() }
        }, lpm())
        col.addView(btn("⏹ বাবল বন্ধ করুন", 0xFFB71C1C.toInt()).apply {
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }, lpm())

        // ভয়েস অংশ
        col.addView(TextView(this).apply {
            text = "আপনার ভয়েস"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(24), 0, 0)
        })
        col.addView(btn("➕ ভয়েস যোগ করুন").apply {
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
                }, 1)
            }
        }, lpm())
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateGame()
        refresh()
    }

    private fun updateGame() {
        val n = Store.gameName(this)
        gameText.text = if (n == null) "গেম: এখনো যোগ করা হয়নি" else "গেম: $n ✅"
    }

    private fun installed(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    private fun chooseGame() {
        val games = listOf("Free Fire" to "com.dts.freefireth", "Free Fire MAX" to "com.dts.freefiremax")
            .filter { installed(it.second) }
        if (games.isEmpty()) {
            Toast.makeText(this, "ফ্রি ফায়ার ইন্সটল করা নেই", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("গেম বাছুন")
            .setItems(games.map { it.first }.toTypedArray()) { _, i ->
                Store.setGame(this, games[i].second, games[i].first)
                updateGame()
            }.show()
    }

    private fun startAll() {
        val pkg = Store.game(this)
        if (pkg == null) {
            Toast.makeText(this, "আগে ফ্রি ফায়ার যোগ করুন", Toast.LENGTH_LONG).show(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "অনুমতি দিয়ে ফিরে এসে আবার চাপুন", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        try {
            if (req == 1) {
                pendingAudio = copyIn(uri, ".mp3")
                pendingName = fileName(uri)
                pendingImage = null
                showForm()
            } else if (req == 2) {
                pendingImage = copyIn(uri, ".img")
                imageBtn?.text = "✅ ছবি বাছা হয়েছে"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ফাইল নেওয়া যায়নি", Toast.LENGTH_LONG).show()
        }
    }

    private fun showForm() {
        val path = pendingAudio ?: return
        val durMs = try {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
            r.release(); d
        } catch (e: Exception) { 0 }
        val total = String.format(Locale.US, "%.1f", durMs / 1000.0)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val nameEt = EditText(this).apply { hint = "ভয়েসের নাম"; setText(pendingName) }
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val startEt = EditText(this).apply {
            hint = "কত সেকেন্ড থেকে শুরু"; inputType = dec; setText("0")
        }
        val endEt = EditText(this).apply {
            hint = "কত সেকেন্ড পর্যন্ত"; inputType = dec; setText(total)
        }
        imageBtn = Button(this).apply {
            text = "🖼️ ছবি বাছুন (ঐচ্ছিক)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, 2)
            }
        }
        box.addView(nameEt); box.addView(startEt); box.addView(endEt); box.addView(imageBtn)

        AlertDialog.Builder(this)
            .setTitle("ভয়েস যোগ (মোট $total সেকেন্ড)")
            .setView(box)
            .setPositiveButton("সেভ") { _, _ ->
                val s = (startEt.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                val e = endEt.text.toString().toDoubleOrNull() ?: 0.0
                val endMs = if (e > s) (e * 1000).toInt() else 0
                val list = Store.load(this)
                list.add(
                    Clip(
                        UUID.randomUUID().toString(),
                        nameEt.text.toString().ifBlank { "ভয়েস" },
                        path, pendingImage, (s * 1000).toInt(), endMs
                    )
                )
                Store.save(this, list)
                refresh()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val clips = Store.load(this)
        if (clips.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "এখনো কোনো ভয়েস নেই"
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(12), 0, 0)
            })
        }
        clips.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(0xFF1E1E1E.toInt())
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            row.addView(thumbView(c.image, 48))
            row.addView(TextView(this).apply {
                text = c.name
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(12), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "🗑"
                textSize = 20f
                setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener {
                    File(c.audio).delete()
                    c.image?.let { File(it).delete() }
                    Store.save(this@MainActivity, Store.load(this@MainActivity).filter { it.id != c.id })
                    refresh()
                }
            })
            listBox.addView(row, lpm())
        }
    }
}

// ---------- গেমের উপরে ভাসমান বাবল ----------
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private var panel: ScrollView? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopTask: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sb", "Soundboard", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            Notification.Builder(this, "sb")
                .setContentTitle("Soundboard চালু আছে")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        )

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2E7D32.toInt())
            }
        }
        root.addView(bubble, LinearLayout.LayoutParams(dp(52), dp(52)))

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
        try { wm.addView(root, lp) } catch (e: Exception) { stopSelf() }
    }

    private fun toggle() {
        panel?.let { root.removeView(it); panel = null; return }
        val clips = Store.load(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        if (clips.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "অ্যাপে গিয়ে ভয়েস যোগ করুন"
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        }
        clips.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            row.addView(thumbView(c.image, 40))
            row.addView(TextView(this).apply {
                text = c.name
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "👂"
                textSize = 20f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { play(c, true) }
            })
            row.addView(TextView(this).apply {
                text = "▶️"
                textSize = 20f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { play(c, false) }
            })
            list.addView(row)
        }
        val h = minOf(dp(300), maxOf(clips.size, 1) * dp(56) + dp(16))
        val sv = ScrollView(this).apply {
            background = round(0xEE1E1E1E.toInt())
            addView(list)
        }
        root.addView(sv, LinearLayout.LayoutParams(dp(280), h))
        panel = sv
    }

    // preview = true হলে শুধু কানের কাছে (ইয়ারপিস) আস্তে বাজে
    private fun play(c: Clip, preview: Boolean) {
        stop()
        try {
            val am = getSystemService(AudioManager::class.java)
            val mp = MediaPlayer()
            mp.setDataSource(c.audio)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (preview) {
                mp.setVolume(0.5f, 0.5f)
                if (Build.VERSION.SDK_INT >= 28) {
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        ?.let { mp.setPreferredDevice(it) }
                }
            } else {
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
                )
            }
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            player = mp
            val begin = c.startMs
            fun go() {
                mp.start()
                if (c.endMs > begin) {
                    val t = Runnable { stop() }
                    stopTask = t
                    handler.postDelayed(t, (c.endMs - begin).toLong())
                }
            }
            if (begin > 0) {
                mp.setOnSeekCompleteListener { go() }
                mp.seekTo(begin)
            } else go()
        } catch (e: Exception) { }
    }

    private fun stop() {
        stopTask?.let { handler.removeCallbacks(it) }
        stopTask = null
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    override fun onDestroy() {
        stop()
        try { wm.removeView(root) } catch (e: Exception) { }
        super.onDestroy()
    }
}
