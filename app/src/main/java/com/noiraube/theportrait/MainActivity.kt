package com.noiraube.theportrait

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
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
    private var stepsToday = 0L
    private var healthPermissionGranted = false
    private var mediaController: MediaController? = null
    private var mediaTitle = "YouTube Music"
    private var mediaArtist = "Touchez pour connecter le lecteur"
    private var mediaPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private val mediaRefresh = object : Runnable {
        override fun run() {
            refreshMediaSession()
            if (::portraitView.isInitialized) portraitView.invalidate()
            handler.postDelayed(this, if (mediaPlaying) 600L else 1800L)
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

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::portraitView.isInitialized && portraitView.closeOverlay()) return
        super.onBackPressed()
    }

    private fun refreshHealthConnect() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            healthPermissionGranted = false
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
                } else {
                    stepsToday = 0L
                }
            } catch (_: Exception) {
                // Keep the last known value if Health Connect is temporarily unavailable.
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
                if (client.permissionController.getGrantedPermissions().containsAll(healthPermissions)) {
                    refreshHealthConnect()
                } else {
                    healthPermissionLauncher.launch(healthPermissions)
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Impossible d'ouvrir Santé Connect", Toast.LENGTH_SHORT).show()
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
            mediaTitle = "YouTube Music"
            mediaArtist = "Touchez pour connecter le lecteur"
            mediaPlaying = false
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, MediaAccessListener::class.java))
            val selected = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.metadata != null }
            mediaController = selected
            mediaTitle = selected?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: "Aucun morceau en lecture"
            mediaArtist = selected?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: selected?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            mediaPlaying = selected?.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: Exception) {
            mediaController = null
            mediaTitle = "YouTube Music"
            mediaArtist = "Touchez pour connecter le lecteur"
            mediaPlaying = false
        }
    }

    private fun askForMediaAccess() {
        Toast.makeText(this, "Autorise The Portrait dans Accès aux notifications", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openMusic() {
        if (!hasNotificationAccess()) {
            askForMediaAccess()
            return
        }
        val controls = mediaController?.transportControls
        if (controls == null) {
            launchApp("com.google.android.apps.youtube.music", "YouTube Music")
            return
        }
        try {
            if (mediaPlaying) controls.pause() else controls.play()
            handler.postDelayed({ refreshMediaSession(); if (::portraitView.isInitialized) portraitView.invalidate() }, 300L)
        } catch (_: Exception) {
            launchApp("com.google.android.apps.youtube.music", "YouTube Music")
        }
    }

    private fun controlMusic(action: String) {
        if (!hasNotificationAccess()) {
            askForMediaAccess()
            return
        }
        val controls = mediaController?.transportControls
        if (controls == null) {
            launchApp("com.google.android.apps.youtube.music", "YouTube Music")
            return
        }
        try {
            when (action) {
                "previous" -> controls.skipToPrevious()
                "next" -> controls.skipToNext()
                "toggle" -> if (mediaPlaying) controls.pause() else controls.play()
            }
            handler.postDelayed({ refreshMediaSession(); if (::portraitView.isInitialized) portraitView.invalidate() }, 300L)
        } catch (_: Exception) {
            launchApp("com.google.android.apps.youtube.music", "YouTube Music")
        }
    }

    private fun launchSearchDialog() {
        val input = EditText(this).apply {
            hint = "Rechercher…"
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

    private fun showNotebookEditor(initial: String, onSave: (String?) -> Unit, canDelete: Boolean) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "Écrire ici…"
            setPadding(32, 24, 32, 24)
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            textSize = 20f
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (canDelete) "Modifier" else "Ajouter")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ -> onSave(input.text.toString().trim().takeIf { it.isNotEmpty() }) }
        if (canDelete) builder.setNeutralButton("Supprimer") { _, _ -> onSave(null) }
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

    private fun openMessages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        } catch (_: Exception) {
            Toast.makeText(this, "Aucune application de messagerie trouvée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDialer() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        } catch (_: Exception) {
            Toast.makeText(this, "Téléphone indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    inner class PortraitView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val prefs = getSharedPreferences("the_portrait", Context.MODE_PRIVATE)
        private var decayed = prefs.getBoolean("decayed", false)
        private var watchOpen = false
        private var bookOpen = false
        private var bookPage = prefs.getInt("bookPage", 0).coerceIn(0, 3)
        private var bookScroll = 0
        private var drawerProgress = 0f
        private var drawerPage = 0
        private var drawerAnimator: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var scaleFactor = 1f
        private var designHeight = 850f
        private val goalSteps = 10000L
        private val pageTitles = listOf("Tâches journalières", "Liste de courses", "Notes", "À venir")
        private val bookPages = mutableListOf<MutableList<BookEntry>>()
        private val taskBounds = RectF(14f, 450f, 211f, 628f)
        private val watchBounds = RectF(286f, 450f, 406f, 625f)
        private val musicBounds = RectF(56f, 352f, 364f, 438f)
        private val searchBounds = RectF(16f, 638f, 404f, 683f)
        private val appBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val drawerAppBounds = mutableListOf<Pair<RectF, AppItem>>()
        private var messageBounds = RectF()
        private var dialBounds = RectF()
        private var bowPhase = 0f
        private val intactPortrait: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.musician_portrait) } catch (_: Exception) { null }
        private val undeadPortrait: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.musician_portrait_decayed) } catch (_: Exception) { null }
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
            AppItem("Vinted", "fr.vinted", "V"),
            AppItem("Chrome", "com.android.chrome", "◉"),
            AppItem("Google Fit", "com.google.android.apps.fitness", "♥")
        )

        init { loadNotebook() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scaleFactor = width / 420f
            designHeight = height / scaleFactor
            canvas.save()
            canvas.scale(scaleFactor, scaleFactor)
            drawBackdrop(canvas)
            drawHeader(canvas)
            drawPortrait(canvas)
            drawMusicPanel(canvas)
            drawNotebookPreview(canvas)
            drawPocketWatch(canvas)
            drawSearch(canvas)
            drawApps(canvas)
            drawCurtain(canvas)
            if (watchOpen) drawOpenWatch(canvas)
            if (bookOpen) drawOpenBook(canvas)
            if (drawerProgress > 0f) drawAppDrawer(canvas)
            canvas.restore()
        }

        private fun color(hex: String) = Color.parseColor(hex)

        private fun setText(value: String, size: Float, tint: Int, italic: Boolean = false, handwritten: Boolean = false) {
            paint.shader = null; paint.colorFilter = null; paint.alpha = 255; paint.color = tint
            paint.textSize = size
            paint.typeface = when {
                handwritten -> Typeface.create("cursive", Typeface.NORMAL)
                italic -> Typeface.create("serif", Typeface.ITALIC)
                else -> Typeface.create("serif", Typeface.NORMAL)
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawText(c: Canvas, value: String, x: Float, y: Float, size: Float, tint: Int, italic: Boolean = false, handwritten: Boolean = false) {
            setText(value, size, tint, italic, handwritten)
            c.drawText(value, x, y, paint)
        }

        private fun centered(c: Canvas, value: String, cx: Float, y: Float, size: Float, tint: Int, italic: Boolean = false, handwritten: Boolean = false) {
            setText(value, size, tint, italic, handwritten)
            c.drawText(value, cx - paint.measureText(value) / 2f, y, paint)
        }

        private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, width: Float = 1f) {
            paint.shader = null; paint.colorFilter = null; paint.alpha = 255; paint.color = tint
            paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.style = Paint.Style.STROKE
            c.drawLine(x1, y1, x2, y2, paint)
            paint.style = Paint.Style.FILL
        }

        private fun rounded(c: Canvas, rect: RectF, radius: Float, fill: Int, stroke: Int? = null, strokeWidth: Float = 1.2f) {
            paint.shader = null; paint.colorFilter = null; paint.alpha = 255; paint.color = fill; paint.style = Paint.Style.FILL
            c.drawRoundRect(rect, radius, radius, paint)
            if (stroke != null) {
                paint.color = stroke; paint.strokeWidth = strokeWidth; paint.style = Paint.Style.STROKE
                c.drawRoundRect(rect, radius, radius, paint); paint.style = Paint.Style.FILL
            }
        }

        private fun drawBackdrop(c: Canvas) {
            val top = if (decayed) color("#0A0B0C") else color("#101923")
            val middle = if (decayed) color("#282522") else color("#28323C")
            val bottom = if (decayed) color("#101112") else color("#101820")
            paint.shader = LinearGradient(0f, 0f, 420f, designHeight, intArrayOf(top, middle, bottom), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 420f, designHeight, paint); paint.shader = null
            for (i in 0..12) {
                val x = i * 38f - 22f
                paint.color = color(if (decayed) (if (i % 2 == 0) "#302B28" else "#151719") else (if (i % 2 == 0) "#26313A" else "#17212A"))
                paint.strokeWidth = 17f + (i % 3) * 5f; paint.style = Paint.Style.STROKE
                c.drawArc(x, -90f, x + 100f, 700f, 95f, 175f, false, paint)
            }
            paint.style = Paint.Style.FILL
            rounded(c, RectF(294f, 35f, 408f, 260f), 55f, if (decayed) color("#171919") else color("#202C37"), color("#68747E"))
            paint.shader = RadialGradient(350f, 112f, 97f, if (decayed) color("#888176") else color("#B9C2C8"), color("#35424E"), Shader.TileMode.CLAMP)
            c.drawCircle(350f, 112f, 78f, paint); paint.shader = null
            line(c, 350f, 35f, 350f, 257f, color("#6C7781"), 2f)
            line(c, 294f, 184f, 407f, 184f, color("#6C7781"), 2f)
            for (i in 0..5) {
                val x = 290f + i * 23f
                paint.color = color(if (decayed) "#101413" else "#101820")
                val path = Path().apply { moveTo(x - 15f, 342f); lineTo(x, 262f - (i % 3) * 17f); lineTo(x + 15f, 342f); close() }
                c.drawPath(path, paint)
            }
            if (decayed) for (i in 0..5) line(c, 22f + i * 67f, 45f, 60f + i * 54f, 208f + (i % 2) * 60f, color("#625C53"), .8f)
        }

        private fun ivory() = color(if (decayed) "#B8AA98" else "#D3D7D8")

        private fun drawHeader(c: Canvas) {
            val day = SimpleDateFormat("EEEE", Locale.FRENCH).format(Date()).replaceFirstChar { it.uppercase() }
            val date = SimpleDateFormat("dd MMMM", Locale.FRENCH).format(Date())
            drawText(c, day, 24f, 62f, 29f, ivory(), italic = true)
            drawText(c, date, 25f, 96f, 21f, ivory(), italic = true)
            line(c, 24f, 111f, 236f, 111f, color("#8C969E"), 1f)
            drawText(c, "8°  ·  ciel couvert", 26f, 138f, 15f, ivory())
        }

        private fun drawPortrait(c: Canvas) {
            val frame = RectF(126f, 147f, 294f, 342f)
            rounded(c, frame, 75f, color(if (decayed) "#292621" else "#222C35"), color(if (decayed) "#81776A" else "#B2BCC2"), 2f)
            rounded(c, RectF(132f, 153f, 288f, 336f), 69f, color(if (decayed) "#51483F" else "#48535D"), color("#65717B"))
            val image = if (decayed) undeadPortrait ?: intactPortrait else intactPortrait
            if (image != null) {
                val oval = Path().apply { addOval(RectF(137f, 158f, 283f, 331f), Path.Direction.CW) }
                c.save(); c.clipPath(oval)
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(image, null, RectF(137f, 158f, 283f, 331f), paint)
                val shift = if (mediaPlaying) sin(System.currentTimeMillis() / 180.0).toFloat() * 7f else 0f
                line(c, 237f + shift, 174f, 190f + shift, 312f, color(if (decayed) "#9D8D78" else "#D7C9AA"), 1.15f)
                c.restore()
            } else {
                centered(c, "♬", 210f, 266f, 54f, ivory())
            }
            paint.color = color("#A5AEB4"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
            c.drawOval(RectF(133f, 154f, 287f, 335f), paint); paint.style = Paint.Style.FILL
            // No caption under the portrait: the frame itself is the signature.
        }

        private fun drawMusicPanel(c: Canvas) {
            rounded(c, RectF(56f, 352f, 364f, 438f), 12f, color(if (decayed) "#282522" else "#18222C"), color(if (decayed) "#81766A" else "#84909A"))
            centered(c, mediaTitle.take(34), 210f, 377f, 13f, ivory(), italic = true)
            centered(c, mediaArtist.take(36), 210f, 395f, 10f, color("#AFA99F"), italic = true)
            drawMiniViolin(c, 91f, 410f, mediaPlaying)
            centered(c, "|◀", 153f, 423f, 14f, ivory())
            centered(c, if (mediaPlaying) "Ⅱ" else "▶", 210f, 425f, 19f, ivory())
            centered(c, "▶|", 266f, 423f, 14f, ivory())
            line(c, 126f, 431f, 294f, 431f, color("#776F63"), .7f)
        }

        private fun drawMiniViolin(c: Canvas, cx: Float, cy: Float, playing: Boolean) {
            paint.color = color(if (decayed) "#776A59" else "#B59B77")
            val path = Path().apply {
                moveTo(cx, cy - 14f); cubicTo(cx - 12f, cy - 20f, cx - 11f, cy - 7f, cx - 5f, cy)
                cubicTo(cx - 11f, cy + 8f, cx - 8f, cy + 17f, cx, cy + 13f)
                cubicTo(cx + 8f, cy + 17f, cx + 11f, cy + 8f, cx + 5f, cy)
                cubicTo(cx + 11f, cy - 7f, cx + 12f, cy - 20f, cx, cy - 14f); close()
            }
            c.drawPath(path, paint)
            line(c, cx, cy - 12f, cx, cy + 12f, color("#3A3028"), 1f)
            val shift = if (playing) sin(System.currentTimeMillis() / 180.0).toFloat() * 5f else 0f
            line(c, cx - 13f + shift, cy - 15f, cx + 14f + shift, cy + 15f, color("#D2C4AA"), 1f)
        }

        private fun drawNotebookPreview(c: Canvas) {
            val bounds = RectF(14f, 450f, 211f, 628f)
            rounded(c, bounds, 9f, color(if (decayed) "#494138" else "#C7BDAE"), color(if (decayed) "#8B7F70" else "#E4DACB"), 1.4f)
            line(c, 28f, 463f, 28f, 616f, color(if (decayed) "#786D5F" else "#9B8D7A"), 2f)
            drawText(c, pageTitles[bookPage], 40f, 476f, 16f, color(if (decayed) "#D7C9B5" else "#393A3A"), italic = true)
            centered(c, "›", 195f, 476f, 22f, color("#777064"))
            line(c, 36f, 484f, 194f, 484f, color("#8F8577"), .8f)
            val page = bookPages[bookPage]
            page.drop(bookScroll).take(4).forEachIndexed { visible, entry ->
                val y = 509f + visible * 25f
                drawCheckbox(c, 37f, y - 12f, entry.done, 13f)
                drawText(c, entry.text.take(20), 60f, y, 12.5f, color(if (decayed) "#C7B8A3" else "#393B3B"), handwritten = true)
            }
            drawText(c, "+ Ajouter…", 60f, 610f, 11f, color(if (decayed) "#B9A991" else "#4A4844"), italic = true)
            centered(c, "✦", 185f, 614f, 12f, color(if (decayed) "#817666" else "#82796E"))
        }

        private fun drawCheckbox(c: Canvas, x: Float, y: Float, done: Boolean, size: Float) {
            rounded(c, RectF(x, y, x + size, y + size), 2f, Color.TRANSPARENT, color(if (decayed) "#9A8F7E" else "#55534D"), 1f)
            if (done) {
                line(c, x + 2f, y + size * .55f, x + size * .42f, y + size - 2f, color("#853D42"), 1.7f)
                line(c, x + size * .42f, y + size - 2f, x + size - 1f, y + 2f, color("#853D42"), 1.7f)
            }
        }

        private fun drawPocketWatch(c: Canvas) {
            val cx = 346f; val cy = 526f
            paint.color = color(if (decayed) "#6A6258" else "#AEB8BE"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            val chain = Path().apply { moveTo(cx, 457f); cubicTo(300f, 430f, 390f, 425f, cx, 468f) }
            c.drawPath(chain, paint); paint.style = Paint.Style.FILL
            paint.color = color(if (decayed) "#655A4F" else "#AEB8BE"); c.drawCircle(cx, cy, 51f, paint)
            paint.color = color(if (decayed) "#1D1D1B" else "#29343E"); c.drawCircle(cx, cy, 45f, paint)
            paint.color = color(if (decayed) "#968775" else "#C4CCD0"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
            c.drawCircle(cx, cy, 40f, paint); paint.style = Paint.Style.FILL
            centered(c, "✣", cx, cy + 7f, 21f, color(if (decayed) "#A99A83" else "#CDD3D6"))
            if (decayed) {
                line(c, 311f, 492f, 381f, 558f, color("#776D61"), 1.1f)
                line(c, 381f, 492f, 315f, 558f, color("#625D55"), .9f)
            }
        }

        private fun drawSearch(c: Canvas) {
            rounded(c, RectF(16f, 638f, 404f, 683f), 8f, color(if (decayed) "#51483E" else "#C9BFAF"), color(if (decayed) "#8D8071" else "#E1D8C8"))
            centered(c, "G", 47f, 668f, 23f, color(if (decayed) "#D3C4AF" else "#3A3B3A"), italic = true)
            drawText(c, "Rechercher sur Google…", 72f, 667f, 13f, color(if (decayed) "#D1C4B1" else "#4A4945"), italic = true)
            centered(c, "⌕", 380f, 669f, 24f, color(if (decayed) "#D3C4AF" else "#393A3A"))
        }

        private fun drawApps(c: Canvas) {
            appBounds.clear()
            val centers = floatArrayOf(37f, 106f, 175f, 244f, 313f, 382f)
            apps.forEachIndexed { index, app ->
                val row = index / 6
                val col = index % 6
                val cx = centers[col]
                val cy = if (row == 0) 718f else 798f
                val rect = RectF(cx - 21f, cy - 21f, cx + 21f, cy + 21f)
                rounded(c, rect, 7f, color(if (decayed) "#252624" else "#1A252F"), color(if (decayed) "#756B5F" else "#9BA7AF"), 1.15f)
                centered(c, app.glyph, cx, cy + 8f, 23f, color(if (decayed) "#8E8578" else "#C4CDD1"))
                if (decayed) {
                    line(c, cx - 15f, cy - 15f, cx + 15f, cy + 15f, color("#6C6258"), .9f)
                    line(c, cx + 15f, cy - 15f, cx - 15f, cy + 15f, color("#514D46"), .7f)
                }
                centered(c, app.label, cx, cy + 36f, 9.3f, ivory())
                appBounds.add(rect to app)
            }
        }

        private fun drawCurtain(c: Canvas) {
            val top = designHeight - 34f
            // Closed state: a decorated velvet hem fills the former white strip.
            // When opened, the full-height theatre curtains are drawn above the drawer.
            if (drawerProgress > .02f) return
            paint.shader = LinearGradient(0f, top, 0f, designHeight, intArrayOf(color("#17151A"), color("#3A1F27"), color("#151419")), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, 420f, designHeight, paint); paint.shader = null
            line(c, 0f, top, 420f, top, color("#A38B70"), 1.2f)
            for (i in 0..14) {
                val x = i * 30f
                line(c, x, top + 3f, x + 8f, designHeight - 2f, color(if (i % 2 == 0) "#75535A" else "#2A1C22"), 2.8f)
            }
            centered(c, "✥", 210f, designHeight - 10f, 18f, color("#C1A783"))
        }

        private fun drawCurtainPanel(c: Canvas, rect: RectF, left: Boolean) {
            if (rect.width() <= 1f) return
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, intArrayOf(color("#21171D"), color("#55303A"), color("#261920")), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null
            for (i in 0..4) {
                val x = rect.left + rect.width() * i / 4f
                line(c, x, rect.top + 2f, x + if (left) 5f else -5f, rect.bottom, color("#96745E"), 1.1f)
            }
            line(c, rect.left, rect.top + 2f, rect.right, rect.top + 2f, color("#B59A77"), 1f)
            val tasselX = if (left) rect.right - 9f else rect.left + 9f
            line(c, tasselX, rect.top + 5f, tasselX, rect.bottom - 4f, color("#C4A77F"), 1f)
            paint.color = color("#C4A77F"); c.drawCircle(tasselX, rect.top + 7f, 2f, paint)
        }

        private fun drawOpenBook(c: Canvas) {
            val bottom = designHeight - 54f
            paint.color = 0xE20A0C10.toInt(); c.drawRect(0f, 0f, 420f, designHeight, paint)
            rounded(c, RectF(10f, 68f, 410f, bottom + 7f), 18f, color("#30231E"), color("#9B8061"), 2.4f)
            val left = RectF(20f, 78f, 207f, bottom)
            val right = RectF(213f, 78f, 400f, bottom)
            drawBookPage(c, left, bookPage)
            drawBookPage(c, right, bookPage + 1)
            // Central binding and subtle leaf edges.
            paint.shader = LinearGradient(205f, 0f, 215f, 0f, intArrayOf(color("#7B6046"), color("#38271F"), color("#7B6046")), null, Shader.TileMode.CLAMP)
            c.drawRect(204f, 81f, 216f, bottom - 1f, paint); paint.shader = null
            centered(c, "✦", 210f, bottom - 12f, 12f, color("#927957"))
            centered(c, "‹", 28f, 105f, 24f, color("#705B47"))
            centered(c, "›", 392f, 105f, 24f, color("#705B47"))
        }

        private fun drawBookPage(c: Canvas, rect: RectF, pageIndex: Int) {
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, intArrayOf(color("#E2D2B3"), color("#D4C09C"), color("#E6D6B8")), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null
            paint.color = color("#A58E6E"); paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
            c.drawRect(rect, paint); paint.style = Paint.Style.FILL
            for (i in 0..6) line(c, rect.left + 12f, rect.top + 45f + i * 48f, rect.right - 12f, rect.top + 45f + i * 48f, color("#C1AA86"), .25f)
            val index = pageIndex.coerceIn(0, 3)
            centered(c, pageTitles[index], rect.centerX(), rect.top + 42f, 12.5f, color("#493A2C"), italic = true)
            line(c, rect.left + 13f, rect.top + 52f, rect.right - 13f, rect.top + 52f, color("#9B8061"), .8f)
            val entries = bookPages[index]
            val startY = rect.top + 84f
            val available = rect.bottom - startY - 46f
            val rowStep = min(38f, available / 10f)
            val visibleCount = max(1, (available / rowStep).toInt())
            entries.drop(bookScroll).take(visibleCount).forEachIndexed { visible, entry ->
                val y = startY + visible * rowStep
                drawCheckbox(c, rect.left + 13f, y - 12f, entry.done, 12f)
                drawText(c, entry.text.take(23), rect.left + 32f, y, 12f, color("#3D3027"), handwritten = true)
            }
            drawText(c, "+ Ajouter…", rect.left + 24f, rect.bottom - 18f, 12f, color("#69523D"), italic = true)
            centered(c, "✧", rect.right - 18f, rect.bottom - 17f, 12f, color("#8A7255"))
        }

        private fun drawAppDrawer(c: Canvas) {
            val alpha = (drawerProgress * 220f).toInt().coerceIn(0, 220)
            paint.color = (alpha shl 24) or 0x0006090D
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            val bottom = designHeight - 48f
            rounded(c, RectF(14f, 74f, 406f, bottom), 18f, color(if (decayed) "#332D27" else "#D8C9AC"), color(if (decayed) "#8B7D6C" else "#9B8668"), 2f)
            centered(c, "Bibliothèque", 210f, 124f, 25f, color(if (decayed) "#D2C4B2" else "#46372B"), italic = true)
            line(c, 42f, 140f, 378f, 140f, color(if (decayed) "#8E8170" else "#9B8061"), .9f)
            messageBounds = RectF(72f, 164f, 194f, 242f)
            dialBounds = RectF(226f, 164f, 348f, 242f)
            drawBatLetter(c, messageBounds.centerX(), messageBounds.centerY())
            drawText(c, "Messages", messageBounds.left + 13f, 260f, 11f, color(if (decayed) "#D2C4B2" else "#493A2C"), italic = true)
            drawTelephoneHorn(c, dialBounds.centerX(), dialBounds.centerY())
            drawText(c, "Appels", dialBounds.left + 35f, 260f, 11f, color(if (decayed) "#D2C4B2" else "#493A2C"), italic = true)
            drawerAppBounds.clear()
            val start = drawerPage * 6
            val selected = apps.drop(start).take(6)
            val xs = floatArrayOf(98f, 210f, 322f)
            selected.forEachIndexed { i, app ->
                val row = i / 3
                val col = i % 3
                val cx = xs[col]
                val cy = 350f + row * 150f
                val rect = RectF(cx - 31f, cy - 31f, cx + 31f, cy + 31f)
                rounded(c, rect, 10f, color(if (decayed) "#242322" else "#26313A"), color(if (decayed) "#9A8B77" else "#8F7A5D"), 1.3f)
                centered(c, app.glyph, cx, cy + 10f, 29f, color("#D8C9AC"))
                centered(c, app.label, cx, cy + 51f, 12f, color(if (decayed) "#D4C6B4" else "#493A2C"), italic = true)
                drawerAppBounds.add(rect to app)
            }
            if (drawerPage > 0) centered(c, "‹", 42f, 650f, 26f, color("#8A7051"))
            centered(c, if (drawerPage == 0) "I" else "II", 210f, 650f, 10f, color("#8A7051"), italic = true)
            if (drawerPage == 0) centered(c, "›", 378f, 650f, 26f, color("#8A7051"))
            centered(c, "✥", 210f, bottom - 18f, 17f, color("#927957"))
            // Full-height velvet panels separate like theatre curtains.
            if (drawerProgress < 1f) {
                val half = 210f * (1f - drawerProgress)
                drawCurtainPanel(c, RectF(0f, 0f, half, designHeight), true)
                drawCurtainPanel(c, RectF(420f - half, 0f, 420f, designHeight), false)
            }
        }

        private fun drawBatLetter(c: Canvas, cx: Float, cy: Float) {
            paint.color = color("#30222A")
            val wings = Path().apply {
                moveTo(cx, cy - 4f); cubicTo(cx - 18f, cy - 28f, cx - 39f, cy - 23f, cx - 50f, cy - 5f)
                lineTo(cx - 34f, cy - 6f); lineTo(cx - 40f, cy + 12f); lineTo(cx - 18f, cy + 3f)
                lineTo(cx, cy + 15f); lineTo(cx + 18f, cy + 3f); lineTo(cx + 40f, cy + 12f)
                lineTo(cx + 34f, cy - 6f); lineTo(cx + 50f, cy - 5f); cubicTo(cx + 39f, cy - 23f, cx + 18f, cy - 28f, cx, cy - 4f); close()
            }
            c.drawPath(wings, paint)
            rounded(c, RectF(cx - 13f, cy - 17f, cx + 13f, cy + 16f), 2f, color("#E2D2B3"), color("#846D50"), 1f)
            line(c, cx - 9f, cy - 10f, cx, cy - 2f, color("#846D50"), .8f)
            line(c, cx + 9f, cy - 10f, cx, cy - 2f, color("#846D50"), .8f)
        }

        private fun drawTelephoneHorn(c: Canvas, cx: Float, cy: Float) {
            paint.color = color("#7B6046"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f; paint.strokeCap = Paint.Cap.ROUND
            val path = Path().apply { moveTo(cx - 22f, cy + 16f); cubicTo(cx - 3f, cy + 23f, cx + 4f, cy - 4f, cx + 23f, cy - 18f) }
            c.drawPath(path, paint); paint.style = Paint.Style.FILL
            paint.color = color("#7B6046")
            val bell = Path().apply { moveTo(cx + 12f, cy - 10f); lineTo(cx + 38f, cy - 31f); lineTo(cx + 47f, cy - 16f); lineTo(cx + 20f, cy + 1f); close() }
            c.drawPath(bell, paint)
            val mouth = Path().apply { moveTo(cx - 31f, cy + 8f); lineTo(cx - 18f, cy + 24f); lineTo(cx - 30f, cy + 34f); lineTo(cx - 43f, cy + 18f); close() }
            c.drawPath(mouth, paint)
            line(c, cx + 26f, cy - 28f, cx + 41f, cy - 15f, color("#D1B58B"), 1.2f)
        }

        private fun drawOpenWatch(c: Canvas) {
            paint.color = 0xE5080B0E.toInt(); c.drawRect(0f, 0f, 420f, designHeight, paint)
            rounded(c, RectF(48f, 118f, 372f, designHeight - 72f), 28f, color(if (decayed) "#2B2722" else "#1C2731"), color(if (decayed) "#A0927F" else "#B9C3C8"), 2f)
            paint.color = color(if (decayed) "#8D806E" else "#BFC8CD"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
            c.drawCircle(210f, 315f, 119f, paint); paint.style = Paint.Style.FILL
            paint.color = color(if (decayed) "#171817" else "#2D3944"); c.drawCircle(210f, 315f, 109f, paint)
            paint.color = color(if (decayed) "#B19D7D" else "#D2D7D8"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
            c.drawCircle(210f, 315f, 98f, paint); paint.style = Paint.Style.FILL
            for (i in 0..11) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                val x = 210f + cos(angle).toFloat() * 84f
                val y = 315f + sin(angle).toFloat() * 84f
                val roman = when (i) { 0 -> "XII"; 3 -> "III"; 6 -> "VI"; 9 -> "IX"; else -> "·" }
                centered(c, roman, x, y + 5f, 12f, ivory())
            }
            val now = java.util.Calendar.getInstance()
            val minute = now.get(java.util.Calendar.MINUTE)
            val hour = now.get(java.util.Calendar.HOUR)
            val ha = Math.toRadians(hour * 30.0 + minute * .5 - 90.0)
            val ma = Math.toRadians(minute * 6.0 - 90.0)
            line(c, 210f, 315f, 210f + cos(ha).toFloat() * 49f, 315f + sin(ha).toFloat() * 49f, ivory(), 3f)
            line(c, 210f, 315f, 210f + cos(ma).toFloat() * 75f, 315f + sin(ma).toFloat() * 75f, color("#B9C4CB"), 1.7f)
            paint.color = ivory(); c.drawCircle(210f, 315f, 4f, paint)
            val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
            centered(c, "${format.format(stepsToday)} pas", 210f, 485f, 25f, ivory(), italic = true)
            centered(c, "Objectif : ${format.format(goalSteps)}", 210f, 514f, 12f, color("#C2B7A6"), italic = true)
            drawFootstepMap(c, RectF(72f, 548f, 348f, 710f))
            if (!healthPermissionGranted) centered(c, "Relier Santé Connect", 210f, 738f, 10f, color("#D5C7B2"), italic = true)
        }

        private fun drawFootstepMap(c: Canvas, rect: RectF) {
            rounded(c, rect, 9f, color("#D8C7A7"), color("#9B8061"), 1f)
            for (i in 0..4) line(c, rect.left + 5f, rect.top + 16f + i * 30f, rect.right - 5f, rect.top + 16f + i * 30f, color("#C3AF8B"), .35f)
            val positions = mutableListOf<Triple<Float, Float, Float>>()
            val xs = floatArrayOf(100f, 155f, 210f, 265f, 320f)
            for (row in 0..3) {
                val indices = if (row % 2 == 0) 0..4 else 4 downTo 0
                for (col in indices) {
                    val x = xs[col]
                    val y = 570f + row * 37f
                    val rotation = if ((row + col) % 2 == 0) -12f else 12f
                    positions.add(Triple(x, y, rotation))
                }
            }
            val complete = (stepsToday / 500L).toInt().coerceIn(0, 20)
            val partial = (stepsToday % 500L).toFloat() / 500f
            positions.forEachIndexed { index, item ->
                val active = index < complete
                val alpha = if (active) 255 else if (index == complete) (70 + partial * 150f).toInt() else 38
                drawFootprint(c, item.first, item.second, item.third, active, alpha)
            }
        }

        private fun drawFootprint(c: Canvas, x: Float, y: Float, rotation: Float, filled: Boolean, alpha: Int) {
            c.save(); c.rotate(rotation, x, y)
            val tint = color("#3C3026")
            paint.color = tint; paint.alpha = alpha
            paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = 1f
            c.drawOval(RectF(x - 4f, y - 2f, x + 4f, y + 10f), paint)
            c.drawOval(RectF(x - 5f, y - 10f, x + 5f, y + 1f), paint)
            for (toe in 0..2) c.drawCircle(x - 3f + toe * 3f, y - 11f - (if (toe == 1) 2f else 0f), 1.3f, paint)
            paint.alpha = 255; paint.style = Paint.Style.FILL
            c.restore()
        }

        private fun drawOpenBookPageMarker(c: Canvas, pageIndex: Int, rect: RectF) {
            centered(c, if (pageIndex % 2 == 0) "I" else "II", rect.centerX(), rect.bottom - 9f, 9f, color("#8A7255"), italic = true)
        }

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
                    if (jsonText == null || page >= root.length()) defaults[page].forEach { entries.add(BookEntry(it, false)) }
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

        private fun editEntry(pageIndex: Int, index: Int) {
            val page = bookPages[pageIndex]
            val existing = index in page.indices
            val initial = if (existing) page[index].text else ""
            showNotebookEditor(initial, { value ->
                if (value == null) {
                    if (existing && index in page.indices) page.removeAt(index)
                } else if (existing && index in page.indices) {
                    page[index].text = value
                } else {
                    page.add(BookEntry(value, false))
                }
                saveNotebook(); invalidate()
            }, existing)
        }

        private fun animateDrawer(open: Boolean) {
            drawerAnimator?.cancel()
            val target = if (open) 1f else 0f
            drawerAnimator = ValueAnimator.ofFloat(drawerProgress, target).apply {
                duration = 620L
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawerProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun closeOverlay(): Boolean {
            return when {
                drawerProgress > .02f -> { animateDrawer(false); true }
                bookOpen -> { bookOpen = false; invalidate(); true }
                watchOpen -> { watchOpen = false; invalidate(); true }
                else -> false
            }
        }

        private fun handleBookTap(x: Float, y: Float, dx: Float, dy: Float, duration: Long) {
            val bottom = designHeight - 54f
            if (abs(dx) > 42f) {
                bookPage = if (dx < 0f) 2 else 0
                bookScroll = 0; saveNotebook(); invalidate(); return
            }
            if (abs(dy) > 34f) {
                val longest = max(bookPages[bookPage].size, bookPages[(bookPage + 1).coerceAtMost(3)].size)
                val maxScroll = max(0, longest - 12)
                bookScroll = (bookScroll + if (dy < 0f) 3 else -3).coerceIn(0, maxScroll)
                invalidate(); return
            }
            if (y < 125f || y > bottom + 8f) { bookOpen = false; invalidate(); return }
            val pageIndex = (bookPage + if (x >= 210f) 1 else 0).coerceIn(0, 3)
            val startY = 78f + 84f
            val rowStep = min(38f, (bottom - startY - 46f) / 10f)
            if (y >= bottom - 39f) { editEntry(pageIndex, -1); return }
            val visible = ((y - startY + rowStep * .35f) / rowStep).toInt()
            val index = visible + bookScroll
            val page = bookPages[pageIndex]
            if (index in page.indices) {
                if (duration > 550L || pageIndex >= 2) editEntry(pageIndex, index)
                else { page[index].done = !page[index].done; saveNotebook(); invalidate() }
            }
        }

        private fun handleHomeNotebookTap(y: Float, dx: Float, duration: Long) {
            if (y < 490f || abs(dx) > 30f) {
                bookPage = if (bookPage % 2 == 0) bookPage else bookPage - 1
                bookOpen = true; bookScroll = 0; invalidate(); return
            }
            if (y >= 596f) { editEntry(bookPage, -1); return }
            val index = ((y - 509f) / 25f).toInt() + bookScroll
            val page = bookPages[bookPage]
            if (index in page.indices) {
                if (duration > 550L || bookPage >= 2) editEntry(bookPage, index)
                else { page[index].done = !page[index].done; saveNotebook(); invalidate() }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x / scaleFactor
            val y = event.y / scaleFactor
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; downAt = event.eventTime
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = x - downX
                    val dy = y - downY
                    val duration = event.eventTime - downAt
                    if (drawerProgress > .97f) {
                        if (y >= designHeight - 44f || y < 95f) { animateDrawer(false); return true }
                        if (messageBounds.contains(x, y)) { openMessages(); return true }
                        if (dialBounds.contains(x, y)) { openDialer(); return true }
                        if (abs(dx) > 45f) { drawerPage = if (dx < 0f) 1 else 0; invalidate(); return true }
                        drawerAppBounds.firstOrNull { it.first.contains(x, y) }?.let { launchApp(it.second.packageName, it.second.label); return true }
                        return true
                    } else if (drawerProgress > .02f) return true

                    if (bookOpen) { handleBookTap(x, y, dx, dy, duration); return true }
                    if (watchOpen) {
                        if (!healthPermissionGranted) requestHealthPermission() else watchOpen = false
                        invalidate(); return true
                    }
                    if (y >= designHeight - 40f) { animateDrawer(true); return true }
                    if (taskBounds.contains(downX, downY) || taskBounds.contains(x, y)) {
                        handleHomeNotebookTap(y, dx, duration); return true
                    }
                    if (musicBounds.contains(x, y)) {
                        if (x < 176f) controlMusic("previous")
                        else if (x > 245f) controlMusic("next")
                        else openMusic()
                        invalidate(); return true
                    }
                    if (watchBounds.contains(x, y)) { watchOpen = true; refreshHealthConnect(); invalidate(); return true }
                    if (searchBounds.contains(x, y)) { launchSearchDialog(); return true }
                    appBounds.firstOrNull { it.first.contains(x, y) }?.let { launchApp(it.second.packageName, it.second.label); return true }
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
