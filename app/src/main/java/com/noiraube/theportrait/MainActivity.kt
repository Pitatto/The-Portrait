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
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
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
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var portraitView: PortraitView
    private lateinit var rootFrame: FrameLayout
    private var inlineSearchView: EditText? = null
    private var inlineSearchIsDrawer = false
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>
    private val healthPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private var stepsToday = 0L
    private var healthPermissionGranted = false
    private var mediaController: MediaController? = null
    private var mediaTitle = ""
    private var mediaArtist = ""
    private var mediaArtwork: Bitmap? = null
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        healthPermissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            healthPermissionGranted = granted.containsAll(healthPermissions)
            refreshHealthConnect()
        }
        portraitView = PortraitView(this)
        rootFrame = FrameLayout(this)
        rootFrame.addView(portraitView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootFrame)
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
        if (inlineSearchView != null) {
            dismissInlineSearch()
            return
        }
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
            mediaArtwork = null
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
            mediaArtwork = selected?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: selected?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: selected?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            mediaPlaying = selected?.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: Exception) {
            mediaController = null
            mediaTitle = ""
            mediaArtist = ""
            mediaArtwork = null
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
        showInlineSearch(forDrawer = false)
    }

    private fun showInlineSearch(forDrawer: Boolean) {
        if (!::rootFrame.isInitialized || !::portraitView.isInitialized) return
        dismissInlineSearch()

        val rect = portraitView.inlineSearchRect(forDrawer)
        val scale = portraitView.currentDesignScale()
        val input = EditText(this).apply {
            hint = if (forDrawer) "Rechercher une application…" else "Rechercher sur Google…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 13f
            typeface = Typeface.create("serif", Typeface.ITALIC)
            setPadding(0, 0, 0, 0)
            setTextColor(portraitView.inlineSearchTextColor(forDrawer))
            setHintTextColor(Color.rgb(112, 128, 143))
            setBackgroundColor(Color.TRANSPARENT)
            if (forDrawer) {
                setText(portraitView.currentDrawerSearchQuery())
                setSelection(text.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (forDrawer) portraitView.updateDrawerSearchQuery(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (!submit) false else {
                    val query = text.toString().trim()
                    if (forDrawer) {
                        portraitView.updateDrawerSearchQuery(query)
                        dismissInlineSearch()
                    } else {
                        dismissInlineSearch()
                        if (query.isNotEmpty()) {
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")))
                        }
                    }
                    true
                }
            }
        }
        val params = FrameLayout.LayoutParams(
            (rect.width() * scale).toInt().coerceAtLeast(80),
            (rect.height() * scale).toInt().coerceAtLeast(32),
            Gravity.TOP or Gravity.LEFT
        ).apply {
            leftMargin = (rect.left * scale).toInt()
            topMargin = (rect.top * scale).toInt()
        }
        inlineSearchIsDrawer = forDrawer
        inlineSearchView = input
        portraitView.setSearchEditing(forDrawer, true)
        rootFrame.addView(input, params)
        input.requestFocus()
        input.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dismissInlineSearch() {
        val input = inlineSearchView ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        if (::rootFrame.isInitialized) rootFrame.removeView(input)
        if (::portraitView.isInitialized) portraitView.setSearchEditing(inlineSearchIsDrawer, false)
        inlineSearchView = null
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
        if (inlineSearchView != null) dismissInlineSearch()
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
        private val pageTitles = listOf("Tâches journalières", "Liste de courses", "Notes", "À venir")
        private val bookPages = mutableListOf<MutableList<BookEntry>>()
        private val taskBounds = RectF(14f, 456f, 211f, 634f)
        private val watchBounds = RectF(270f, 438f, 418f, 638f)
        private val musicBounds = RectF(56f, 364f, 364f, 448f)
        private val searchBounds = RectF(16f, 638f, 404f, 683f)
        private var googleSearchEditing = false
        private var drawerSearchEditing = false
        private val appBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val drawerAppBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val drawerApps = mutableListOf<AppItem>()
        private val drawerIconCache = mutableMapOf<String, Drawable>()
        private var drawerSearchBounds = RectF()
        private var messageBounds = RectF()
        private var dialBounds = RectF()
        private var bowPhase = 0f
        private val vampirePortrait: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.portrait_vampire_oil) } catch (_: Exception) { null }
        private val catLetterBitmap: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.tuxedo_cat_letter) } catch (_: Exception) { null }
        private val telephoneBitmap: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.antique_telephone) } catch (_: Exception) { null }
        private val oldBookPaper: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.old_book_paper) } catch (_: Exception) { null }
        private val pocketWatch: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.pocket_watch) } catch (_: Exception) { null }
        private val closedPocketWatch: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.pocket_watch_closed) } catch (_: Exception) { null }
        private val wallpaperBitmap: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.the_portrait_wallpaper) } catch (_: Exception) { null }
        private val portraitFrame: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.portrait_frame_silver) } catch (_: Exception) { null }
        private val homeIconCache = mutableMapOf<String, Drawable>()
        private val silverIconFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.18f)
            postConcat(ColorMatrix(floatArrayOf(
                .94f, 0f, 0f, 0f, 5f,
                0f, .98f, 0f, 0f, 9f,
                0f, 0f, 1.04f, 0f, 15f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
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

        fun currentDesignScale(): Float = scaleFactor.coerceAtLeast(0.1f)
        fun inlineSearchRect(forDrawer: Boolean): RectF = if (forDrawer)
            RectF(52f, 161f, 344f, 194f) else RectF(72f, 645f, 344f, 677f)
        fun inlineSearchTextColor(forDrawer: Boolean): Int =
            if (forDrawer) color("#314456") else color("#D7E0E7")
        fun currentDrawerSearchQuery(): String = drawerSearchQuery
        fun updateDrawerSearchQuery(value: String) {
            drawerSearchQuery = value
            drawerScrollOffset = 0
            invalidate()
        }
        fun setSearchEditing(forDrawer: Boolean, editing: Boolean) {
            if (forDrawer) drawerSearchEditing = editing else googleSearchEditing = editing
            invalidate()
        }

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
            paint.color = 0x33233342
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, designHeight, 0x22000000, 0x66000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 420f, designHeight, paint); paint.shader = null
            // A fine silver hairline at the edge gives the wallpaper a framed finish.
            line(c, 8f, 8f, 8f, designHeight - 8f, color("#596878"), .55f)
            line(c, 412f, 8f, 412f, designHeight - 8f, color("#596878"), .55f)
        }

        private fun ivory() = color("#D3D7D8")

        private fun drawHeader(c: Canvas) {
            val day = SimpleDateFormat("EEEE", Locale.FRENCH).format(Date()).replaceFirstChar { it.uppercase() }
            val date = SimpleDateFormat("dd MMMM", Locale.FRENCH).format(Date())
            drawText(c, day, 24f, 62f, 29f, ivory(), italic = true)
            drawText(c, date, 25f, 96f, 21f, ivory(), italic = true)
            line(c, 24f, 111f, 236f, 111f, color("#8998A8"), 1f)
            centered(c, "✥", 130f, 115f, 8f, color("#C4CDD6"))
        }

        private fun drawPortrait(c: Canvas) {
            val aperture = RectF(145f, 143f, 275f, 318f)
            paint.color = color("#0B111B")
            c.drawOval(RectF(140f, 137f, 280f, 324f), paint)
            vampirePortrait?.let { image ->
                val oval = Path().apply { addOval(aperture, Path.Direction.CW) }
                c.save(); c.clipPath(oval)
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(image, null, aperture, paint)
                c.restore()
            }
            portraitFrame?.let {
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(it, null, RectF(123f, 84f, 297f, 361f), paint)
            } ?: run {
                paint.color = color("#AEBAC6"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f
                c.drawOval(RectF(140f, 137f, 280f, 324f), paint); paint.style = Paint.Style.FILL
            }
        }


        private fun drawMusicPanel(c: Canvas) {
            val bounds = RectF(56f, 364f, 364f, 448f)
            paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                color("#121F30"), color("#080F19"), Shader.TileMode.CLAMP)
            c.drawRoundRect(bounds, 13f, 13f, paint); paint.shader = null
            rounded(c, RectF(59f, 367f, 361f, 445f), 11f, Color.TRANSPARENT, color("#B5C0CB"), 1.35f)
            rounded(c, RectF(64f, 372f, 356f, 440f), 9f, Color.TRANSPARENT, color("#536171"), .65f)

            val cover = RectF(70f, 378f, 112f, 420f)
            drawMusicCover(c, cover)

            // The title and artist live in their own lane, clear of the portrait frame.
            if (mediaTitle.isNotBlank()) {
                centered(c, mediaTitle.take(30), 238f, 389f, 11.7f, ivory(), italic = true)
                if (mediaArtist.isNotBlank()) centered(c, mediaArtist.take(28), 238f, 405f, 9.8f, color("#AEBBC8"), italic = true)
            }

            centered(c, "|◀", 158f, 435f, 13.5f, ivory())
            centered(c, if (mediaPlaying) "Ⅱ" else "▶", 210f, 437f, 18f, color("#D7E0E7"))
            centered(c, "▶|", 262f, 435f, 13.5f, ivory())
            line(c, 137f, 442f, 283f, 442f, color("#8796A6"), .65f)
            for (cx in listOf(68f, 352f)) centered(c, "✧", cx, 377f, 8f, color("#C7D0D8"))
        }

        private fun drawMusicCover(c: Canvas, rect: RectF) {
            rounded(c, rect, 5f, color("#182536"), color("#AAB8C5"), .9f)
            val art = mediaArtwork
            if (art != null) {
                val clip = Path().apply { addRoundRect(rect, 4f, 4f, Path.Direction.CW) }
                c.save(); c.clipPath(clip)
                paint.shader = null; paint.colorFilter = null; paint.alpha = 255
                c.drawBitmap(art, null, rect, paint)
                c.restore()
            } else {
                paint.color = color("#AAB8C5"); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
                c.drawCircle(rect.centerX(), rect.centerY(), 11f, paint); paint.style = Paint.Style.FILL
                centered(c, "♪", rect.centerX(), rect.centerY() + 6f, 17f, color("#D7E0E7"))
            }
            rounded(c, rect, 5f, Color.TRANSPARENT, color("#C4CED7"), .7f)
        }

        private fun drawNotebookPreview(c: Canvas) {
            val bounds = RectF(14f, 456f, 211f, 634f)
            paint.shader = LinearGradient(14f, 456f, 211f, 634f, color("#6E7883"), color("#202F3D"), Shader.TileMode.CLAMP)
            c.drawRoundRect(bounds, 11f, 11f, paint); paint.shader = null
            rounded(c, RectF(19f, 461f, 206f, 629f), 8f, color("#202B36"), color("#AEB9C2"), 1.1f)
            val pageRect = RectF(24f, 466f, 201f, 624f)
            oldBookPaper?.let { c.drawBitmap(it, null, pageRect, paint) } ?: rounded(c, pageRect, 4f, color("#D8D3C8"), color("#8D969D"), .8f)
            rounded(c, pageRect, 4f, Color.TRANSPARENT, color("#8F999F"), .65f)
            line(c, 33f, 472f, 33f, 618f, color("#8B969E"), 1.2f)
            drawText(c, pageTitles[bookPage], 43f, 483f, 15f, color("#273746"), italic = true)
            centered(c, "›", 193f, 483f, 21f, color("#657687"))
            line(c, 41f, 491f, 190f, 491f, color("#7D8991"), .75f)
            centered(c, "✥", 114f, 495f, 8f, color("#82909A"))

            val page = bookPages[bookPage]
            val baseline = 519f
            val rowStep = 25.5f
            page.drop(bookScroll).take(4).forEachIndexed { visible, entry ->
                val y = baseline + visible * rowStep
                line(c, 41f, y + 5f, 190f, y + 5f, color("#87939B"), .42f)
                drawCheckbox(c, 42f, y - 12f, entry.done, 13f)
                drawText(c, entry.text.take(19), 64f, y, 12.1f, color("#273746"), handwritten = true)
            }
            drawText(c, "+ Ajouter…", 64f, 617f, 11f, color("#455969"), italic = true)
            centered(c, "✦", 185f, 619f, 12f, color("#68798A"))
        }


        private fun drawBitmapContained(c: Canvas, bitmap: Bitmap?, bounds: RectF) {
            if (bitmap == null) return
            val scale = min(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val dst = RectF(bounds.centerX() - w / 2f, bounds.centerY() - h / 2f,
                bounds.centerX() + w / 2f, bounds.centerY() + h / 2f)
            paint.shader = null; paint.colorFilter = null; paint.alpha = 255
            c.drawBitmap(bitmap, null, dst, paint)
        }

        private fun drawCheckbox(c: Canvas, x: Float, y: Float, done: Boolean, size: Float) {
            rounded(c, RectF(x, y, x + size, y + size), 2f, Color.TRANSPARENT, color("#4E5E6E"), 1f)
            if (done) {
                line(c, x + 2f, y + size * .55f, x + size * .42f, y + size - 2f, color("#6E414C"), 1.7f)
                line(c, x + size * .42f, y + size - 2f, x + size - 1f, y + 2f, color("#6E414C"), 1.7f)
            }
        }

        private fun drawPocketWatch(c: Canvas) {
            val image = closedPocketWatch ?: pocketWatch
            if (image != null) {
                // Matching closed state: full body and chain remain visible above the search bar.
                val dst = RectF(278f, 440f, 410f, 605f)
                paint.alpha = 255; paint.colorFilter = null; paint.shader = null
                c.drawBitmap(image, null, dst, paint)
            }
            val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
            centered(c, "${format.format(stepsToday)} pas", 345f, 632f, 13.5f,
                ivory(), italic = true)
        }

        private fun drawSearch(c: Canvas) {
            rounded(c, RectF(16f, 638f, 404f, 683f), 8f, color("#182535"), color("#AAB7C3"), 1.1f)
            rounded(c, RectF(20f, 642f, 400f, 679f), 6f, Color.TRANSPARENT, color("#526375"), .55f)
            centered(c, "G", 47f, 668f, 23f, color("#D4DCE3"), italic = true)
            if (!googleSearchEditing) drawText(c, "Rechercher sur Google…", 72f, 667f, 13f, color("#C9D2DB"), italic = true)
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
                    drawSilverAppIcon(c, icon, rect, 7f)
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
            oldBookPaper?.let { c.drawBitmap(it, null, rect, paint) } ?: run {
                paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(color("#E0DDD4"), color("#C9C5BA"), color("#E5E1D7")), null, Shader.TileMode.CLAMP)
                c.drawRect(rect, paint); paint.shader = null
            }
            paint.color = color("#87939B"); paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
            c.drawRect(rect, paint); paint.style = Paint.Style.FILL
            val index = pageIndex.coerceIn(0, 3)
            centered(c, pageTitles[index], rect.centerX(), rect.top + 42f, 12.5f, color("#2C3946"), italic = true)
            line(c, rect.left + 13f, rect.top + 52f, rect.right - 13f, rect.top + 52f, color("#8F999E"), .8f)
            val entries = bookPages[index]
            val startY = rect.top + 93f
            val rowStep = 48f
            val visibleCount = max(1, ((rect.bottom - startY - 38f) / rowStep).toInt())
            for (i in 0 until visibleCount) {
                val guideY = startY + i * rowStep + 6f
                line(c, rect.left + 12f, guideY, rect.right - 12f, guideY, color("#87939B"), .45f)
            }
            entries.drop(bookScroll).take(visibleCount).forEachIndexed { visible, entry ->
                val y = startY + visible * rowStep
                drawCheckbox(c, rect.left + 13f, y - 15f, entry.done, 13f)
                drawText(c, entry.text.take(22), rect.left + 33f, y, 12.5f, color("#27333D"), handwritten = true)
                rounded(c, RectF(rect.right - 31f, y - 15f, rect.right - 8f, y + 5f), 5f,
                    color("#DBD7CC"), color("#89949A"), .6f)
                centered(c, "×", rect.right - 19.5f, y + 1f, 14f, color("#6A5354"))
            }
            drawText(c, "+ Ajouter…", rect.left + 24f, rect.bottom - 18f, 12f, color("#455969"), italic = true)
            centered(c, "✧", rect.right - 18f, rect.bottom - 17f, 12f, color("#71808B"))
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
            this@MainActivity.showInlineSearch(forDrawer = true)
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
            drawSilverAppIcon(c, drawable, rect, 7f)
        }

        private fun drawSilverAppIcon(c: Canvas, drawable: Drawable, rect: RectF, inset: Float) {
            val oldFilter = drawable.colorFilter
            drawable.colorFilter = silverIconFilter
            c.save()
            drawable.setBounds((rect.left + inset).toInt(), (rect.top + inset).toInt(),
                (rect.right - inset).toInt(), (rect.bottom - inset).toInt())
            drawable.draw(c)
            c.restore()
            drawable.colorFilter = oldFilter
        }

        private fun drawAppDrawer(c: Canvas) {
            val alpha = (drawerProgress * 220f).toInt().coerceIn(0, 220)
            paint.color = (alpha shl 24) or 0x0006090D
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            val bottom = designHeight - 48f
            rounded(c, RectF(14f, 74f, 406f, bottom), 18f, color("#D9E0E5"), color("#9AA8B4"), 2f)
            line(c, 42f, 140f, 378f, 140f, color("#8A9AA8"), .9f)
            centered(c, "❦", 210f, 132f, 19f, color("#AAB7C2"))

            // Search is displayed inside the library; tapping it opens a live-filter input.
            drawerSearchBounds = RectF(34f, 153f, 386f, 201f)
            rounded(c, drawerSearchBounds, 9f, color("#E0E6EB"), color("#91A0AE"), 1.2f)
            val searchCaption = drawerSearchQuery.ifBlank { "Rechercher une application…" }
            if (!drawerSearchEditing) drawText(c, searchCaption.take(31), 52f, 184f, 13f, color("#334657"), italic = true)
            centered(c, "⌕", 365f, 186f, 23f, color("#536A7D"))

            messageBounds = RectF(76f, 208f, 188f, 330f)
            dialBounds = RectF(232f, 208f, 344f, 330f)
            drawTuxedoCatLetter(c, messageBounds.centerX(), messageBounds.centerY())
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
                centered(c, app.label.take(16), cx, cy + 44f, 10.5f, color("#304456"), italic = true)
                drawerAppBounds.add(RectF(cx - 49f, cy - 34f, cx + 49f, cy + 54f) to app)
            }
            if (filtered.isEmpty()) {
                centered(c, if (drawerSearchQuery.isBlank()) "Aucune autre application" else "Aucun résultat", 210f, top + 55f, 13f, color("#304456"), italic = true)
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
            rounded(c, rect, radius - 1f, color("#172433"), color("#BBC6D0"), if (large) 1.3f else 1f)
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

        private fun drawTuxedoCatLetter(c: Canvas, cx: Float, cy: Float) {
            val bounds = RectF(cx - 53f, cy - 64f, cx + 53f, cy + 64f)
            drawBitmapContained(c, catLetterBitmap, bounds)
        }


        private fun drawTelephoneHorn(c: Canvas, cx: Float, cy: Float) {
            val bounds = RectF(cx - 56f, cy - 66f, cx + 56f, cy + 66f)
            drawBitmapContained(c, telephoneBitmap, bounds)
        }


        private fun drawOpenWatch(c: Canvas) {
            val progress = watchOpenProgress.coerceIn(0f, 1f)
            if (progress <= 0f) return
            paint.color = Color.argb((218f * progress).toInt(), 3, 8, 15)
            c.drawRect(0f, 0f, 420f, designHeight, paint)
            val panel = RectF(35f, 26f, 385f, designHeight - 22f)
            paint.alpha = (255f * progress).toInt()
            paint.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                color("#1C2938"), color("#08111D"), Shader.TileMode.CLAMP)
            c.drawRoundRect(panel, 24f, 24f, paint); paint.shader = null
            rounded(c, RectF(39f, 30f, 381f, designHeight - 26f), 22f,
                Color.TRANSPARENT, color("#C0CBD4"), 1.7f)
            rounded(c, RectF(45f, 36f, 375f, designHeight - 32f), 18f,
                Color.TRANSPARENT, color("#586D80"), .7f)

            val watchRect = RectF(82f, 46f, 338f, 366f)
            pocketWatch?.let {
                paint.alpha = 255; paint.colorFilter = null; paint.shader = null
                c.drawBitmap(it, null, watchRect, paint)
            }
            paint.alpha = 255
            drawLiveClockFace(c, watchRect)

            val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
            centered(c, "${format.format(stepsToday)} pas", 210f, 415f, 25f, ivory(), italic = true)
            // Recompute the phone's local time every second while the watch is open.
            if (watchOpen && watchOpenProgress > 0.98f) postInvalidateDelayed(1000L)
        }

        private fun drawLiveClockFace(c: Canvas, watchRect: RectF) {
            // Coordinates match the empty dial aperture in the open pocket-watch artwork.
            // Measured against the actual cream dial in pocket_watch.webp (760 × 950).
            // These values align the live dial with the watch bezel, not the artwork's full canvas.
            val cx = watchRect.left + watchRect.width() * (303f / 760f)
            val cy = watchRect.top + watchRect.height() * (502f / 950f)
            val rx = watchRect.width() * (205f / 760f)
            val ry = watchRect.height() * (205f / 950f)
            val face = RectF(cx - rx, cy - ry, cx + rx, cy + ry)

            paint.shader = RadialGradient(cx - rx * .22f, cy - ry * .28f, rx * 1.35f,
                intArrayOf(color("#F0EADD"), color("#DDD5C4"), color("#C2B9A7")),
                floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
            paint.colorFilter = null; paint.alpha = 255
            c.drawOval(face, paint); paint.shader = null
            paint.color = color("#766E60"); paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
            c.drawOval(RectF(face.left + 1.4f, face.top + 1.4f, face.right - 1.4f, face.bottom - 1.4f), paint)
            c.drawOval(RectF(face.left + 4.2f, face.top + 4.2f, face.right - 4.2f, face.bottom - 4.2f), paint)
            paint.style = Paint.Style.FILL

            for (tick in 0 until 60) {
                val angle = Math.toRadians(tick * 6.0 - 90.0)
                val ux = cos(angle).toFloat(); val uy = sin(angle).toFloat()
                val outerX = cx + ux * (rx * .88f)
                val outerY = cy + uy * (ry * .88f)
                val innerRadius = if (tick % 5 == 0) .78f else .845f
                val innerX = cx + ux * (rx * innerRadius)
                val innerY = cy + uy * (ry * innerRadius)
                line(c, outerX, outerY, innerX, innerY,
                    color(if (tick % 5 == 0) "#514A40" else "#8B8170"), if (tick % 5 == 0) .9f else .45f)
            }
            val roman = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")
            for (i in 0 until 12) {
                val angle = Math.toRadians(i * 30.0 - 90.0)
                val tx = cx + cos(angle).toFloat() * (rx * .68f)
                val ty = cy + sin(angle).toFloat() * (ry * .68f) + 2.8f
                centered(c, roman[i], tx, ty, if (i % 3 == 0) 8.3f else 7.4f, color("#292923"))
            }

            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY) % 12
            val minute = now.get(Calendar.MINUTE)
            val second = now.get(Calendar.SECOND)
            val millis = now.get(Calendar.MILLISECOND)
            val seconds = second + millis / 1000f
            val minutes = minute + seconds / 60f
            val hours = hour + minutes / 60f
            val hourAngle = Math.toRadians(hours * 30.0 - 90.0)
            val minuteAngle = Math.toRadians(minutes * 6.0 - 90.0)
            val secondAngle = Math.toRadians(seconds * 6.0 - 90.0)

            fun hand(angle: Double, length: Float, tail: Float, width: Float, tint: Int) {
                val ux = cos(angle).toFloat(); val uy = sin(angle).toFloat()
                line(c, cx - ux * tail, cy - uy * tail, cx + ux * length, cy + uy * length, tint, width)
                paint.color = tint
                c.drawCircle(cx + ux * length, cy + uy * length, width * .72f, paint)
            }
            hand(hourAngle, rx * .48f, rx * .08f, 2.7f, color("#283A49"))
            hand(minuteAngle, rx * .72f, rx * .09f, 1.8f, color("#344B5C"))
            hand(secondAngle, rx * .80f, rx * .16f, .75f, color("#744A50"))
            paint.color = color("#263A4A"); c.drawCircle(cx, cy, 3.6f, paint)
            paint.color = color("#D5D9D7"); c.drawCircle(cx - .7f, cy - .7f, 1.1f, paint)
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
            if (open) refreshDrawerApps() else if (inlineSearchView != null) dismissInlineSearch()
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
                inlineSearchView != null -> { dismissInlineSearch(); true }
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
            val visible = ((y - startY + rowStep * .5f) / rowStep).toInt()
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
            if (y < 496f || abs(dx) > 30f) {
                bookPage = if (bookPage % 2 == 0) bookPage else bookPage - 1
                bookOpen = true; bookScroll = 0; invalidate(); return
            }
            if (y >= 611f) { editEntry(bookPage, -1); return }
            val index = ((y - 519f + 12f) / 25.5f).toInt() + bookScroll
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
                            x < 126f -> openMusic() // album cover
                            x < 184f -> controlMusic("previous")
                            x < 242f -> controlMusic("toggle")
                            else -> controlMusic("next")
                        }
                        invalidate(); return true
                    }
                    if (watchBounds.contains(x, y)) { animateWatch(true); return true }
                    if (searchBounds.contains(x, y)) { launchSearchDialog(); return true }
                    appBounds.firstOrNull { it.first.contains(x, y) }?.let { launchApp(it.second.packageName, it.second.label); return true }
                    // One permanent painted portrait: horizontal swipes no longer switch visual states.
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
