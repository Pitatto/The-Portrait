package com.noiraube.theportrait

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.PathMeasure
import android.graphics.drawable.Drawable
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
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.WindowManager
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
    private var mediaTitle = ""
    private var mediaArtist = ""
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
            mediaTitle = ""
            mediaArtist = ""
            mediaPlaying = false
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, MediaAccessListener::class.java))
            val youtubeSessions = sessions.filter { it.packageName == "com.google.android.apps.youtube.music" }
            val selected = youtubeSessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: youtubeSessions.firstOrNull { it.metadata != null }
            mediaController = selected
            mediaTitle = selected?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: ""
            mediaArtist = selected?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: selected?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            mediaPlaying = selected?.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: Exception) {
            mediaController = null
            mediaTitle = ""
            mediaArtist = ""
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
        launchApp("com.google.android.apps.youtube.music", "YouTube Music")
    }

    private fun controlMusic(action: String) {
        if (!hasNotificationAccess()) {
            askForMediaAccess()
            return
        }
        val controller = mediaController
        if (controller == null || controller.packageName != "com.google.android.apps.youtube.music") {
            if (action == "toggle") openMusic()
            else Toast.makeText(this, "Aucun morceau YouTube Music actif", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            when (action) {
                "previous" -> controller.transportControls.skipToPrevious()
                "next" -> controller.transportControls.skipToNext()
                "toggle" -> if (mediaPlaying) controller.transportControls.pause() else controller.transportControls.play()
            }
            handler.postDelayed({ refreshMediaSession(); if (::portraitView.isInitialized) portraitView.invalidate() }, 350L)
        } catch (_: Exception) {
            Toast.makeText(this, "Commande musicale indisponible", Toast.LENGTH_SHORT).show()
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
        private var watchOpenProgress = 0f
        private var watchAnimator: ValueAnimator? = null
        private var bookOpen = false
        private var bookPage = prefs.getInt("bookPage", 0).coerceIn(0, 3)
        private var bookScroll = 0
        private var drawerProgress = 0f
        private var drawerPage = 0
        private var drawerSearchQuery = ""
        private var drawerScrollOffset = 0
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
        private val watchBounds = RectF(270f, 425f, 418f, 640f)
        private val musicBounds = RectF(56f, 352f, 364f, 438f)
        private val searchBounds = RectF(16f, 638f, 404f, 683f)
        private val appBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val drawerAppBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val drawerApps = mutableListOf<AppItem>()
        private val drawerIconCache = mutableMapOf<String, Drawable>()
        private var drawerSearchBounds = RectF()
        private var messageBounds = RectF()
        private var dialBounds = RectF()
        private var bowPhase = 0f
        private val intactPortrait: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.musician_portrait) } catch (_: Exception) { null }
        private val undeadPortrait: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.musician_portrait_decayed) } catch (_: Exception) { null }
        private val pocketWatch: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.pocket_watch) } catch (_: Exception) { null }
        private val closedPocketWatch: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.pocket_watch_closed) } catch (_: Exception) { null }
        private val wallpaperBitmap: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.the_portrait_wallpaper) } catch (_: Exception) { null }
        private val portraitFrame: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.portrait_frame_silver) } catch (_: Exception) { null }
        private val homeIconCache = mutableMapOf<String, Drawable>()
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
            drawMusicPanel(canvas)
            drawPortrait(canvas)
            drawNotebookPreview(canvas)
            drawPocketWatch(canvas)
            drawSearch(canvas)
            drawApps(canvas)
            drawCurtain(canvas)
            if (watchOpenProgress > 0.001f) drawOpenWatch(canvas)
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
            val wallpaper = wallpaperBitmap
            if (wallpaper != null) {
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(wallpaper, null, RectF(0f, 0f, 420f, designHeight), paint)
            } else {
                paint.shader = LinearGradient(0f, 0f, 420f, designHeight, color("#111B2A"), color("#060D17"), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 420f, designHeight, paint); paint.shader = null
            }
            // Cool nocturnal glaze keeps all widgets in the same silver-blue atmosphere.
            paint.color = if (decayed) 0x66303A46 else 0x33233342
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, designHeight, 0x22000000, 0x66000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 420f, designHeight, paint); paint.shader = null
            // A fine silver hairline at the edge gives the wallpaper a framed finish.
            line(c, 8f, 8f, 8f, designHeight - 8f, color("#596878"), .55f)
            line(c, 412f, 8f, 412f, designHeight - 8f, color("#596878"), .55f)
        }

        private fun ivory() = color(if (decayed) "#B8AA98" else "#D3D7D8")

        private fun drawHeader(c: Canvas) {
            val day = SimpleDateFormat("EEEE", Locale.FRENCH).format(Date()).replaceFirstChar { it.uppercase() }
            val date = SimpleDateFormat("dd MMMM", Locale.FRENCH).format(Date())
            drawText(c, day, 24f, 62f, 29f, ivory(), italic = true)
            drawText(c, date, 25f, 96f, 21f, ivory(), italic = true)
            line(c, 24f, 111f, 236f, 111f, color("#8998A8"), 1f)
            centered(c, "✥", 130f, 115f, 8f, color("#C4CDD6"))
        }

        private fun drawPortrait(c: Canvas) {
            val aperture = RectF(137f, 145f, 283f, 348f)
            paint.color = color("#0B111B"); c.drawOval(RectF(137f, 135f, 283f, 356f), paint)
            val image = if (decayed) undeadPortrait ?: intactPortrait else intactPortrait
            if (image != null) {
                val oval = Path().apply { addOval(aperture, Path.Direction.CW) }
                c.save(); c.clipPath(oval)
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(image, null, aperture, paint)
                c.restore()
            }
            // The portrait artwork already contains its violin and bow: no artificial duplicate stroke.
            portraitFrame?.let {
                c.drawBitmap(it, null, RectF(107f, 83f, 313f, 422f), paint)
            } ?: run {
                paint.color = color("#AEBAC6"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f
                c.drawOval(RectF(137f, 135f, 283f, 356f), paint); paint.style = Paint.Style.FILL
            }
        }

        private fun drawMusicPanel(c: Canvas) {
            val bounds = RectF(56f, 352f, 364f, 438f)
            paint.shader = LinearGradient(56f, 352f, 364f, 438f, color("#101A29"), color("#080F19"), Shader.TileMode.CLAMP)
            c.drawRoundRect(bounds, 13f, 13f, paint); paint.shader = null
            rounded(c, RectF(59f, 355f, 361f, 435f), 11f, Color.TRANSPARENT, color("#B5C0CB"), 1.35f)
            rounded(c, RectF(64f, 360f, 356f, 430f), 9f, Color.TRANSPARENT, color("#536171"), .65f)
            for (cx in listOf(68f, 352f)) {
                centered(c, "✧", cx, 365f, 9f, color("#C7D0D8"))
                centered(c, "✧", cx, 430f, 9f, color("#C7D0D8"))
            }
            if (mediaTitle.isNotBlank()) {
                centered(c, mediaTitle.take(31), 210f, 379f, 13f, ivory(), italic = true)
                if (mediaArtist.isNotBlank()) centered(c, mediaArtist.take(34), 210f, 395f, 10f, color("#AEBBC8"), italic = true)
            }
            drawMiniViolin(c, 94f, 408f, mediaPlaying)
            centered(c, "|◀", 164f, 423f, 14f, ivory())
            centered(c, if (mediaPlaying) "Ⅱ" else "▶", 210f, 425f, 19f, color("#D7E0E7"))
            centered(c, "▶|", 256f, 423f, 14f, ivory())
            line(c, 134f, 431f, 286f, 431f, color("#8796A6"), .7f)
        }

        private fun drawMiniViolin(c: Canvas, cx: Float, cy: Float, playing: Boolean) {
            paint.color = color(if (decayed) "#8793A0" else "#C5D0D9")
            val path = Path().apply {
                moveTo(cx, cy - 14f); cubicTo(cx - 12f, cy - 20f, cx - 11f, cy - 7f, cx - 5f, cy)
                cubicTo(cx - 11f, cy + 8f, cx - 8f, cy + 17f, cx, cy + 13f)
                cubicTo(cx + 8f, cy + 17f, cx + 11f, cy + 8f, cx + 5f, cy)
                cubicTo(cx + 11f, cy - 7f, cx + 12f, cy - 20f, cx, cy - 14f); close()
            }
            c.drawPath(path, paint)
            line(c, cx, cy - 12f, cx, cy + 12f, color("#293746"), 1f)
            val shift = if (playing) sin(System.currentTimeMillis() / 180.0).toFloat() * 5f else 0f
            line(c, cx - 13f + shift, cy - 15f, cx + 14f + shift, cy + 15f, color("#D7E0E7"), 1f)
        }

        private fun drawNotebookPreview(c: Canvas) {
            val bounds = RectF(14f, 450f, 211f, 628f)
            paint.shader = LinearGradient(14f, 450f, 211f, 628f, color("#8291A0"), color("#263443"), Shader.TileMode.CLAMP)
            c.drawRoundRect(bounds, 10f, 10f, paint); paint.shader = null
            rounded(c, RectF(19f, 455f, 206f, 623f), 7f, color("#D9DEE3"), color("#A9B5C0"), 1f)
            line(c, 28f, 463f, 28f, 616f, color("#8996A3"), 2f)
            drawText(c, pageTitles[bookPage], 40f, 476f, 16f, color("#273544"), italic = true)
            centered(c, "›", 195f, 476f, 22f, color("#657687"))
            line(c, 36f, 484f, 194f, 484f, color("#9EABB7"), .8f)
            val page = bookPages[bookPage]
            page.drop(bookScroll).take(4).forEachIndexed { visible, entry ->
                val y = 509f + visible * 25f
                line(c, 35f, y + 4f, 193f, y + 4f, color("#AEBAC4"), .42f)
                drawCheckbox(c, 37f, y - 12f, entry.done, 13f)
                drawText(c, entry.text.take(20), 60f, y, 12.5f, color("#263442"), handwritten = true)
            }
            drawText(c, "+ Ajouter…", 60f, 610f, 11f, color("#46596B"), italic = true)
            centered(c, "✦", 185f, 614f, 12f, color("#68798A"))
        }

        private fun drawCheckbox(c: Canvas, x: Float, y: Float, done: Boolean, size: Float) {
            rounded(c, RectF(x, y, x + size, y + size), 2f, Color.TRANSPARENT, color(if (decayed) "#71808D" else "#4E5E6E"), 1f)
            if (done) {
                line(c, x + 2f, y + size * .55f, x + size * .42f, y + size - 2f, color("#6E414C"), 1.7f)
                line(c, x + size * .42f, y + size - 2f, x + size - 1f, y + 2f, color("#6E414C"), 1.7f)
            }
        }

        private fun drawPocketWatch(c: Canvas) {
            val image = closedPocketWatch
            if (image != null) {
                val dst = RectF(270f, 418f, 420f, 648f)
                paint.alpha = 255; paint.colorFilter = null
                c.drawBitmap(image, null, dst, paint)
                if (decayed) {
                    paint.color = 0x263C4652; c.drawRect(dst, paint)
                }
            } else {
                val cx = 346f; val cy = 526f
                paint.color = color("#AEBAC6"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                c.drawCircle(cx, cy, 54f, paint); paint.style = Paint.Style.FILL
                centered(c, "✥", cx, cy + 7f, 24f, color("#C9D3DC"))
            }
        }

        private fun drawSearch(c: Canvas) {
            rounded(c, RectF(16f, 638f, 404f, 683f), 8f, color("#182535"), color("#AAB7C3"), 1.1f)
            rounded(c, RectF(20f, 642f, 400f, 679f), 6f, Color.TRANSPARENT, color("#526375"), .55f)
            centered(c, "G", 47f, 668f, 23f, color("#D4DCE3"), italic = true)
            drawText(c, "Rechercher sur Google…", 72f, 667f, 13f, color("#C9D2DB"), italic = true)
            centered(c, "⌕", 380f, 669f, 24f, color("#D1DAE2"))
        }

        private fun drawApps(c: Canvas) {
            appBounds.clear()
            val centers = floatArrayOf(37f, 106f, 175f, 244f, 313f, 382f)
            apps.forEachIndexed { index, app ->
                val row = index / 6
                val col = index % 6
                val cx = centers[col]
                val cy = if (row == 0) 712f else 776f
                val rect = RectF(cx - 21f, cy - 21f, cx + 21f, cy + 21f)
                drawMetalAppFrame(c, rect, "", false)
                val icon = try { homeIconCache.getOrPut(app.packageName) { packageManager.getApplicationIcon(app.packageName) } } catch (_: Exception) { null }
                if (icon != null) {
                    val inset = 7f
                    icon.setBounds((rect.left + inset).toInt(), (rect.top + inset).toInt(), (rect.right - inset).toInt(), (rect.bottom - inset).toInt())
                    c.save(); icon.draw(c); c.restore()
                } else {
                    centered(c, app.glyph, cx, cy + 8f, 22f, color("#D1DAE2"))
                }
                centered(c, app.label, cx, cy + 36f, 9.3f, ivory())
                appBounds.add(rect to app)
            }
        }

        private fun drawCurtain(c: Canvas) {
            if (drawerProgress > .02f) return
            val top = designHeight - 30f
            paint.shader = LinearGradient(0f, top, 0f, designHeight, intArrayOf(color("#08111D"), color("#22374B"), color("#0A1421")), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, 420f, designHeight, paint); paint.shader = null
            // Gathered velvet folds, like a miniature theatre valance.
            val swag = Path().apply {
                moveTo(0f, top + 2f)
                cubicTo(50f, top + 8f, 75f, designHeight - 2f, 140f, designHeight - 8f)
                cubicTo(178f, designHeight - 11f, 192f, top + 14f, 210f, top + 13f)
                cubicTo(228f, top + 14f, 242f, designHeight - 11f, 280f, designHeight - 8f)
                cubicTo(345f, designHeight - 2f, 370f, top + 8f, 420f, top + 2f)
                lineTo(420f, designHeight); lineTo(0f, designHeight); close()
            }
            paint.shader = LinearGradient(0f, top, 0f, designHeight, color("#263D53"), color("#08121F"), Shader.TileMode.CLAMP)
            c.drawPath(swag, paint); paint.shader = null
            for (i in 0..12) {
                val x = i * 35f
                val fold = Path().apply {
                    moveTo(x, top + 4f)
                    cubicTo(x + 7f, top + 15f, x + 3f, designHeight - 4f, x + 16f, designHeight - 7f)
                }
                paint.color = color(if (i % 2 == 0) "#71869A" else "#111F2E"); paint.strokeWidth = 2.2f; paint.style = Paint.Style.STROKE
                c.drawPath(fold, paint); paint.style = Paint.Style.FILL
            }
            line(c, 0f, top + 2f, 420f, top + 2f, color("#B7C2CC"), 1.2f)
            line(c, 0f, designHeight - 4f, 420f, designHeight - 4f, color("#66798A"), .8f)
            centered(c, "❦", 210f, designHeight - 10f, 17f, color("#D0DAE2"))
            paint.color = color("#D0DAE2"); c.drawCircle(210f, top + 10f, 2.5f, paint)
            line(c, 210f, top + 12f, 210f, designHeight - 8f, color("#95A6B6"), .8f)
        }

        private fun drawCurtainPanel(c: Canvas, rect: RectF, left: Boolean) {
            if (rect.width() <= 1f) return
            paint.color = 0x55000000; c.drawRect(rect.left + if (left) rect.width() - 5f else 0f, rect.top, rect.left + if (left) rect.width() else 5f, rect.bottom, paint)
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, intArrayOf(color("#0A1421"), color("#324B61"), color("#172637"), color("#070F19")), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null
            for (i in 0..6) {
                val x = rect.left + rect.width() * i / 6f
                line(c, x, rect.top + 4f, x + if (left) 8f else -8f, rect.bottom, color(if (i % 2 == 0) "#AAB8C4" else "#142437"), 1.15f)
            }
            line(c, rect.left, rect.top + 2f, rect.right, rect.top + 2f, color("#B7C2CC"), 1.5f)
            val tasselX = if (left) rect.right - 13f else rect.left + 13f
            line(c, tasselX, rect.top + 10f, tasselX, rect.bottom - 10f, color("#CBD5DE"), 1.1f)
            paint.color = color("#CBD5DE"); c.drawCircle(tasselX, rect.top + 11f, 3f, paint)
            centered(c, "❧", tasselX, rect.top + 30f, 18f, color("#CBD5DE"))
        }

        private fun drawOpenBook(c: Canvas) {
            val bottom = designHeight - 54f
            paint.color = 0xE20A0C10.toInt(); c.drawRect(0f, 0f, 420f, designHeight, paint)
            rounded(c, RectF(10f, 68f, 410f, bottom + 7f), 18f, color("#182433"), color("#AAB7C2"), 2.4f)
            val left = RectF(20f, 78f, 207f, bottom)
            val right = RectF(213f, 78f, 400f, bottom)
            drawBookPage(c, left, bookPage)
            drawBookPage(c, right, bookPage + 1)
            // Central binding and subtle leaf edges.
            paint.shader = LinearGradient(205f, 0f, 215f, 0f, intArrayOf(color("#8D9EAD"), color("#293A4B"), color("#8D9EAD")), null, Shader.TileMode.CLAMP)
            c.drawRect(204f, 81f, 216f, bottom - 1f, paint); paint.shader = null
            centered(c, "✦", 210f, bottom - 12f, 12f, color("#AAB8C4"))
            centered(c, "‹", 28f, 105f, 24f, color("#788B9D"))
            centered(c, "›", 392f, 105f, 24f, color("#788B9D"))
        }

        private fun drawBookPage(c: Canvas, rect: RectF, pageIndex: Int) {
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, intArrayOf(color("#E4E9ED"), color("#C7D1D9"), color("#E7ECEF")), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, paint); paint.shader = null
            paint.color = color("#9AA8B5"); paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
            c.drawRect(rect, paint); paint.style = Paint.Style.FILL
            val index = pageIndex.coerceIn(0, 3)
            centered(c, pageTitles[index], rect.centerX(), rect.top + 42f, 12.5f, color("#2C3C4C"), italic = true)
            line(c, rect.left + 13f, rect.top + 52f, rect.right - 13f, rect.top + 52f, color("#AAB7C2"), .8f)
            val entries = bookPages[index]
            // Each handwritten baseline sits directly on a fixed ruled line.
            val startY = rect.top + 93f
            val rowStep = 48f
            val visibleCount = max(1, ((rect.bottom - startY - 34f) / rowStep).toInt())
            for (i in 0 until visibleCount) {
                val guideY = startY + i * rowStep
                line(c, rect.left + 12f, guideY, rect.right - 12f, guideY, color("#AAB6C0"), .42f)
            }
            entries.drop(bookScroll).take(visibleCount).forEachIndexed { visible, entry ->
                val y = startY + visible * rowStep
                drawCheckbox(c, rect.left + 13f, y - 13f, entry.done, 13f)
                drawText(c, entry.text.take(22), rect.left + 33f, y, 12.5f, color("#273746"), handwritten = true)
                rounded(c, RectF(rect.right - 31f, y - 13f, rect.right - 8f, y + 7f), 5f, color("#E1E7EB"), color("#8C9BA9"), .6f)
                centered(c, "×", rect.right - 19.5f, y + 3f, 14f, color("#5C4651"))
            }
            drawText(c, "+ Ajouter…", rect.left + 24f, rect.bottom - 18f, 12f, color("#506579"), italic = true)
            centered(c, "✧", rect.right - 18f, rect.bottom - 17f, 12f, color("#718597"))
        }

        private fun refreshDrawerApps() {
            val excluded = apps.mapTo(mutableSetOf()) { it.packageName }.apply { add(packageName) }
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                packageManager.queryIntentActivities(launcherIntent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            val discovered = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg in excluded) return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                AppItem(label, pkg, label.first().uppercaseChar().toString())
            }.distinctBy { it.packageName }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            drawerApps.clear()
            drawerApps.addAll(discovered)
            drawerIconCache.keys.retainAll(discovered.map { it.packageName }.toSet())
            drawerScrollOffset = 0
        }

        private fun showDrawerSearch() {
            val input = EditText(this@MainActivity).apply {
                hint = "Nom de l'application…"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setText(drawerSearchQuery)
                setSelection(text.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        drawerSearchQuery = s?.toString().orEmpty()
                        drawerScrollOffset = 0
                        invalidate()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Rechercher une application")
                .setView(input)
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Effacer", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { input.text.clear() }
            }
            dialog.show()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.requestFocus()
        }

        private fun filteredDrawerApps(): List<AppItem> {
            val query = drawerSearchQuery.trim()
            if (query.isEmpty()) return drawerApps
            return drawerApps.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }

        private fun drawInstalledApp(c: Canvas, rect: RectF, app: AppItem) {
            drawMetalAppFrame(c, rect, "", true)
            val drawable = try {
                drawerIconCache.getOrPut(app.packageName) { packageManager.getApplicationIcon(app.packageName) }
            } catch (_: Exception) {
                null
            }
            if (drawable == null) {
                centered(c, app.glyph, rect.centerX(), rect.centerY() + 9f, 27f, color("#D7E0E7"))
                return
            }
            val inset = if (decayed) 9f else 7f
            val left = (rect.left + inset).toInt()
            val top = (rect.top + inset).toInt()
            val right = (rect.right - inset).toInt()
            val bottom = (rect.bottom - inset).toInt()
            c.save()
            drawable.setBounds(left, top, right, bottom)
            drawable.draw(c)
            c.restore()
        }

        private fun drawAppDrawer(c: Canvas) {
            val alpha = (drawerProgress * 220f).toInt().coerceIn(0, 220)
            paint.color = (alpha shl 24) or 0x0006090D
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            val bottom = designHeight - 48f
            rounded(c, RectF(14f, 74f, 406f, bottom), 18f, color(if (decayed) "#1B2632" else "#D9E0E5"), color(if (decayed) "#8493A0" else "#9AA8B4"), 2f)
            line(c, 42f, 140f, 378f, 140f, color(if (decayed) "#82909D" else "#8A9AA8"), .9f)
            centered(c, "❦", 210f, 132f, 19f, color("#AAB7C2"))

            // Search is displayed inside the library; tapping it opens a live-filter input.
            drawerSearchBounds = RectF(34f, 153f, 386f, 201f)
            rounded(c, drawerSearchBounds, 9f, color(if (decayed) "#101925" else "#E0E6EB"), color("#91A0AE"), 1.2f)
            val searchCaption = drawerSearchQuery.ifBlank { "Rechercher une application…" }
            drawText(c, searchCaption.take(31), 52f, 184f, 13f, color(if (decayed) "#CDD7DF" else "#334657"), italic = true)
            centered(c, "⌕", 365f, 186f, 23f, color(if (decayed) "#D1DAE2" else "#536A7D"))

            messageBounds = RectF(92f, 218f, 176f, 292f)
            dialBounds = RectF(244f, 218f, 328f, 292f)
            drawBatLetter(c, messageBounds.centerX(), messageBounds.centerY())
            drawTelephoneHorn(c, dialBounds.centerX(), dialBounds.centerY())

            drawerAppBounds.clear()
            val filtered = filteredDrawerApps()
            val top = 350f
            val rowStep = 105f
            val gridBottom = bottom - 52f
            val visibleRows = max(1, ((gridBottom - top) / rowStep).toInt() + 1)
            val totalRows = (filtered.size + 2) / 3
            val maxOffset = max(0, totalRows - visibleRows)
            drawerScrollOffset = drawerScrollOffset.coerceIn(0, maxOffset)
            val startIndex = drawerScrollOffset * 3
            val visible = filtered.drop(startIndex).take(visibleRows * 3)
            val xs = floatArrayOf(88f, 210f, 332f)
            visible.forEachIndexed { index, app ->
                val row = index / 3
                val col = index % 3
                val cx = xs[col]
                val cy = top + row * rowStep
                val rect = RectF(cx - 25f, cy - 25f, cx + 25f, cy + 25f)
                drawInstalledApp(c, rect, app)
                centered(c, app.label.take(16), cx, cy + 44f, 10.5f, color(if (decayed) "#CBD5DE" else "#304456"), italic = true)
                drawerAppBounds.add(RectF(cx - 49f, cy - 34f, cx + 49f, cy + 54f) to app)
            }
            if (filtered.isEmpty()) {
                centered(c, if (drawerSearchQuery.isBlank()) "Aucune autre application" else "Aucun résultat", 210f, top + 55f, 13f, color(if (decayed) "#CBD5DE" else "#304456"), italic = true)
            }
            if (drawerScrollOffset > 0) centered(c, "⌃", 390f, top - 25f, 18f, color("#9BAAB8"))
            if (drawerScrollOffset < maxOffset) centered(c, "⌄", 390f, gridBottom - 4f, 18f, color("#9BAAB8"))

            // The velvet remains at the sides when the library is fully open.
            if (drawerProgress < .98f) {
                val half = 210f * (1f - drawerProgress)
                drawCurtainPanel(c, RectF(0f, 0f, max(24f, half), designHeight), true)
                drawCurtainPanel(c, RectF(420f - max(24f, half), 0f, 420f, designHeight), false)
            } else {
                drawCurtainPanel(c, RectF(0f, 0f, 24f, designHeight), true)
                drawCurtainPanel(c, RectF(396f, 0f, 420f, designHeight), false)
            }
        }

        private fun drawMetalAppFrame(c: Canvas, rect: RectF, glyph: String, large: Boolean = false) {
            val radius = if (large) 11f else 8f
            val outer = RectF(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f)
            rounded(c, RectF(outer.left + 1.5f, outer.top + 3f, outer.right + 1.5f, outer.bottom + 3f), radius + 1f, color("#050A12"))
            paint.shader = LinearGradient(outer.left, outer.top, outer.right, outer.bottom, color("#D1D9E0"), color("#354656"), Shader.TileMode.CLAMP)
            c.drawRoundRect(outer, radius, radius, paint); paint.shader = null
            rounded(c, rect, radius - 1f, color(if (decayed) "#0C1520" else "#172433"), color("#BBC6D0"), if (large) 1.3f else 1f)
            val inner = RectF(rect.left + 3f, rect.top + 3f, rect.right - 3f, rect.bottom - 3f)
            rounded(c, inner, radius - 3f, Color.TRANSPARENT, color("#667A8B"), .65f)
            if (glyph.isNotBlank()) {
                val mark = if (large) 29f else 23f
                centered(c, glyph, rect.centerX(), rect.centerY() + mark * .30f, mark, color("#D5DEE6"))
            }
            val d = if (large) 2.2f else 1.6f
            for (pt in listOf(rect.left + 5f to rect.top + 5f, rect.right - 5f to rect.top + 5f, rect.left + 5f to rect.bottom - 5f, rect.right - 5f to rect.bottom - 5f)) {
                paint.color = color("#DAE2E9"); c.drawCircle(pt.first, pt.second, d, paint)
            }
        }

        private fun drawBatLetter(c: Canvas, cx: Float, cy: Float) {
            paint.color = color("#273748")
            val wings = Path().apply {
                moveTo(cx, cy - 4f); cubicTo(cx - 18f, cy - 28f, cx - 39f, cy - 23f, cx - 50f, cy - 5f)
                lineTo(cx - 34f, cy - 6f); lineTo(cx - 40f, cy + 12f); lineTo(cx - 18f, cy + 3f)
                lineTo(cx, cy + 15f); lineTo(cx + 18f, cy + 3f); lineTo(cx + 40f, cy + 12f)
                lineTo(cx + 34f, cy - 6f); lineTo(cx + 50f, cy - 5f); cubicTo(cx + 39f, cy - 23f, cx + 18f, cy - 28f, cx, cy - 4f); close()
            }
            c.drawPath(wings, paint)
            rounded(c, RectF(cx - 13f, cy - 17f, cx + 13f, cy + 16f), 2f, color("#D7E0E7"), color("#697D90"), 1f)
            line(c, cx - 9f, cy - 10f, cx, cy - 2f, color("#788C9E"), .8f)
            line(c, cx + 9f, cy - 10f, cx, cy - 2f, color("#788C9E"), .8f)
        }

        private fun drawTelephoneHorn(c: Canvas, cx: Float, cy: Float) {
            paint.color = color("#8D9EAD"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f; paint.strokeCap = Paint.Cap.ROUND
            val path = Path().apply { moveTo(cx - 22f, cy + 16f); cubicTo(cx - 3f, cy + 23f, cx + 4f, cy - 4f, cx + 23f, cy - 18f) }
            c.drawPath(path, paint); paint.style = Paint.Style.FILL
            paint.color = color("#8D9EAD")
            val bell = Path().apply { moveTo(cx + 12f, cy - 10f); lineTo(cx + 38f, cy - 31f); lineTo(cx + 47f, cy - 16f); lineTo(cx + 20f, cy + 1f); close() }
            c.drawPath(bell, paint)
            val mouth = Path().apply { moveTo(cx - 31f, cy + 8f); lineTo(cx - 18f, cy + 24f); lineTo(cx - 30f, cy + 34f); lineTo(cx - 43f, cy + 18f); close() }
            c.drawPath(mouth, paint)
            line(c, cx + 26f, cy - 28f, cx + 41f, cy - 15f, color("#D2DCE4"), 1.2f)
        }

        private fun drawOpenWatch(c: Canvas) {
            val progress = watchOpenProgress.coerceIn(0f, 1f)
            if (progress <= 0f) return
            paint.color = Color.argb((218f * progress).toInt(), 3, 8, 15); c.drawRect(0f, 0f, 420f, designHeight, paint)
            val panel = RectF(35f, 26f, 385f, designHeight - 22f)
            paint.alpha = (255f * progress).toInt()
            paint.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom, color("#1C2938"), color("#08111D"), Shader.TileMode.CLAMP)
            c.drawRoundRect(panel, 24f, 24f, paint); paint.shader = null
            rounded(c, RectF(39f, 30f, 381f, designHeight - 26f), 22f, Color.TRANSPARENT, color("#C0CBD4"), 1.7f)
            rounded(c, RectF(45f, 36f, 375f, designHeight - 32f), 18f, Color.TRANSPARENT, color("#586D80"), .7f)
            val image = pocketWatch
            if (image != null) c.drawBitmap(image, null, RectF(88f, 55f, 332f, 430f), paint)
            paint.alpha = 255
            val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
            centered(c, "${format.format(stepsToday)} pas", 210f, 475f, 25f, ivory(), italic = true)
            centered(c, "Objectif : ${format.format(goalSteps)}", 210f, 505f, 12f, color("#C4CFD8"), italic = true)
            drawFootstepMap(c, RectF(62f, 532f, 358f, designHeight - 68f))
        }

        private fun drawFootstepMap(c: Canvas, rect: RectF) {
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, color("#E3E8EC"), color("#BECAD4"), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, 11f, 11f, paint); paint.shader = null
            rounded(c, rect, 11f, Color.TRANSPARENT, color("#8191A0"), 1.1f)
            // Subtle parchment grain and a dotted wandering trail.
            for (i in 0..16) {
                val y = rect.top + 12f + i * 9f
                line(c, rect.left + 9f, y, rect.right - 9f, y, color("#9EACB8"), .18f)
            }
            val trail = Path().apply {
                moveTo(rect.left + 28f, rect.bottom - 22f)
                cubicTo(rect.left + 2f, rect.bottom - 73f, rect.left + 110f, rect.bottom - 96f, rect.left + 132f, rect.bottom - 65f)
                cubicTo(rect.right - 5f, rect.bottom - 17f, rect.right - 11f, rect.top + 92f, rect.right - 71f, rect.top + 73f)
                cubicTo(rect.left + 121f, rect.top + 51f, rect.left + 91f, rect.top + 20f, rect.right - 38f, rect.top + 28f)
            }
            paint.color = color("#7B8D9D"); paint.alpha = 90; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.1f
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 6f), 0f)
            c.drawPath(trail, paint); paint.pathEffect = null; paint.style = Paint.Style.FILL; paint.alpha = 255
            val measure = PathMeasure(trail, false)
            val pos = FloatArray(2); val tan = FloatArray(2)
            val count = 20
            val complete = (stepsToday / 500L).toInt().coerceIn(0, count)
            val partial = (stepsToday % 500L).toFloat() / 500f
            val length = measure.length
            for (i in 0 until count) {
                val distance = length * (i + .5f) / count
                measure.getPosTan(distance, pos, tan)
                val angle = Math.toDegrees(kotlin.math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat() + 90f
                val side = if (i % 2 == 0) -4f else 4f
                val px = pos[0] - tan[1] * side
                val py = pos[1] + tan[0] * side
                val alpha = when { i < complete -> 235; i == complete -> (55f + partial * 150f).toInt(); else -> 38 }
                drawFootprint(c, px, py, angle, alpha)
            }
            centered(c, "✦", rect.centerX(), rect.bottom - 8f, 11f, color("#6E8193"))
        }

        private fun drawFootprint(c: Canvas, x: Float, y: Float, rotation: Float, alpha: Int) {
            c.save(); c.rotate(rotation, x, y)
            val foot = Path().apply {
                moveTo(-3.4f, 9f)
                cubicTo(-7.2f, 5f, -6.7f, -2f, -4.9f, -8f)
                cubicTo(-3.6f, -13f, -1.2f, -15f, 1.5f, -13.2f)
                cubicTo(5.5f, -10f, 6.4f, -2f, 4.8f, 4f)
                cubicTo(3.7f, 10f, 0f, 13f, -3.4f, 9f)
                close()
            }
            paint.color = color("#1C2A39"); paint.alpha = alpha; paint.style = Paint.Style.FILL
            c.drawPath(foot, paint)
            // Five tiny toe pads make the silhouette read as a footprint, not an oval.
            for (toe in 0..4) {
                val tx = -4.3f + toe * 2.2f
                val ty = -12.2f - (if (toe == 2) 2.0f else if (toe == 1 || toe == 3) .8f else 0f)
                c.drawCircle(tx, ty, if (toe == 2) 1.45f else 1.15f, paint)
            }
            paint.color = color("#52677C"); paint.alpha = (alpha * .7f).toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = .55f
            c.drawPath(foot, paint)
            paint.alpha = 255; paint.style = Paint.Style.FILL; c.restore()
        }

        private fun drawOpenBookPageMarker(c: Canvas, pageIndex: Int, rect: RectF) {
            centered(c, if (pageIndex % 2 == 0) "I" else "II", rect.centerX(), rect.bottom - 9f, 9f, color("#718597"), italic = true)
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
            if (open) refreshDrawerApps()
            drawerAnimator?.cancel()
            val target = if (open) 1f else 0f
            drawerAnimator = ValueAnimator.ofFloat(drawerProgress, target).apply {
                duration = 620L
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawerProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun animateWatch(open: Boolean) {
            watchAnimator?.cancel()
            if (open) {
                watchOpen = true
                refreshHealthConnect()
                requestHealthPermissionIfNeeded()
            }
            val target = if (open) 1f else 0f
            watchAnimator = ValueAnimator.ofFloat(watchOpenProgress, target).apply {
                duration = if (open) 480L else 330L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    watchOpenProgress = it.animatedValue as Float
                    if (!open && watchOpenProgress <= 0.001f) watchOpen = false
                    invalidate()
                }
                start()
            }
        }

        private fun requestHealthPermissionIfNeeded() {
            if (!healthPermissionGranted) requestHealthPermission()
        }

        fun closeOverlay(): Boolean {
            return when {
                drawerProgress > .02f -> { animateDrawer(false); true }
                bookOpen -> { bookOpen = false; invalidate(); true }
                watchOpen -> { animateWatch(false); true }
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
            val pageRect = if (pageIndex % 2 == 0) RectF(20f, 78f, 207f, bottom) else RectF(213f, 78f, 400f, bottom)
            val startY = pageRect.top + 93f
            val rowStep = 48f
            if (y >= bottom - 39f) { editEntry(pageIndex, -1); return }
            val visible = ((y - startY + rowStep * .45f) / rowStep).toInt()
            val index = visible + bookScroll
            val page = bookPages[pageIndex]
            if (index in page.indices) {
                if (x >= pageRect.right - 38f) {
                    confirmDeleteEntry(pageIndex, index)
                } else if (x <= pageRect.left + 31f && pageIndex < 2) {
                    page[index].done = !page[index].done; saveNotebook(); invalidate()
                } else if (duration > 550L || pageIndex >= 2) {
                    editEntry(pageIndex, index)
                } else {
                    page[index].done = !page[index].done; saveNotebook(); invalidate()
                }
            }
        }

        private fun confirmDeleteEntry(pageIndex: Int, index: Int) {
            if (index !in bookPages[pageIndex].indices) return
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Supprimer cette entrée ?")
                .setMessage(bookPages[pageIndex][index].text)
                .setNegativeButton("Garder", null)
                .setPositiveButton("Supprimer") { _, _ ->
                    if (index in bookPages[pageIndex].indices) bookPages[pageIndex].removeAt(index)
                    saveNotebook(); invalidate()
                }
                .show()
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
                        if (y >= designHeight - 48f || y < 70f || (dy > 65f && downY < 120f)) { animateDrawer(false); return true }
                        if (drawerSearchBounds.contains(x, y)) { showDrawerSearch(); return true }
                        if (messageBounds.contains(x, y)) { openMessages(); return true }
                        if (dialBounds.contains(x, y)) { openDialer(); return true }
                        if (abs(dy) > 28f) {
                            val filteredCount = filteredDrawerApps().size
                            val rows = max(1, ((designHeight - 48f - 52f - 350f) / 105f).toInt() + 1)
                            val maxOffset = max(0, (filteredCount + 2) / 3 - rows)
                            val amount = max(1, (abs(dy) / 105f).toInt())
                            drawerScrollOffset = (drawerScrollOffset + if (dy < 0f) amount else -amount).coerceIn(0, maxOffset)
                            invalidate(); return true
                        }
                        drawerAppBounds.firstOrNull { it.first.contains(x, y) }?.let { launchApp(it.second.packageName, it.second.label); return true }
                        return true
                    } else if (drawerProgress > .02f) return true

                    if (bookOpen) { handleBookTap(x, y, dx, dy, duration); return true }
                    if (watchOpen) {
                        animateWatch(false)
                        return true
                    }
                    if (y >= designHeight - 52f || (dy < -42f && downY > designHeight - 210f)) { animateDrawer(true); return true }
                    if (taskBounds.contains(downX, downY) || taskBounds.contains(x, y)) {
                        handleHomeNotebookTap(y, dx, duration); return true
                    }
                    if (musicBounds.contains(x, y)) {
                        when {
                            x < 128f -> openMusic()
                            x < 187f -> controlMusic("previous")
                            x < 234f -> controlMusic("toggle")
                            else -> controlMusic("next")
                        }
                        invalidate(); return true
                    }
                    if (watchBounds.contains(x, y)) { animateWatch(true); return true }
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
