package it.macrocam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.ImageFormat
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private val GREEN = 0xFF1FE01F.toInt()
private val MULS = floatArrayOf(1f, 0.7f, 0.55f)   // moltiplicatori della distanza minima di fuoco

/** Slider verticale. f = 0 in alto, 1 in basso. shutterLook = aspetto da pulsante di scatto (fuoco). */
class VSlider(c: Context, private val thumbR: Float, private val shutterLook: Boolean) : View(c) {
    var f = 0f
    var label = ""
    var labelRight = true
    var snap = 0f
    var topText = ""
    var botText = ""
    var showDot = false
    var dotOn = false
    var onDot: () -> Unit = {}
    var onChange: (Float) -> Unit = {}
    var onTap: () -> Unit = {}
    var onDouble: () -> Unit = {}

    private val d = c.resources.displayMetrics.density
    private val padV get() = thumbR + 14 * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rel = false
    private var onThumb = false
    private var moved = false
    private var y0 = 0f
    private var f0 = 0f
    private var lastTap = 0L
    private var dotDown = false

    /** Il pallino sta subito sotto la manopola (sopra, se la manopola è in fondo). */
    private fun dotY(): Float {
        val y = ty()
        val below = y + thumbR + 16 * d
        return if (below + 11 * d <= height - 12 * d) below else y - thumbR - 16 * d
    }

    private fun hitDot(e: MotionEvent) = showDot && abs(e.x - width / 2f) <= 24 * d && abs(e.y - dotY()) <= 24 * d

    private fun ty() = padV + f * (height - 2 * padV)

    private fun setFrom(v: Float) {
        var n = v.coerceIn(0f, 1f)
        if (snap > 0f && abs(n - 0.5f) < snap) n = 0.5f
        f = n
    }

