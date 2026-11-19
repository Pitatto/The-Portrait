package com.noiraube.theportrait

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var portraitView: PortraitView
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>
    private val healthPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private var stepsToday: Long = 0L
    private var healthPermissionGranted = false
    private var healthStatus = "Touchez la montre pour connecter Santé Connect"

    private var mediaController: MediaController? = null
    private var mediaTitle = "Aucune musique en lecture"
    private var mediaArtist = ""
    private var mediaPlaying = false
    private var lastHealthRefreshAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val mediaRefresh = object : Runnable {
        override fun run() {
            refreshMediaSession()
            val now = System.currentTimeMillis()
            if (now - lastHealthRefreshAt >= 60_000L) {
                lastHealthRefreshAt = now
                refreshHealthConnect()
            }
            if (::portraitView.isInitialized) portraitView.invalidate()
            handler.postDelayed(this, if (mediaPlaying) 450L else 1800L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 22)
        window.navigationBarColor = Color.rgb(13, 17, 22)
        window.decorView.systemUiVisibility = 0

        healthPermissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            healthPermissionGranted = granted.containsAll(healthPermissions)
            healthStatus = if (healthPermissionGranted) "Santé Connect est relié" else "Autorisation non accordée"
            refreshHealthConnect()
        }

        portraitView = PortraitView(this)
        setContentView(portraitView)
    }

    override fun onResume() {
        super.onResume()
        refreshHealthConnect()
        refreshMediaSession()
        handler.removeCallbacks(mediaRefresh)
        handler.post(mediaRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(mediaRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(mediaRefresh)
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshHealthConnect() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            healthPermissionGranted = false
            healthStatus = "Santé Connect indisponible ou à mettre à jour"
            if (::portraitView.isInitialized) portraitView.invalidate()
            return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                healthPermissionGranted = granted.containsAll(healthPermissions)
                if (healthPermissionGranted) {
                    val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    val response = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                        )
                    )
                    stepsToday = response[StepsRecord.COUNT_TOTAL] ?: 0L
                    healthStatus = "Données de pas lues depuis Santé Connect"
                } else {
                    stepsToday = 0L
                    healthStatus = "Touchez la montre pour autoriser l'accès aux pas"
                }
            } catch (e: Exception) {
                healthStatus = "Lecture des pas indisponible"
            }
            if (::portraitView.isInitialized) portraitView.invalidate()
        }
    }

    private fun requestHealthPermission() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            Toast.makeText(this, "Santé Connect doit être installé ou mis à jour", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
            }
            return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(healthPermissions)) {
                    refreshHealthConnect()
                } else {
                    healthPermissionLauncher.launch(healthPermissions)
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Impossible d'ouvrir Santé Connect", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(this, MediaAccessListener::class.java)
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == target }
    }

    private fun refreshMediaSession() {
        if (!hasNotificationAccess()) {
            mediaController = null
            mediaTitle = "Autoriser l'accès au lecteur"
            mediaArtist = "YouTube Music et autres lecteurs"
            mediaPlaying = false
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = manager.getActiveSessions(ComponentName(this, MediaAccessListener::class.java))
            val selected = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull { it.metadata != null }
            mediaController = selected
            val metadata = selected?.metadata
            mediaTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: "Aucune musique en lecture"
            mediaArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            mediaPlaying = selected?.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: SecurityException) {
            mediaController = null
            mediaTitle = "Autoriser l'accès au lecteur"
            mediaArtist = "YouTube Music et autres lecteurs"
            mediaPlaying = false
        } catch (_: Exception) {
            mediaController = null
            mediaTitle = "Aucune musique en lecture"
            mediaArtist = ""
            mediaPlaying = false
        }
    }

    private fun askForMediaAccess() {
        if (hasNotificationAccess()) {
            refreshMediaSession()
            return
        }
        Toast.makeText(this, "Active The Portrait dans l'accès aux notifications", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun controlMusic(action: String) {
        if (!hasNotificationAccess()) {
            askForMediaAccess()
            return
        }
        val controls = mediaController?.transportControls
        if (controls == null) {
            Toast.makeText(this, "Aucun lecteur multimédia actif", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> if (mediaPlaying) controls.pause() else controls.play()
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
            }
            handler.postDelayed({ refreshMediaSession(); if (::portraitView.isInitialized) portraitView.invalidate() }, 250L)
        } catch (_: Exception) {
            Toast.makeText(this, "Le lecteur ne prend pas en charge cette commande", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSearchDialog() {
        val input = EditText(this).apply {
            hint = "Rechercher sur Google…"
                        setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(36, 24, 36, 24)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Recherche")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Rechercher", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")))
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showNotebookEditor(page: Int, index: Int, initial: String, onSave: (String?) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "Écrire ici…"
            setPadding(32, 24, 32, 24)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "Modifier l'entrée" else "Ajouter une entrée")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ -> onSave(input.text.toString().trim().takeIf { it.isNotEmpty() }) }
        if (index >= 0) builder.setNeutralButton("Supprimer") { _, _ -> onSave(null) }
        builder.show()
    }

    private fun launchApp(packageName: String, label: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) startActivity(intent)
            else Toast.makeText(this, "$label n'est pas installée", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir $label", Toast.LENGTH_SHORT).show()
        }
    }

    inner class PortraitView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val prefs = getSharedPreferences("the_portrait", Context.MODE_PRIVATE)
        private var decayed = prefs.getBoolean("decayed", false)
        private var watchOpen = false
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var scale = 1f
        private val goalSteps = 10000L
        private var bookPage = prefs.getInt("bookPage", 0).coerceIn(0, 3)
        private var bookScroll = 0
        private val pageTitles = listOf("Tâches journalières", "Liste de courses", "Notes", "À venir")
        private val bookPages = mutableListOf<MutableList<BookEntry>>()
        private val taskBounds = RectF(14f, 450f, 211f, 628f)
        private val watchBounds = RectF(286f, 450f, 406f, 625f)
        private val musicBounds = RectF(56f, 352f, 364f, 438f)
        private val searchBounds = RectF(16f, 638f, 404f, 683f)
        private val appBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val bitmap: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.musician_portrait) } catch (_: Exception) { null }
        private var bowPhase = 0f
        private val apps = listOf(
            AppItem("Discord", "com.discord", "D"),
            AppItem("Reddit", "com.reddit.frontpage", "R"),
            AppItem("Instagram", "com.instagram.android", "◎"),
            AppItem("Facebook", "com.facebook.katana", "f"),
            AppItem("YouTube", "com.google.android.youtube", "▶"),
            AppItem("ChatGPT", "com.openai.chatgpt", "✳"),
            AppItem("YT Music", "com.google.android.apps.youtube.music", "♫"),
            AppItem("Bandcamp", "com.bandcamp.android", "b"),
            AppItem("Outlook", "com.microsoft.office.outlook", "✉"),
            AppItem("Vinted", "com.vinted", "V"),
            AppItem("Chrome", "com.android.chrome", "◉"),
            AppItem("Google Fit", "com.google.android.apps.fitness", "♥")
        )

        init { loadNotebook() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scale = width / 420f
            canvas.save()
            canvas.scale(scale, scale)
            drawBackdrop(canvas)
            drawHeader(canvas)
            drawPortrait(canvas)
            drawMusicPanel(canvas)
            drawNotebook(canvas)
            drawPocketWatch(canvas)
            drawSearch(canvas)
            drawApps(canvas)
            drawOrnament(canvas)
            if (watchOpen) drawOpenWatch(canvas)
            canvas.restore()
        }

        private fun color(hex: String) = Color.parseColor(hex)

        private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, tint: Int, italic: Boolean = false, bold: Boolean = false) {
            p.shader = null; p.colorFilter = null; p.alpha = 255; p.color = tint; p.textSize = size
            p.typeface = Typeface.create("serif", when { bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
            p.style = Paint.Style.FILL
            c.drawText(value, x, y, p)
        }

        private fun centered(c: Canvas, value: String, cx: Float, y: Float, size: Float, tint: Int, italic: Boolean = false) {
            p.shader = null; p.colorFilter = null; p.alpha = 255; p.color = tint; p.textSize = size
            p.typeface = if (italic) Typeface.create("serif", Typeface.ITALIC) else Typeface.create("serif", Typeface.NORMAL)
            p.style = Paint.Style.FILL
            c.drawText(value, cx - p.measureText(value) / 2f, y, p)
        }

        private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, width: Float = 1f) {
            p.shader = null; p.colorFilter = null; p.alpha = 255; p.color = tint; p.strokeWidth = width
            p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
            c.drawLine(x1, y1, x2, y2, p)
            p.style = Paint.Style.FILL
        }

        private fun rounded(c: Canvas, r: RectF, radius: Float, fill: Int, stroke: Int? = null, strokeWidth: Float = 1.2f) {
            p.shader = null; p.colorFilter = null; p.alpha = 255; p.color = fill; p.style = Paint.Style.FILL
            c.drawRoundRect(r, radius, radius, p)
            if (stroke != null) {
                p.color = stroke; p.strokeWidth = strokeWidth; p.style = Paint.Style.STROKE
                c.drawRoundRect(r, radius, radius, p); p.style = Paint.Style.FILL
            }
        }

        private fun drawBackdrop(c: Canvas) {
            val top = if (decayed) color("#0A0B0C") else color("#101923")
            val mid = if (decayed) color("#282522") else color("#28323C")
            val bottom = if (decayed) color("#101112") else color("#101820")
            p.shader = LinearGradient(0f, 0f, 420f, 850f, intArrayOf(top, mid, bottom), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 420f, 850f, p); p.shader = null
            for (i in 0..12) {
                val x = i * 38f - 22f
                p.color = color(if (decayed) (if (i % 2 == 0) "#302B28" else "#151719") else (if (i % 2 == 0) "#26313A" else "#17212A"))
                p.strokeWidth = 17f + (i % 3) * 5f; p.style = Paint.Style.STROKE
                c.drawArc(x, -90f, x + 100f, 700f, 95f, 175f, false, p)
            }
            p.style = Paint.Style.FILL
            rounded(c, RectF(294f, 35f, 408f, 260f), 55f, if (decayed) color("#171919") else color("#202C37"), color("#68747E"))
            p.shader = RadialGradient(350f, 112f, 97f, if (decayed) color("#888176") else color("#B9C2C8"), color("#35424E"), Shader.TileMode.CLAMP)
            c.drawCircle(350f, 112f, 78f, p); p.shader = null
            line(c, 350f, 35f, 350f, 257f, color("#6C7781"), 2f)
            line(c, 294f, 184f, 407f, 184f, color("#6C7781"), 2f)
            for (i in 0..5) {
                val x = 290f + i * 23f
                p.color = color(if (decayed) "#101413" else "#101820")
                val path = Path().apply { moveTo(x - 15f, 342f); lineTo(x, 262f - (i % 3) * 17f); lineTo(x + 15f, 342f); close() }
                c.drawPath(path, p)
            }
            if (decayed) {
                for (i in 0..5) line(c, 22f + i * 67f, 45f, 60f + i * 54f, 208f + (i % 2) * 60f, color("#625C53"), .8f)
            }
        }

        private fun drawHeader(c: Canvas) {
            val ivory = if (decayed) color("#B8AA98") else color("#D3D7D8")
            val day = SimpleDateFormat("EEEE", Locale.FRENCH).format(Date()).replaceFirstChar { it.uppercase() }
            val date = SimpleDateFormat("dd MMMM", Locale.FRENCH).format(Date())
            text(c, day, 24f, 62f, 29f, ivory, true)
            text(c, date, 25f, 96f, 21f, ivory, true)
            line(c, 24f, 111f, 236f, 111f, color("#8C969E"), 1f)
            text(c, "8°  ·  ciel couvert", 26f, 138f, 15f, ivory)
        }

        private fun drawPortrait(c: Canvas) {
            val frame = RectF(126f, 147f, 294f, 342f)
            rounded(c, frame, 75f, color(if (decayed) "#292621" else "#222C35"), color(if (decayed) "#81776A" else "#B2BCC2"), 2f)
            rounded(c, RectF(132f, 153f, 288f, 336f), 69f, color(if (decayed) "#51483F" else "#48535D"), color("#65717B"))
            if (bitmap != null) {
                val oval = Path().apply { addOval(RectF(137f, 158f, 283f, 331f), Path.Direction.CW) }
                c.save()
                c.clipPath(oval)
                p.shader = null; p.alpha = 255
                if (decayed) {
                    val matrix = ColorMatrix().apply { setSaturation(0.22f) }
                    p.colorFilter = ColorMatrixColorFilter(matrix)
                    p.alpha = 190
                } else {
                    p.colorFilter = null
                }
                c.drawBitmap(bitmap, null, RectF(137f, 158f, 283f, 331f), p)
                p.colorFilter = null; p.alpha = 255
                if (decayed) {
                    p.color = 0x55362D24; c.drawRect(137f, 158f, 283f, 331f, p)
                    line(c, 160f, 167f, 199f, 237f, color("#B0A08A"), 1.1f)
                    line(c, 199f, 237f, 176f, 318f, color("#A99B89"), .9f)
                    line(c, 253f, 176f, 221f, 247f, color("#81776D"), .9f)
                }
                // A restrained moving bow-stroke accent while music is playing.
                val shift = if (mediaPlaying) sin(System.currentTimeMillis() / 180.0).toFloat() * 7f else 0f
                line(c, 237f + shift, 174f, 190f + shift, 312f, if (mediaPlaying) color("#E3D3B7") else color("#B6A992"), 1.35f)
                c.restore()
            } else {
                text(c, "♬", 183f, 266f, 54f, ivoryColor())
            }
            // Fine oval engraving.
            p.color = color("#A5AEB4"); p.style = Paint.Style.STROKE; p.strokeWidth = 1f
            c.drawOval(RectF(133f, 154f, 287f, 335f), p); p.style = Paint.Style.FILL
            centered(c, "THE PORTRAIT", 210f, 355f, 10f, ivoryColor(), true)
        }

        private fun drawMusicPanel(c: Canvas) {
            rounded(c, musicBounds, 12f, color(if (decayed) "#282522" else "#18222C"), color(if (decayed) "#81766A" else "#84909A"))
            val title = mediaTitle.take(30)
            val artist = mediaArtist.take(38)
            centered(c, title, 210f, 377f, 13f, ivoryColor(), true)
            centered(c, artist.ifBlank { if (hasNotificationAccess()) "" else "Autorisation requise" }, 210f, 395f, 10f, color("#AFA99F"), true)
            drawMiniViolin(c, 91f, 410f, mediaPlaying)
            centered(c, "|◀", 153f, 423f, 14f, ivoryColor())
            centered(c, if (mediaPlaying) "Ⅱ" else "▶", 210f, 425f, 19f, ivoryColor())
            centered(c, "▶|", 266f, 423f, 14f, ivoryColor())
            line(c, 126f, 431f, 294f, 431f, color("#776F63"), .7f)
        }

        private fun drawMiniViolin(c: Canvas, cx: Float, cy: Float, playing: Boolean) {
            p.color = color(if (decayed) "#776A59" else "#B59B77")
            val path = Path().apply {
                moveTo(cx, cy - 14f); cubicTo(cx - 12f, cy - 20f, cx - 11f, cy - 7f, cx - 5f, cy)
                cubicTo(cx - 11f, cy + 8f, cx - 8f, cy + 17f, cx, cy + 13f)
                cubicTo(cx + 8f, cy + 17f, cx + 11f, cy + 8f, cx + 5f, cy)
                cubicTo(cx + 11f, cy - 7f, cx + 12f, cy - 20f, cx, cy - 14f); close()
            }
            c.drawPath(path, p)
            line(c, cx, cy - 12f, cx, cy + 12f, color("#3A3028"), 1f)
            val shift = if (playing) sin(System.currentTimeMillis() / 180.0).toFloat() * 5f else 0f
            line(c, cx - 13f + shift, cy - 15f, cx + 14f + shift, cy + 15f, color("#D2C4AA"), 1f)
        }

        private fun drawNotebook(c: Canvas) {
            rounded(c, taskBounds, 9f, color(if (decayed) "#494138" else "#C7BDAE"), color(if (decayed) "#8B7F70" else "#E4DACB"), 1.4f)
            // page grain and spine
            line(c, 28f, 463f, 28f, 616f, color(if (decayed) "#786D5F" else "#9B8D7A"), 2f)
            text(c, pageTitles[bookPage], 40f, 476f, 16f, color(if (decayed) "#D7C9B5" else "#393A3A"), true)
            centered(c, "‹", 27f, 476f, 22f, color("#777064"))
            centered(c, "›", 195f, 476f, 22f, color("#777064"))
            line(c, 36f, 484f, 194f, 484f, color("#8F8577"), .8f)
            val page = bookPages[bookPage]
            val visible = page.drop(bookScroll).take(4)
            visible.forEachIndexed { visibleIndex, entry ->
                val actualIndex = bookScroll + visibleIndex
                val y = 509f + visibleIndex * 25f
                rounded(c, RectF(36f, y - 12f, 50f, y + 2f), 2f, Color.TRANSPARENT, color(if (decayed) "#9A8F7E" else "#55534D"), 1f)
                if (entry.done && bookPage < 2) {
                    line(c, 38f, y - 5f, 43f, y, color("#853D42"), 2f)
                    line(c, 43f, y, 52f, y - 13f, color("#853D42"), 2f)
                }
                val label = entry.text.take(20)
                text(c, label, 60f, y, 12.5f, color(if (decayed) "#C7B8A3" else "#393B3B"), true)
                if (decayed && visibleIndex % 2 == 1) line(c, 60f, y - 4f, 150f, y - 3f, color("#938575"), .7f)
            }
            text(c, "+ Ajouter…", 60f, 610f, 11f, color(if (decayed) "#B9A991" else "#4A4844"), true)
            text(c, "✦", 184f, 614f, 12f, color(if (decayed) "#817666" else "#82796E"))
            if (bookScroll > 0) text(c, "↑", 181f, 500f, 10f, color("#777064"))
            if (bookScroll + 4 < page.size) text(c, "↓", 181f, 604f, 10f, color("#777064"))
        }

        private fun drawPocketWatch(c: Canvas) {
            val cx = 346f; val cy = 526f
            p.color = color(if (decayed) "#6A6258" else "#AEB8BE"); p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
            val chain = Path().apply { moveTo(cx, 457f); cubicTo(300f, 430f, 390f, 425f, cx, 468f) }
            c.drawPath(chain, p); p.style = Paint.Style.FILL
            p.color = color(if (decayed) "#655A4F" else "#AEB8BE"); c.drawCircle(cx, cy, 51f, p)
            p.color = color(if (decayed) "#1D1D1B" else "#29343E"); c.drawCircle(cx, cy, 45f, p)
            p.color = color(if (decayed) "#968775" else "#C4CCD0"); p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f
            c.drawCircle(cx, cy, 40f, p); p.style = Paint.Style.FILL
            centered(c, "✣", cx, cy + 7f, 21f, color(if (decayed) "#A99A83" else "#CDD3D6"))
            centered(c, "PAS", cx, 594f, 8f, color(if (decayed) "#A99C8B" else "#C1C9CD"), true)
            if (decayed) {
                line(c, 311f, 492f, 381f, 558f, color("#776D61"), 1.1f)
                line(c, 381f, 492f, 315f, 558f, color("#625D55"), .9f)
            }
        }

        private fun drawSearch(c: Canvas) {
            rounded(c, searchBounds, 8f, color(if (decayed) "#51483E" else "#C9BFAF"), color(if (decayed) "#8D8071" else "#E1D8C8"))
            centered(c, "G", 47f, 668f, 23f, color(if (decayed) "#D3C4AF" else "#3A3B3A"), true)
            text(c, "Rechercher sur Google…", 72f, 667f, 13f, color(if (decayed) "#D1C4B1" else "#4A4945"), true)
            centered(c, "⌕", 380f, 669f, 24f, color(if (decayed) "#D3C4AF" else "#393A3A"))
        }

        private fun drawApps(c: Canvas) {
            appBounds.clear()
            val cols = 6
            val centers = floatArrayOf(37f, 106f, 175f, 244f, 313f, 382f)
            apps.forEachIndexed { i, app ->
                val row = i / cols
                val col = i % cols
                val cx = centers[col]
                val cy = if (row == 0) 718f else 798f
                val rect = RectF(cx - 21f, cy - 21f, cx + 21f, cy + 21f)
                rounded(c, rect, 7f, color(if (decayed) "#252624" else "#1A252F"), color(if (decayed) "#756B5F" else "#9BA7AF"), 1.15f)
                centered(c, app.glyph, cx, cy + 8f, 23f, color(if (decayed) "#8E8578" else "#C4CDD1"))
                if (decayed) {
                    line(c, cx - 15f, cy - 15f, cx + 15f, cy + 15f, color("#6C6258"), .9f)
                    line(c, cx + 15f, cy - 15f, cx - 15f, cy + 15f, color("#514D46"), .7f)
                }
                centered(c, app.label, cx, cy + 36f, 9.3f, ivoryColor())
                appBounds.add(rect to app)
            }
        }

        private fun drawOrnament(c: Canvas) {
            line(c, 28f, 845f, 392f, 845f, color("#616E78"), .8f)
            centered(c, "✥", 210f, 841f, 12f, color(if (decayed) "#786C5D" else "#99A5AB"))
        }

        private fun drawOpenWatch(c: Canvas) {
            p.color = 0xD9080B0E.toInt(); c.drawRect(0f, 0f, 420f, 850f, p)
            rounded(c, RectF(48f, 190f, 372f, 650f), 28f, color(if (decayed) "#2B2722" else "#1C2731"), color(if (decayed) "#A0927F" else "#B9C3C8"), 2f)
            centered(c, "MONTRE À GOUSSET", 210f, 231f, 13f, ivoryColor(), true)
            p.color = color(if (decayed) "#8D806E" else "#BFC8CD"); p.style = Paint.Style.STROKE; p.strokeWidth = 5f
            c.drawCircle(210f, 396f, 119f, p); p.style = Paint.Style.FILL
            p.color = color(if (decayed) "#171817" else "#2D3944"); c.drawCircle(210f, 396f, 109f, p)
            p.color = color(if (decayed) "#B19D7D" else "#D2D7D8"); p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f
            c.drawCircle(210f, 396f, 98f, p); p.style = Paint.Style.FILL
            for (i in 0..11) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                val x = 210f + cos(angle).toFloat() * 84f
                val y = 396f + sin(angle).toFloat() * 84f
                val roman = when (i) { 0 -> "XII"; 3 -> "III"; 6 -> "VI"; 9 -> "IX"; else -> "·" }
                centered(c, roman, x, y + 5f, 12f, ivoryColor())
            }
            val now = java.util.Calendar.getInstance()
            val minute = now.get(java.util.Calendar.MINUTE)
            val hour = now.get(java.util.Calendar.HOUR)
            val ha = Math.toRadians(hour * 30.0 + minute * .5 - 90.0)
            val ma = Math.toRadians(minute * 6.0 - 90.0)
            line(c, 210f, 396f, 210f + cos(ha).toFloat() * 49f, 396f + sin(ha).toFloat() * 49f, ivoryColor(), 3f)
            line(c, 210f, 396f, 210f + cos(ma).toFloat() * 75f, 396f + sin(ma).toFloat() * 75f, color("#B9C4CB"), 1.7f)
            p.color = ivoryColor(); c.drawCircle(210f, 396f, 4f, p)
            val numberFormat = NumberFormat.getIntegerInstance(Locale.FRANCE)
            centered(c, "${numberFormat.format(stepsToday)} pas", 210f, 548f, 24f, ivoryColor(), true)
            centered(c, "Objectif : ${numberFormat.format(goalSteps)}", 210f, 577f, 12f, color("#C2B7A6"), true)
            val progress = (stepsToday.toFloat() / goalSteps.toFloat()).coerceIn(0f, 1f)
            rounded(c, RectF(102f, 596f, 318f, 604f), 4f, color("#3E4345"))
            rounded(c, RectF(102f, 596f, 102f + 216f * progress, 604f), 4f, color("#8B4148"))
            if (!healthPermissionGranted) {
                centered(c, "CONNECTER SANTÉ CONNECT", 210f, 626f, 10f, color("#D5C7B2"), true)
            } else {
                centered(c, "DONNÉES DU JOUR", 210f, 626f, 10f, color("#D5C7B2"), true)
            }
            centered(c, "TOUCHER POUR FERMER", 210f, 680f, 10f, color("#D5C7B2"), true)
            centered(c, healthStatus.take(48), 210f, 700f, 8f, color("#A9A398"), true)
        }

        private fun ivoryColor() = color(if (decayed) "#B4AA9A" else "#D0D6D9")

        private fun loadNotebook() {
            val defaults = listOf(
                listOf("Rangement", "Courses", "Courrier", "Préparer la semaine"),
                listOf("Pain", "Lait", "Œufs", "Légumes"),
                listOf("Idées", "À retenir"),
                listOf("Rendez-vous", "Appels à faire")
            )
            val jsonText = prefs.getString("notebook", null)
            try {
                val root = if (jsonText != null) JSONArray(jsonText) else JSONArray()
                for (page in 0..3) {
                    val entries = mutableListOf<BookEntry>()
                    if (page < root.length()) {
                        val array = root.optJSONArray(page) ?: JSONArray()
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i) ?: continue
                            val label = obj.optString("text", "").trim()
                            if (label.isNotEmpty()) entries.add(BookEntry(label, obj.optBoolean("done", false)))
                        }
                    }
                    if (jsonText == null || page >= root.length()) {
                        defaults[page].forEach { entries.add(BookEntry(it, false)) }
                    }
                    bookPages.add(entries)
                }
            } catch (_: Exception) {
                defaults.forEach { page -> bookPages.add(page.map { BookEntry(it, false) }.toMutableList()) }
            }
        }

        private fun saveNotebook() {
            val root = JSONArray()
            bookPages.forEach { page ->
                val array = JSONArray()
                page.forEach { entry -> array.put(JSONObject().put("text", entry.text).put("done", entry.done)) }
                root.put(array)
            }
            prefs.edit().putString("notebook", root.toString()).putInt("bookPage", bookPage).apply()
        }

        private fun editBookEntry(index: Int) {
            val page = bookPages[bookPage]
            val initial = if (index >= 0 && index < page.size) page[index].text else ""
            showNotebookEditor(bookPage, index, initial) { value ->
                if (value == null) {
                    if (index in page.indices) page.removeAt(index)
                } else if (index in page.indices) {
                    page[index].text = value
                } else {
                    page.add(BookEntry(value, false))
                }
                bookScroll = bookScroll.coerceAtMost(max(0, page.size - 4))
                saveNotebook(); invalidate()
            }
        }

        private fun handleNotebookTap(x: Float, y: Float, duration: Long, dx: Float, dy: Float) {
            if (abs(dx) > 32f) {
                bookPage = (bookPage + if (dx < 0) 1 else -1).coerceIn(0, 3)
                bookScroll = 0; saveNotebook(); invalidate(); return
            }
            if (abs(dy) > 22f) {
                val maxScroll = max(0, bookPages[bookPage].size - 4)
                bookScroll = (bookScroll + if (dy < 0f) 1 else -1).coerceIn(0, maxScroll)
                invalidate(); return
            }
            if (y < 488f) {
                bookPage = (bookPage + 1) % 4; bookScroll = 0; saveNotebook(); invalidate(); return
            }
            if (y >= 596f) { editBookEntry(-1); return }
            val visibleIndex = ((y - 509f) / 25f).toInt()
            val index = visibleIndex + bookScroll
            val page = bookPages[bookPage]
            if (index in page.indices) {
                if (duration > 550L || bookPage >= 2) editBookEntry(index)
                else {
                    page[index].done = !page[index].done
                    saveNotebook(); invalidate()
                }
            }
        }

        private fun drawSearchBarAndOpen() { launchSearchDialog() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x / scale
            val y = event.y / scale
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; downAt = event.eventTime
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = x - downX
                    val dy = y - downY
                    val duration = event.eventTime - downAt
                    if (watchOpen) {
                        if (y > 675f || y < 250f) {
                            watchOpen = false
                        } else if (!healthPermissionGranted) {
                            requestHealthPermission()
                        } else {
                            watchOpen = false
                        }
                        invalidate(); return true
                    }
                    if (taskBounds.contains(downX, downY) || taskBounds.contains(x, y)) {
                        handleNotebookTap(x, y, duration, dx, dy)
                        return true
                    }
                    if (musicBounds.contains(x, y)) {
                        if (!hasNotificationAccess()) askForMediaAccess()
                        else if (x < 176f) controlMusic("previous")
                        else if (x > 245f) controlMusic("next")
                        else controlMusic("toggle")
                        invalidate(); return true
                    }
                    if (watchBounds.contains(x, y)) { watchOpen = true; refreshHealthConnect(); invalidate(); return true }
                    if (searchBounds.contains(x, y)) { drawSearchBarAndOpen(); return true }
                    appBounds.firstOrNull { it.first.contains(x, y) }?.let {
                        launchApp(it.second.packageName, it.second.label); return true
                    }
                    if (abs(dx) > 55f) {
                        decayed = dx < 0f
                        prefs.edit().putBoolean("decayed", decayed).apply()
                        invalidate(); return true
                    }
                    return true
                }
            }
            return true
        }
    }

    private data class BookEntry(var text: String, var done: Boolean)
    private data class AppItem(val label: String, val packageName: String, val glyph: String)
}

class MediaAccessListener : android.service.notification.NotificationListenerService()

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = android.widget.TextView(this).apply {
            text = "The Portrait lit uniquement le nombre de pas du jour depuis Santé Connect afin de l'afficher dans la montre à gousset. Aucune donnée de santé n'est écrite ni envoyée à un serveur. Vous pouvez retirer cette autorisation à tout moment dans Santé Connect."
            textSize = 18f
            setPadding(32, 48, 32, 48)
        }
        setContentView(message)
    }
}