    override fun onDraw(cv: Canvas) {
        val x = width / 2f
        val y = ty()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x80FFFFFF.toInt()
        cv.drawLine(x, padV, x, height - padV, p)

        p.style = Paint.Style.FILL
        if (shutterLook) {
            p.color = Color.BLACK; cv.drawCircle(x, y, thumbR, p)
            p.color = Color.WHITE; cv.drawCircle(x, y, thumbR * 0.88f, p)
            p.color = Color.BLACK; cv.drawCircle(x, y, thumbR * 0.70f, p)
            p.color = Color.WHITE; cv.drawCircle(x, y, thumbR * 0.63f, p)
            if (label.isNotEmpty()) {
                p.textSize = 13 * d
                p.textAlign = Paint.Align.LEFT
                val tw = p.measureText(label)
                val m = 8 * d
                val left = if (labelRight) x + thumbR + 6 * d else x - thumbR - 6 * d - tw - 2 * m
                p.color = 0xB3000000.toInt()
                cv.drawRoundRect(left, y - 13 * d, left + tw + 2 * m, y + 13 * d, 10 * d, 10 * d, p)
                p.color = Color.WHITE
                cv.drawText(label, left + m, y + 5 * d, p)
            }
        } else {
            p.color = Color.BLACK; cv.drawCircle(x, y, thumbR, p)
            p.color = Color.WHITE; cv.drawCircle(x, y, thumbR - 3.5f * d, p)
            p.textSize = 13 * d
            p.textAlign = Paint.Align.CENTER
            p.color = Color.BLACK
            cv.drawText(label, x, y + p.textSize / 3, p)
        }

        if (showDot) {
            val dy = dotY()
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            cv.drawCircle(x, dy, 10 * d, p)
            p.color = if (dotOn) GREEN else Color.WHITE
            cv.drawCircle(x, dy, 7.5f * d, p)
        }

        p.style = Paint.Style.FILL
        p.textSize = 10 * d
        p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
        p.setShadowLayer(3 * d, 0f, 1f, Color.BLACK)
        cv.drawText(topText, x, 11 * d, p)
        cv.drawText(botText, x, height - 3 * d, p)
        p.clearShadowLayer()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val span = max(1f, height - 2 * padV)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && hitDot(e)) {
            dotDown = true
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (dotDown) {
            if (e.actionMasked == MotionEvent.ACTION_UP) { dotDown = false; if (hitDot(e)) onDot() }
            else if (e.actionMasked == MotionEvent.ACTION_CANCEL) dotDown = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onThumb = abs(e.y - ty()) <= thumbR * 1.2f
                rel = onThumb
                y0 = e.y
                f0 = f
                moved = false
                if (!rel) {
                    setFrom((e.y - padV) / span)
                    onChange(f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (rel) {
                    if (!moved && abs(e.y - y0) < 8 * d) return true
                    moved = true
                    setFrom(f0 + (e.y - y0) / span)
                } else {
                    setFrom((e.y - padV) / span)
                }
                onChange(f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (onThumb && !moved) {
                    onTap()
                    val n = System.currentTimeMillis()
                    if (n - lastTap < 400) onDouble()
                    lastTap = n
                }
            }
        }
        return true
    }
}

/** Barra orizzontale del bilanciamento del bianco, con calamita al centro. */
class HSlider(c: Context) : View(c) {
    var f = 0.5f
    var onChange: (Float) -> Unit = {}

    private val d = c.resources.displayMetrics.density
    private val padH get() = 14 * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = LinearGradient(
            padH, 0f, w - padH, 0f,
            intArrayOf(0xFF5B8DFF.toInt(), Color.WHITE, 0xFFFFC21A.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(cv: Canvas) {
        val cy = height / 2f
        p.style = Paint.Style.FILL
        p.shader = shader
        cv.drawRoundRect(padH, cy - 4 * d, width - padH, cy + 4 * d, 4 * d, 4 * d, p)
        p.shader = null
        val x = padH + f * (width - 2 * padH)
        p.color = Color.WHITE
        cv.drawRoundRect(x - 5 * d, cy - 16 * d, x + 5 * d, cy + 16 * d, 3 * d, 3 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = Color.BLACK
        cv.drawRoundRect(x - 5 * d, cy - 16 * d, x + 5 * d, cy + 16 * d, 3 * d, 3 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                var n = ((e.x - padH) / max(1f, width - 2 * padH)).coerceIn(0f, 1f)
                if (abs(n - 0.5f) < 0.05f) n = 0.5f
                f = n
                onChange(f)
                invalidate()
            }
        }
        return true
    }
}

/** TextureView che mantiene le proporzioni dell'anteprima. */
class AutoTex(c: Context) : TextureView(c) {
    private var rw = 0
    private var rh = 0
    fun setAspect(w: Int, h: Int) {
        rw = w
        rh = h
        requestLayout()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        if (rw == 0 || rh == 0) {
            setMeasuredDimension(w, h)
            return
        }
        if (w.toLong() * rh < h.toLong() * rw) setMeasuredDimension(w, w * rh / rw)
        else setMeasuredDimension(h * rw / rh, h)
    }
}

class Shutter(c: Context) : View(c) {
    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val r = width / 2f
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.11f
        cv.drawCircle(r, r, r - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(r, r, (r - r * 0.2f) * (if (down) 0.88f else 1f), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); performClick() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Triangolino bianco (nessuno stato attivo). */
class Tri(c: Context, private val left: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        pathEffect = CornerPathEffect(5f * c.resources.displayMetrics.density)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val path = Path()
        if (left) {
            path.moveTo(w * 0.78f, h * 0.18f); path.lineTo(w * 0.20f, h * 0.5f); path.lineTo(w * 0.78f, h * 0.82f)
        } else {
            path.moveTo(w * 0.22f, h * 0.18f); path.lineTo(w * 0.80f, h * 0.5f); path.lineTo(w * 0.22f, h * 0.82f)
        }
        path.close()
        cv.drawPath(path, p)
    }
}

/** Vista con le stesse proporzioni dell'anteprima. */
open class AspectView(c: Context) : View(c) {
    private var rw = 0
    private var rh = 0
    fun setAspect(w: Int, h: Int) {
        rw = w
        rh = h
        requestLayout()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        if (rw == 0 || rh == 0) {
            setMeasuredDimension(w, h)
            return
        }
        if (w.toLong() * rh < h.toLong() * rw) setMeasuredDimension(w, w * rh / rw)
        else setMeasuredDimension(h * rw / rh, h)
    }
}

/** Finestra mobile: mostra il riquadro che verrà ingrandito (si trascina); con lo zoom attivo sparisce. */
class Reticle(c: Context) : AspectView(c) {
    var win = 0f
    var px = 0.5f
    var py = 0.5f
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(cv: Canvas) {
        if (win <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = px * w
        val cy = py * h
        for (pass in 0..1) {
            p.strokeWidth = if (pass == 0) 3.5f * d else 1.5f * d
            p.color = if (pass == 0) 0x99000000.toInt() else 0xCCFFFFFF.toInt()
            cv.drawRect(cx - w * win / 2, cy - h * win / 2, cx + w * win / 2, cy + h * win / 2, p)
        }
    }
}

/** Contenitore dell'anteprima: se il tocco inizia sulla finestra mobile, la finestra vince sui selettori. */
class Stage(c: Context) : FrameLayout(c) {
    var grab: (MotionEvent) -> Boolean = { false }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && grab(e)) return true
        return super.onInterceptTouchEvent(e)
    }
}

class MainActivity : Activity() {

    // ---- UI ----
    private lateinit var prefs: SharedPreferences
    private lateinit var stage: FrameLayout
    private lateinit var tv: AutoTex
    private lateinit var reticle: Reticle
    private lateinit var sl: VSlider
    private lateinit var ex: VSlider
    private lateinit var wb: HSlider
    private lateinit var msgV: TextView
    private lateinit var flashV: View
    private lateinit var promptV: LinearLayout
    private lateinit var thumbV: ImageView
    private lateinit var tvTime: TextView
    private lateinit var tvMacro: TextView
    private lateinit var tvAuto: TextView
    private lateinit var tvLock: TextView
    private val isoBtns = ArrayList<TextView>()
    private val topChips = ArrayList<TextView>()
    private lateinit var hdrV: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var settingsBox: LinearLayout? = null
    private var dlg: AlertDialog? = null

    // ---- camera ----
    private var bgT: HandlerThread? = null
    private var bg: Handler? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var pSurface: Surface? = null
    @Volatile private var opening = false
    @Volatile private var creating = false
    private var camId = ""
    private var sensorOri = 90
    private var arr = Rect()
    private var jpeg = Size(0, 0)
    private var prev = Size(0, 0)
    private var minFocus = 0f
    private var manualFocus = false
    private var manualOk = false
    private var wbOk = false
    private var awbLockOk = false
    private var isoMin = 0
    private var isoMax = 0
    private var expMin = 0L
    private var expMax = 0L
    private var aeStep = 0f
    private var aeMin = 0f
    private var aeMax = 0f
    private var aeIdxMin = 0
    private var aeIdxMax = 0
    private var diag = ""

    // ---- stato ----
    private var macroOn = true
    private var focusD = 0f
    private var isoVal = 0
    private var t0 = 10_000_000.0
    private var exEv = 0f
    private var zoomed = false
    private var big = false           // pallino: zoom 10× invece di 5×
    @Volatile private var maxZoom = 1f
    private var minMul = 1f           // moltiplicatore della distanza minima (impostazioni)
    @Volatile private var gotFocusD = 0f
    /** Ingrandimento in uso: 5× o 10×, ma mai oltre il massimo che il telefono permette. */
    private val zoomF: Float get() = min(if (big) 10f else 5f, max(1f, maxZoom))
    /** Estremo vicino della scala, in diottrie (più alto = più vicino). */
    private val effMin: Float get() = if (minFocus > 1f) minFocus / minMul else minFocus
    private val P = PointF(0.5f, 0.5f)
    private var wbShift = 0f
    private var wbLockBtn = false

    @Volatile private var baseG: RggbChannelVector? = null
    @Volatile private var baseT: ColorSpaceTransform? = null
    @Volatile private var usedManual = false
    @Volatile private var lastExp = 10_000_000L
    @Volatile private var lastIso = 100
    @Volatile private var lensMoving = false
    @Volatile private var pend = false
    @Volatile private var busy = false
    private var devOri = 0
    private var lastJpegOri = 0
    private var lastUri: Uri? = null
    private var lblT = 0L

    // ---- voce ----
    private var vmode = 0   // 0 spento, 2 rumore vocale
    private val noiseOn: Boolean get() = vmode == 2
    private var nSens = 1
    @Volatile private var noiseRun = false
    private var noiseThread: Thread? = null
    private var rec: AudioRecord? = null
    @Volatile private var nLevel = 0f
    @Volatile private var nThr = 0f
    @Volatile private var nBase = 0f
    @Volatile private var nHits = 0

    @Volatile private var vShot = "nessuno"
    private var busySince = 0L
    private var voiceDiagTv: TextView? = null
    private val diagTick = object : Runnable {
        override fun run() {
            refreshVoiceDiag()
            if (dlg?.isShowing == true) ui.postDelayed(this, 300)
        }
    }

    private val oriL by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) devOri = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    private val zoomOffR = Runnable { zoomed = false; updateReticle(); update() }
    private val hideMsg = Runnable { msgV.visibility = View.GONE }
    private val hidePrompt = Runnable { promptV.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    // =====================================================================
    // Ciclo di vita
    // =====================================================================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("macro", MODE_PRIVATE)
        minMul = MULS[prefs.getInt("minf", 0).coerceIn(0, MULS.size - 1)]
        vmode = prefs.getInt("vmode", 0)
        nSens = prefs.getInt("nsens", 1)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        askPerms()
    }

    override fun onResume() {
        super.onResume()
        bgT = HandlerThread("cam").also { it.start() }
        bg = Handler(bgT!!.looper)
        oriL.enable()
        if (tv.isAvailable) openCamera()
        startMic()
    }

    override fun onPause() {
        stopMic()
        oriL.disable()
        ui.removeCallbacks(zoomOffR)
        closeCamera()
        bgT?.quitSafely()
        bgT = null
        bg = null
        super.onPause()
    }

    private fun askPerms() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 2) {
            if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startMic()
            else { setVmode(0); toast("Serve il permesso del microfono") }
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (tv.isAvailable) openCamera()
        } else toast("Serve il permesso della fotocamera")
    }

    // =====================================================================
    // Interfaccia
    // =====================================================================
    private fun chip(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // --- riga 1: impostazioni, ISO, MACRO, tempo ---
        val hdr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(8), 0)
        }
        hdrV = hdr
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 24f; setTextColor(0xFFB4B4B8.toInt()); gravity = Gravity.CENTER
            setOnClickListener { showSettings() }
        }
        hdr.addView(gear, LinearLayout.LayoutParams(dp(34), dp(40)))
        val isoLbl = chip("ISO:").apply { setPadding(dp(2), dp(8), dp(4), dp(8)) }
        topChips.add(isoLbl)
        hdr.addView(isoLbl)
        for (i in intArrayOf(50, 100, 200, 400, 0)) {
            val t = chip(if (i == 0) "AUTO" else "$i").apply { tag = i; setOnClickListener { setIso(i) } }
            isoBtns.add(t)
            topChips.add(t)
            hdr.addView(t)
        }
        tvMacro = chip("MACRO").apply { setOnClickListener { setMacro(!macroOn) } }
        topChips.add(tvMacro)
        hdr.addView(tvMacro, LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(8) })
        tvTime = chip("1:—").apply { gravity = Gravity.END }
        topChips.add(tvTime)
        hdr.addView(tvTime, LinearLayout.LayoutParams(0, WC, 1f))
        root.addView(hdr, LinearLayout.LayoutParams(MP, WC))

        // --- riga 2: bilanciamento del bianco ---
        val wrow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(8), dp(2))
        }
        wb = HSlider(this)
        wb.onChange = { f -> onWb(f) }
        wrow.addView(wb, LinearLayout.LayoutParams(0, dp(40), 1f))
        tvAuto = chip("AUTO").apply { setOnClickListener { wbAuto() } }
        tvLock = chip("BLOCCATO").apply { setOnClickListener { wbLockBtn = true; updateWbUi(); update() } }
        topChips.add(tvAuto)
        topChips.add(tvLock)
        wrow.addView(tvAuto, LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(8) })
        wrow.addView(tvLock)
        root.addView(wrow, LinearLayout.LayoutParams(MP, WC))

        // --- area anteprima ---
        stage = Stage(this).apply { clipChildren = false; setBackgroundColor(Color.BLACK); grab = { e -> windowHit(e) } }
        tv = AutoTex(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (cam != null) createSession() else openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                if (cam != null && session == null) createSession()
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        stage.addView(tv, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))

        // senza zoom: si tocca o si trascina per spostare la finestra; con lo zoom attivo: un tocco lo toglie
        var downZoomed = false
        var dx0 = 0f
        var dy0 = 0f
        stage.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downZoomed = zoomed
                    dx0 = e.x
                    dy0 = e.y
                    if (!zoomed) { grabOffset(e); movePoint(e) }
                }
                MotionEvent.ACTION_MOVE -> if (!zoomed && !downZoomed) movePoint(e)
                MotionEvent.ACTION_UP -> {
                    if (downZoomed && zoomed && abs(e.x - dx0) < dpf(24) && abs(e.y - dy0) < dpf(24)) zoomOffR.run()
                    downZoomed = false
                }
            }
            true
        }

        sl = VSlider(this, dpf(30), true)
        ex = VSlider(this, dpf(25), false)
        ex.f = 0.5f
        ex.snap = 0.045f
        sl.onChange = { f -> onFocus(f) }
        sl.onTap = { shoot() }
        sl.onDot = { toggleBig() }
        ex.onChange = { f -> onExp(f) }
        ex.onDouble = { ex.f = 0.5f; onExp(0.5f); ex.invalidate() }
        stage.addView(sl)
        stage.addView(ex)
        reticle = Reticle(this)
        stage.addView(reticle, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))

        msgV = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dpf(16); setColor(0xE61C1C1E.toInt()) }
        }
        stage.addView(msgV, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        flashV = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        stage.addView(flashV, FrameLayout.LayoutParams(MP, MP))

        promptV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dpf(22); setColor(0xF01C1C1E.toInt()) }
        }
        promptV.addView(TextView(this).apply { text = "Foto salvata"; setTextColor(Color.WHITE); textSize = 14f })
        promptV.addView(TextView(this).apply {
            text = "Apri in Google Foto"; setTextColor(GREEN); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setOnClickListener { lastUri?.let { u -> openPhoto(u) }; promptV.visibility = View.GONE }
        })
        promptV.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setOnClickListener { promptV.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        stage.addView(promptV, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })

        root.addView(stage, LinearLayout.LayoutParams(MP, 0, 1f))

        // --- barra bassa: miniatura, triangoli, scatto ---
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
        }
        val pile = FrameLayout(this)
        val back = View(this).apply {
            rotation = -9f
            background = GradientDrawable().apply { cornerRadius = dpf(8); setColor(0xFF0B0B0B.toInt()); setStroke(dp(2), Color.WHITE) }
        }
        pile.addView(back, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.START))
        thumbV = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1C1C1E.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(6)) }
            }
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(8); setColor(Color.WHITE) }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { lastUri?.let { u -> openPhoto(u) } }
        }
        frame.addView(thumbV, FrameLayout.LayoutParams(MP, MP))
        pile.addView(frame, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.TOP or Gravity.END))
        val leftBox = FrameLayout(this)
        leftBox.addView(pile, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER_VERTICAL or Gravity.START))
        bar.addView(leftBox, LinearLayout.LayoutParams(0, dp(80), 1f))

        val triL = Tri(this, true).apply { setOnClickListener { setSide("l") } }
        val triR = Tri(this, false).apply { setOnClickListener { setSide("r") } }
        val shutter = Shutter(this).apply { setOnClickListener { shoot() } }
        val center = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        center.addView(triL, LinearLayout.LayoutParams(dp(40), dp(48)))
        center.addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(18); marginEnd = dp(18) })
        center.addView(triR, LinearLayout.LayoutParams(dp(40), dp(48)))
        bar.addView(center, LinearLayout.LayoutParams(WC, dp(80)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(80), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MP, WC))

        setContentView(root)
        root.post { fitHeader() }
        applySide()
        styleIso()
        updateWbUi()
        setMacro(true)
        onExp(0.5f)
        onFocus(0f, false)
        zoomOffR.run()
    }

    /** Calcola un'unica dimensione del testo per le due righe in alto, così stanno sempre nello schermo. */
    private fun fitHeader() {
        val avail = hdrV.width - hdrV.paddingLeft - hdrV.paddingRight - dp(34)
        if (avail <= 0) return
        val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 100f }
        var total = 0f
        for (w in arrayOf("ISO:", "50", "100", "200", "400", "AUTO", "MACRO", "1:10000")) total += m.measureText(w) / 100f
        val pads = dp(8) * 8 + dp(8)
        val px = ((avail - pads) / total).coerceIn(dpf(9), dpf(13))
        for (t in topChips) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }

    private fun setSide(s: String) {
        prefs.edit().putString("side", s).apply()
        applySide()
    }

    private fun applySide() {
        val left = prefs.getString("side", "l") == "l"
        fun lp(w: Int, m: Int) = FrameLayout.LayoutParams(dp(w), MP, if (left) Gravity.START else Gravity.END).apply {
            topMargin = dp(8); bottomMargin = dp(8)
            if (left) leftMargin = dp(m) else rightMargin = dp(m)
        }
        sl.layoutParams = lp(64, 70)
        ex.layoutParams = lp(52, 8)
        sl.labelRight = left
        sl.invalidate()
    }

    private fun styleIso() {
        for (t in isoBtns) {
            val i = t.tag as Int
            t.setTextColor(if (i == isoVal) GREEN else Color.WHITE)
            t.alpha = if (i == 0 || isoOk(i)) 1f else 0.3f
        }
    }

    private fun isoOk(i: Int) = manualOk && i in isoMin..isoMax

    private fun setMacro(on: Boolean) {
        macroOn = on
        tvMacro.setTextColor(if (on) GREEN else Color.WHITE)
        sl.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { ui.removeCallbacks(zoomOffR); zoomed = false }
        updateReticle()
        update()
    }

    private fun updateReticle() {
        if (!::reticle.isInitialized) return
        reticle.visibility = if (macroOn) View.VISIBLE else View.INVISIBLE
        reticle.win = if (zoomed) 0f else 1f / zoomF
        reticle.px = P.x
        reticle.py = P.y
        reticle.invalidate()
    }

    /** Tiene la finestra dentro l'immagine. */
    private fun clampP() {
        val h = 0.5f / zoomF
        P.x = P.x.coerceIn(h, 1f - h)
        P.y = P.y.coerceIn(h, 1f - h)
    }

    private var offX = 0f
    private var offY = 0f

    /** true se il tocco cade sulla finestra mobile (con un po' di margine). */
    private fun windowHit(e: MotionEvent): Boolean {
        if (zoomed || !macroOn || !::tv.isInitialized || tv.width <= 0 || tv.height <= 0) return false
        val cx = tv.left + P.x * tv.width
        val cy = tv.top + P.y * tv.height
        val hw = tv.width / zoomF / 2f + dpf(14)
        val hh = tv.height / zoomF / 2f + dpf(14)
        return abs(e.x - cx) <= hw && abs(e.y - cy) <= hh
    }

    /** Se si afferra la finestra, la si sposta senza farla saltare; altrimenti salta dove si tocca. */
    private fun grabOffset(e: MotionEvent) {
        if (tv.width <= 0 || tv.height <= 0) return
        if (windowHit(e)) {
            offX = P.x - (e.x - tv.left) / tv.width
            offY = P.y - (e.y - tv.top) / tv.height
        } else {
            offX = 0f
            offY = 0f
        }
    }

    private fun movePoint(e: MotionEvent) {
        if (!macroOn || tv.width <= 0 || tv.height <= 0) return
        P.x = (e.x - tv.left) / tv.width + offX
        P.y = (e.y - tv.top) / tv.height + offY
        clampP()
        updateReticle()
    }

    private fun toast(t: String) {
        ui.post {
            msgV.text = t
            msgV.visibility = View.VISIBLE
            ui.removeCallbacks(hideMsg)
            ui.postDelayed(hideMsg, 3500)
        }
    }

    private fun showTime(ns: Long) {
        if (ns <= 0) return
        tvTime.text = "1:" + (1e9 / ns).roundToInt()
        tvTime.setTextColor(if (ns > 33_333_333L) 0xFFFFB300.toInt() else Color.WHITE)
    }

    // =====================================================================
    // Controlli: fuoco, esposizione, ISO, bianco
    // =====================================================================
    private fun fmtCm(cm: Float, dec: Boolean): String =
        if (dec || (cm < 10f && abs(cm - cm.roundToInt()) >= 0.05f)) String.format(Locale.US, "%.1f", cm)
        else cm.roundToInt().toString()

    private fun onFocus(f: Float, user: Boolean = true) {
        focusD = when {
            !manualFocus -> 0f
            else -> 1f + f * (effMin - 1f)
        }
        sl.label = if (!manualFocus) "AF" else fmtCm(100f / focusD, false) + " cm"
        sl.invalidate()
        if (macroOn && user) zoomOn()
        update()
    }

    /** Scrive ai due estremi dello slider i valori in cm della scala in uso. */
    private fun updateEnds() {
        if (!manualFocus) {
            sl.topText = ""
            sl.botText = ""
        } else {
            sl.topText = "100 cm"
            sl.botText = fmtCm(100f / effMin, false) + " cm"
        }
        sl.invalidate()
    }

    /** Pallino: passa l'ingrandimento da 5× a 10× (o al massimo del telefono) e viceversa. */
    private fun toggleBig() {
        big = !big
        sl.dotOn = big
        clampP()
        if (big) zoomOn() else updateReticle()
        update()
    }

    /** Cambia il moltiplicatore della distanza minima mantenendo il fuoco dov'è. */
    private fun setMinMul(i: Int) {
        val d = if (focusD > 0f) focusD else 1f
        minMul = MULS[i]
        prefs.edit().putInt("minf", i).apply()
        sl.f = ((d - 1f) / (effMin - 1f)).coerceIn(0f, 1f)
        updateEnds()
        onFocus(sl.f, false)
        clampP()
        updateReticle()
        update()
        if (dlg?.isShowing == true) settingsBox?.let { fillSettings(it) }
    }

    private fun zoomLine(): String {
        val z = String.format(Locale.US, "%.1f", maxZoom)
        return if (maxZoom >= 10f) "Zoom: 5× e 10× (massimo del telefono $z×)"
        else "Zoom: 5× e $z× (limite del telefono: non arriva a 10×)"
    }

    private fun focusLine(): String {
        if (!manualFocus) return "Fuoco: autofocus"
        val req = if (focusD > 0f) 100f / focusD else 0f
        val got = if (gotFocusD > 0f) 100f / gotFocusD else 0f
        val lim = focusD > minFocus * 1.02f && gotFocusD < focusD * 0.97f
        return "Fuoco: richiesto " + String.format(Locale.US, "%.1f", req) + " cm · applicato " +
            String.format(Locale.US, "%.1f", got) + " cm" + (if (lim) "  ← limitato dal telefono" else "")
    }

    private fun zoomOn() {
        zoomed = true
        ui.removeCallbacks(zoomOffR)
        updateReticle()
    }

    private fun onExp(f: Float) {
        val lo = if (isoVal == 0) aeMin else -2f
        val hi = if (isoVal == 0) aeMax else 2f
        var e = if (f <= 0.5f) hi * (1 - f * 2) else lo * ((f - 0.5f) * 2)
        if (isoVal == 0 && aeStep > 0f) e = (e / aeStep).roundToInt() * aeStep
        exEv = e
        ex.label = if (abs(e) < 0.04f) "0" else String.format(Locale.US, "%+.1f", e)
        ex.invalidate()
        if (isoVal != 0) showTime(curTimeNs())
        update()
    }

    private fun curTimeNs(): Long {
        val t = (t0 * 2.0.pow(exEv.toDouble())).toLong()
        return if (expMax > expMin) t.coerceIn(expMin, expMax) else t
    }

    private fun setIso(i: Int) {
        if (i != 0 && !isoOk(i)) return
        if (i != 0) {
            val t = lastExp.toDouble() * lastIso / i
            t0 = if (expMax > expMin) t.coerceIn(expMin.toDouble(), expMax.toDouble()) else t
        }
        isoVal = i
        ex.f = 0.5f
        if (i == 0) tvTime.text = "1:—"
        onExp(0.5f)
        styleIso()
    }

    private fun onWb(f: Float) {
        if (!wbOk) {
            wb.f = 0.5f
            wb.invalidate()
            toast("Questo telefono non permette di regolare il bianco")
            return
        }
        wbShift = (f - 0.5f) * 2f
        updateWbUi()
        update()
    }

    private fun wbAuto() {
        wbLockBtn = false
        wbShift = 0f
        wb.f = 0.5f
        wb.invalidate()
        updateWbUi()
        update()
    }

    private fun updateWbUi() {
        val locked = wbLockBtn || wbShift != 0f
        tvLock.setTextColor(if (locked) GREEN else Color.WHITE)
        tvAuto.setTextColor(if (locked) Color.WHITE else GREEN)
    }

    // =====================================================================
    // Camera2
    // =====================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cam != null || opening || bg == null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val backs = mgr.cameraIdList.filter {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
            if (backs.isEmpty()) { toast("Nessuna fotocamera posteriore"); return }
            camId = if ("0" in backs) "0" else backs[0]
            readCaps(mgr.getCameraCharacteristics(camId))
            opening = true
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { cam = d; opening = false; createSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); cam = null; session = null; opening = false }
                override fun onError(d: CameraDevice, err: Int) {
                    d.close(); cam = null; session = null; opening = false
                    toast("Errore fotocamera ($err)")
                }
            }, bg)
        } catch (e: Exception) {
            opening = false
            toast("Camera: ${e.message}")
        }
    }

    private fun readCaps(ch: CameraCharacteristics) {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        jpeg = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
        val ar = jpeg.width.toDouble() / jpeg.height
        val ps = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
        prev = ps.filter { abs(it.width.toDouble() / it.height - ar) < 0.02 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: ps.maxByOrNull { it.width.toLong() * it.height }!!
        arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        manualFocus = minFocus > 1f
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        manualOk = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        wbOk = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
        awbLockOk = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expMin = it.lower; expMax = it.upper }
        aeStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            aeIdxMin = it.lower; aeIdxMax = it.upper
            aeMin = it.lower * aeStep; aeMax = it.upper * aeStep
        }
        val lvl = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        diag = "Fotocamera ID $camId ($lvl) · ${jpeg.width}×${jpeg.height}\n" +
            "Fuoco manuale: " + (if (manualFocus) "sì, minimo dichiarato " + String.format(Locale.US, "%.1f", 100f / minFocus) + " cm" else "NO (resta autofocus)") + "\n" +
            "ISO manuale: " + (if (manualOk) "sì ($isoMin–$isoMax)" else "NO") + "  ·  bianco manuale: " + (if (wbOk) "sì" else "NO")

        runOnUiThread {
            tv.setAspect(prev.height, prev.width)
            reticle.setAspect(prev.height, prev.width)
            wb.alpha = if (wbOk) 1f else 0.35f
            styleIso()
            sl.showDot = manualFocus
            updateEnds()
            onFocus(sl.f, false)
            onExp(ex.f)
            if (!manualFocus) toast("Questo telefono non permette il fuoco manuale")
        }
    }

    private fun createSession() {
        val c = cam ?: return
        if (session != null || creating) return
        val st = tv.surfaceTexture ?: return
        creating = true
        try {
            st.setDefaultBufferSize(prev.width, prev.height)
            val ps = Surface(st)
            pSurface = ps
            reader?.close()
            val r = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, bg)
            reader = r
            @Suppress("DEPRECATION")
            c.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; creating = false; update() }
                override fun onConfigureFailed(s: CameraCaptureSession) { creating = false; toast("Sessione fotocamera non riuscita") }
            }, bg)
        } catch (e: Exception) {
            creating = false
            toast("Sessione: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { cam?.close() } catch (e: Exception) {}
        cam = null
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        pSurface = null
        opening = false
        creating = false
    }

    // coordinate dello schermo (0..1) -> coordinate normalizzate del sensore
    private fun toSensor(dx: Float, dy: Float): Pair<Float, Float> = when (sensorOri) {
        90 -> Pair(dy, 1f - dx)
        180 -> Pair(1f - dx, 1f - dy)
        270 -> Pair(1f - dy, dx)
        else -> Pair(dx, dy)
    }

    private fun cropFor(z: Float): Rect {
        val w = arr.width() / z
        val h = arr.height() / z
        val (sx, sy) = toSensor(P.x, P.y)
        val l = (arr.left + sx * arr.width() - w / 2).coerceIn(arr.left.toFloat(), arr.right - w)
        val t = (arr.top + sy * arr.height() - h / 2).coerceIn(arr.top.toFloat(), arr.bottom - h)
        return Rect(l.toInt(), t.toInt(), (l + w).toInt(), (t + h).toInt())
    }

    private fun fill(b: CaptureRequest.Builder, zoom: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (macroOn && manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusD)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (isoVal == 0) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val idx = if (aeStep > 0f) (exEv / aeStep).roundToInt().coerceIn(aeIdxMin, aeIdxMax) else 0
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, curTimeNs())
        }
        // bilanciamento del bianco
        val g = baseG
        val t = baseT
        if (wbOk && wbShift != 0f && g != null && t != null) {
            usedManual = true
            val w = 1.6.pow(wbShift.toDouble()).toFloat()
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red * w, g.greenEven, g.greenOdd, g.blue / w))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, t)
        } else {
            usedManual = false
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (wbLockBtn && awbLockOk) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        if (zoom && macroOn) b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoomF))
    }

    private val prevCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            lensMoving = res.get(CaptureResult.LENS_STATE) == CameraMetadata.LENS_STATE_MOVING
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { gotFocusD = it }
            if (!usedManual) {
                res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { baseG = it }
                res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { baseT = it }
            }
            val n = System.currentTimeMillis()
            if (isoVal == 0 && n - lblT > 400) {
                lblT = n
                val e = lastExp
                ui.post { if (isoVal == 0) showTime(e) }
            }
        }
    }

    private fun update() {
        val h = bg ?: return
        if (pend) return
        pend = true
        h.post {
            pend = false
            try {
                val s = session ?: return@post
                val c = cam ?: return@post
                val ps = pSurface ?: return@post
                val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                fill(b, zoomed)
                s.setRepeatingRequest(b.build(), prevCb, bg)
            } catch (e: Exception) {
            }
        }
    }

    // =====================================================================
    // Scatto e salvataggio
    // =====================================================================
    private fun shoot() {
        if (busy && System.currentTimeMillis() - busySince > 6000) busy = false
        if (busy || session == null || cam == null) {
            vShot = "ignorato (già in corso: $busy, sessione: ${session != null}, camera: ${cam != null})"
            return
        }
        busy = true
        busySince = System.currentTimeMillis()
        vShot = "avviato"
        flashV.alpha = 0.7f
        flashV.animate().alpha(0f).setDuration(250).start()
        bg?.post { waitLens(0) }
    }

    private fun waitLens(n: Int) {
        if (lensMoving && n < 10) bg?.postDelayed({ waitLens(n + 1) }, 80) else fire()
    }

    private fun fire() {
        try {
            val c = cam ?: throw IllegalStateException("camera chiusa")
            val s = session ?: throw IllegalStateException("sessione chiusa")
            val rd = reader ?: throw IllegalStateException("reader chiuso")
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(rd.surface)
            fill(b, false)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            lastJpegOri = (sensorOri + devOri) % 360
            b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegOri)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (vShot == "avviato") vShot = "scatto eseguito, salvo…"
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    busy = false
                    vShot = "scatto fallito"
                    toast("Scatto non riuscito")
                }
            }, bg)
        } catch (e: Exception) {
            busy = false
            vShot = "errore: ${e.message}"
            toast("Scatto: ${e.message}")
        }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireNextImage()
        if (img == null) { vShot = "immagine nulla"; busy = false; return }
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        img.close()
        val ori = lastJpegOri
        val uri = try { saveJpeg(bytes) } catch (e: Exception) { null }
        val th = makeThumb(bytes, ori)
        vShot = if (uri == null) "salvataggio fallito" else "foto salvata"
        runOnUiThread {
            busy = false
            if (uri == null) toast("Salvataggio non riuscito") else {
                lastUri = uri
                if (th != null) thumbV.setImageBitmap(th)
                promptV.visibility = View.VISIBLE
                ui.removeCallbacks(hidePrompt)
                ui.postDelayed(hidePrompt, 7000)
            }
        }
    }

    private fun makeThumb(b: ByteArray, deg: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bm = BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
    } catch (e: Exception) {
        null
    }

    private fun saveJpeg(bytes: ByteArray): Uri? {
        val name = "MACRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"

        val tree = prefs.getString("tree", null)
        if (tree != null) {
            try {
                val t = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                val doc = DocumentsContract.createDocument(contentResolver, parent, "image/jpeg", name)
                if (doc != null) {
                    contentResolver.openOutputStream(doc)!!.use { it.write(bytes) }
                    return doc
                }
            } catch (e: Exception) {
                toast("Cartella non accessibile: salvo in Pictures/Macro")
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Macro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Macro")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, f.absolutePath)
            }
            return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        }
    }

    private fun openPhoto(u: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(i).setPackage("com.google.android.apps.photos"))
        } catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) { toast("Nessuna app per aprire la foto") }
        }
    }

    // =====================================================================
    // Scatto vocale: di' «clic»
    // =====================================================================
    /** Pannello in tempo reale nelle impostazioni: fuoco richiesto/applicato e, se attivo, il livello del rumore. */
    private fun refreshVoiceDiag() {
        val t = voiceDiagTv ?: return
        val sb = StringBuilder(focusLine()).append("\n").append(zoomLine())
        if (vmode == 2) {
            val lvl = ((nLevel / max(1f, nThr)) * 10f).roundToInt().coerceIn(0, 10)
            sb.append("\nRumore: ").append("█".repeat(lvl)).append("░".repeat(10 - lvl)).append("  (pieno = scatta)")
            sb.append("\nSoglia ").append(nThr.toInt()).append(" · fondo ").append(nBase.toInt()).append(" · scatti ").append(nHits)
        }
        t.text = sb.toString()
    }

    private fun setVmode(m: Int) {
        stopMic()
        vmode = m
        prefs.edit().putInt("vmode", m).apply()
        startMic()
        if (dlg?.isShowing == true) settingsBox?.let { fillSettings(it) }
    }

    private fun startMic() {
        if (vmode == 2) startNoise()
    }

    private fun stopMic() {
        stopNoise()
    }

    /** Scatto a rumore: parte appena un suono netto supera il rumore di fondo (molto più rapido della parola). */
    @SuppressLint("MissingPermission")
    private fun startNoise() {
        if (!noiseOn || noiseRun) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096) * 2)
        } catch (e: Exception) { null }
        if (minBuf <= 0 || r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            try { r?.release() } catch (e: Exception) {}
            toast("Microfono non disponibile")
            setVmode(0)
            return
        }
        rec = r
        noiseRun = true
        r.startRecording()
        noiseThread = Thread {
            val buf = ShortArray(rate / 50)   // finestre da 20 ms
            var base = 0f
            var warm = 0
            var cool = 0L
            while (noiseRun) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                nLevel = rms
                if (warm < 25) {
                    base = if (warm == 0) rms else base * 0.9f + rms * 0.1f
                    warm++
                    nBase = base
                    continue
                }
                val k = when (nSens) { 0 -> 8f; 2 -> 3.5f; else -> 5f }
                val floor = when (nSens) { 0 -> 3000f; 2 -> 1000f; else -> 1800f }
                val thr = max(base * k, floor)
                nThr = thr
                val now = System.currentTimeMillis()
                if (rms > thr && now > cool) {
                    cool = now + 2500
                    nHits++
                    ui.post { shoot() }
                } else if (rms <= thr) {
                    base = base * 0.98f + rms * 0.02f
                }
                nBase = base
            }
        }.also { it.start() }
        toast("Scatto a rumore attivo: pronuncia un suono netto")
    }

    private fun stopNoise() {
        noiseRun = false
        try { rec?.stop() } catch (e: Exception) {}
        try { noiseThread?.join(300) } catch (e: Exception) {}
        noiseThread = null
        try { rec?.release() } catch (e: Exception) {}
        rec = null
    }

    // =====================================================================
    // Impostazioni
    // =====================================================================
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        settingsBox = box
        fillSettings(box)
        dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Chiudi", null)
            .create()
        dlg!!.setOnDismissListener { ui.removeCallbacks(diagTick) }
        dlg!!.show()
        ui.removeCallbacks(diagTick)
        ui.post(diagTick)
    }

    private fun fillSettings(box: LinearLayout) {
        box.removeAllViews()
        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFF9A9AA0.toInt()); textSize = 12f; setPadding(0, dp(14), 0, dp(4))
        }
        fun btn(t: String, on: Boolean, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false
            setTextColor(if (on) Color.BLACK else Color.WHITE)
            setBackgroundColor(if (on) GREEN else 0xFF3A3A3E.toInt())
            setOnClickListener { f() }
        }
        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (x in v) addView(x, LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = dp(6) })
        }

        box.addView(TextView(this).apply { text = "Impostazioni"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })

        box.addView(label("SALVA LE FOTO IN"))
        val tree = prefs.getString("tree", null)
        val folder = if (tree == null) "Pictures/Macro (predefinita)" else
            try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':').ifEmpty { "(radice)" } } catch (e: Exception) { "cartella scelta" }
        box.addView(TextView(this).apply { text = folder; setTextColor(Color.WHITE); textSize = 15f })
        box.addView(row(
            btn("Scegli cartella…", false) { pickFolder() },
            btn("Predefinita", tree == null) { prefs.edit().remove("tree").apply(); fillSettings(box) }
        ))

        box.addView(label("DISTANZA MINIMA DI FUOCO (× QUELLA DICHIARATA DAL TELEFONO)"))
        val base = if (minFocus > 1f) 100f / minFocus else 0f
        val names = arrayOf("1.0×", "0.7×", "0.55×")
        fun mb(i: Int) = btn(names[i] + (if (base > 0f) "\n" + fmtCm(base * MULS[i], false) + " cm" else ""), minMul == MULS[i]) { setMinMul(i) }
        box.addView(row(mb(0), mb(1), mb(2)))
        box.addView(TextView(this).apply {
            text = "Alcuni telefoni (es. Galaxy S9) vanno oltre il valore dichiarato, altri lo limitano: controlla la riga «Fuoco»."
            setTextColor(0xFF9A9AA0.toInt()); textSize = 11f; setPadding(0, dp(4), 0, 0)
        })

        box.addView(label("SCATTO A RUMORE VOCALE"))
        box.addView(row(
            btn("Spento", vmode == 0) { setVmode(0) },
            btn("Rumore vocale", vmode == 2) { setVmode(2) }
        ))
        if (vmode == 2) {
            box.addView(label("SENSIBILITÀ (PIÙ ALTA = SCATTA CON SUONI PIÙ DEBOLI)"))
            box.addView(row(
                btn("Bassa", nSens == 0) { nSens = 0; prefs.edit().putInt("nsens", 0).apply(); fillSettings(box) },
                btn("Media", nSens == 1) { nSens = 1; prefs.edit().putInt("nsens", 1).apply(); fillSettings(box) },
                btn("Alta", nSens == 2) { nSens = 2; prefs.edit().putInt("nsens", 2).apply(); fillSettings(box) }
            ))
        }

        box.addView(label("FORMATO"))
        box.addView(TextView(this).apply {
            text = "Massima risoluzione del sensore, JPEG a qualità 100"
            setTextColor(Color.WHITE); textSize = 14f
        })

        box.addView(label("DIAGNOSTICA"))
        box.addView(TextView(this).apply {
            text = if (diag.isEmpty()) "Fotocamera non ancora aperta" else diag
            setTextColor(0xFFCCCCCC.toInt()); textSize = 12f
        })
        voiceDiagTv = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); textSize = 12f; setPadding(0, dp(8), 0, 0)
        }
        box.addView(voiceDiagTv)
        refreshVoiceDiag()

        box.addView(TextView(this).apply {
            text = "Macrocamera by Pino Settino"
            setTextColor(0xFF8A8A90.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }, LinearLayout.LayoutParams(MP, WC))
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, 7)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 7 || res != RESULT_OK) return
        val u = d?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        prefs.edit().putString("tree", u.toString()).apply()
        if (dlg?.isShowing == true) settingsBox?.let { fillSettings(it) }
    }
}
