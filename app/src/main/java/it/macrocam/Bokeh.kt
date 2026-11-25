package it.macrocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bokeh artificiale da tre foto (nitida, intermedia, vicina): porting di sfocato_macro.py.
 *
 *  1. allinea le tre foto (prima in generale, poi solo sullo sfondo);
 *  2. dal confronto di nitidezza stima la maschera del piano a fuoco;
 *  3. soggetto dalla foto nitida, sfondo dallo sfocato vero delle altre due;
 *  4. attorno al soggetto, dove la foto «vicina» è sporcata dall'alone, sfocato sintetico a disco calibrato;
 *  5. ritaglio alla zona comune alle tre foto.
 *
 * Va chiamato fuori dal thread dell'interfaccia, dopo OpenCVLoader.initLocal().
 */
object BokehProcessor {
    // parametri (gli stessi dello script Python)
    private const val FASCIA_ALONE = 0.035   // larghezza della zona di raccordo attorno al soggetto (frazione della larghezza)
    private const val SOGLIA_FUOCO = 0.85    // >1 maschera più severa, <1 più permissiva
    private const val MORBIDEZZA = 0.6       // transizione della maschera
    private const val RAGGIO_GUIDA = 12      // quanto la maschera segue i bordi reali (px)
    private const val EXTRA = 0.0            // sfocatura in più sullo sfondo (frazione della larghezza); 0 = solo quella vera
    private val LATI = intArrayOf(2400, 1600, 1200)   // lato lungo di lavoro; se manca memoria si riprova più piccolo

    private val F32 = CvType.CV_32F
    private val ZERO = Size(0.0, 0.0)
    private val ANCHOR = Point(-1.0, -1.0)

    private class Aligned(val img: Mat, val valid: Mat)

    /** Restituisce il JPEG del risultato, già raddrizzato (ori = gradi orari dell'orientamento di scatto). */
    fun process(jpegs: List<ByteArray>, ori: Int): ByteArray {
        require(jpegs.size == 3) { "servono 3 foto" }
        Core.setNumThreads(2)       // lascia spazio all'anteprima e allo scatto
        var last: Throwable? = null
        for (side in LATI) {
            try {
                return run(jpegs, ori, side)
            } catch (e: Throwable) {   // anche OutOfMemoryError: si riprova a risoluzione minore
                last = e
                System.gc()
            }
        }
        throw RuntimeException(last?.message ?: "elaborazione non riuscita", last)
    }

    private fun run(jpegs: List<ByteArray>, ori: Int, side: Int): ByteArray {
        val a = decode(jpegs[0], side)
        val c = fit(decode(jpegs[1], side), a)
        val b = fit(decode(jpegs[2], side), a)

        // allineamento generale
        val c1 = align(a, c, null, 4.0, null)
        val b1 = align(a, b, null, 4.0, null)
        var u = estimateFocus(a, c1.img, b1.img)

        // secondo allineamento, solo sullo sfondo (il cambio di fuoco ingrandisce leggermente l'immagine)
        val bg = Mat()
        Imgproc.threshold(u, bg, 0.2, 1.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.erode(bg, bg, Mat.ones(15, 15, CvType.CV_8U))
        val c2 = align(a, c1.img, bg, 6.0, c1.valid)
        val b2 = align(a, b1.img, bg, 6.0, b1.valid)
        val valid = mul(c2.valid, b2.valid)
        u.release()
        bg.release()
        u = estimateFocus(a, c2.img, b2.img)

        val out = compose(a, c2.img, b2.img, u, valid)
        return encode(out, valid, ori)
    }

    // ------------------------------------------------------------------
    // Lettura
    // ------------------------------------------------------------------
    /** JPEG -> RGB float 0..1, lato lungo al massimo 'side'. L'EXIF non viene applicato (le tre foto hanno lo stesso). */
    private fun decode(bytes: ByteArray, side: Int): Mat {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var ss = 1
        while (max(o.outWidth, o.outHeight) / (ss * 2) >= side) ss *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = ss
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            ?: throw IllegalStateException("immagine illeggibile")
        val rgba = Mat()
        Utils.bitmapToMat(bm, rgba)
        bm.recycle()
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val m = max(rgb.cols(), rgb.rows())
        val sized = Mat()
        if (m > side) {
            val s = side.toDouble() / m
            Imgproc.resize(rgb, sized, ZERO, s, s, Imgproc.INTER_AREA)
            rgb.release()
        } else rgb.copyTo(sized)
        val f = Mat()
        sized.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
        sized.release()
        return f
    }

    private fun fit(m: Mat, ref: Mat): Mat {
        if (m.cols() == ref.cols() && m.rows() == ref.rows()) return m
        val o = Mat()
        Imgproc.resize(m, o, Size(ref.cols().toDouble(), ref.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        m.release()
        return o
    }

    // ------------------------------------------------------------------
    // Utilità
    // ------------------------------------------------------------------
    private fun gray(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        return g
    }

    private fun mul(x: Mat, y: Mat): Mat {
        val o = Mat()
        Core.multiply(x, y, o)
        return o
    }

    private fun clip01(m: Mat) {
        Core.max(m, Scalar(0.0), m)
        Core.min(m, Scalar(1.0), m)
    }

    /** 3x² - 2x³ sul valore già compreso in 0..1 (dopo il clip). */
    private fun smooth(x: Mat) {
        clip01(x)
        val t = Mat()
        x.convertTo(t, F32, -2.0, 3.0)
        val x2 = Mat()
        Core.multiply(x, x, x2)
        Core.multiply(x2, t, x)
        t.release()
        x2.release()
    }

    /** a*(1-t) + b*t, con t a un solo canale. */
    private fun lerp(a: Mat, b: Mat, t1: Mat): Mat {
        val t3 = Mat()
        Core.merge(listOf(t1, t1, t1), t3)
        val d = Mat()
        Core.subtract(b, a, d)
        Core.multiply(d, t3, d)
        val o = Mat()
        Core.add(a, d, o)
        t3.release()
        d.release()
        return o
    }

    /** Percentili (0..100) su un campione di circa 200 mila pixel; la Mat deve essere a un canale float e continua. */
    private fun pct(m: Mat, vararg p: Double): DoubleArray {
        val n = m.total().toInt()
        val all = FloatArray(n)
        m.get(0, 0, all)
        val step = max(1, n / 200_000)
        val cnt = (n + step - 1) / step
        val s = FloatArray(cnt) { all[it * step] }
        s.sort()
        return DoubleArray(p.size) { s[((p[it] / 100.0) * (cnt - 1)).roundToInt().coerceIn(0, cnt - 1)].toDouble() }
    }

    private fun box(src: Mat, r: Int): Mat {
        val d = Mat()
        val k = (2 * r + 1).toDouble()
        Imgproc.boxFilter(src, d, -1, Size(k, k), ANCHOR, true, Core.BORDER_REFLECT)
        return d
    }

    /** Guided filter (He et al.), guida a un canale. */
    private fun guided(guide: Mat, p: Mat, r: Int, eps: Double): Mat {
        val mI = box(guide, r)
        val mp = box(p, r)
        val tmp = Mat()
        val ip = Mat()
        Core.multiply(guide, p, ip)
        val cov = box(ip, r)
        Core.multiply(mI, mp, tmp)
        Core.subtract(cov, tmp, cov)
        val ii = Mat()
        Core.multiply(guide, guide, ii)
        val vr = box(ii, r)
        Core.multiply(mI, mI, tmp)
        Core.subtract(vr, tmp, vr)
        Core.add(vr, Scalar(eps), vr)
        val a = Mat()
        Core.divide(cov, vr, a)
        val b = Mat()
        Core.multiply(a, mI, tmp)
        Core.subtract(mp, tmp, b)
        val ma = box(a, r)
        val mb = box(b, r)
        Core.multiply(ma, guide, ma)
        Core.add(ma, mb, ma)
        for (m in listOf(mI, mp, tmp, ip, cov, ii, vr, a, b, mb)) m.release()
        return ma
    }

    // ------------------------------------------------------------------
    // Allineamento
    // ------------------------------------------------------------------
    /** ECC affine su immagini ridotte; con 'mask' (1 = zona da usare) allinea solo su quella. */
    private fun align(ref: Mat, img: Mat, mask: Mat?, sigma: Double, validIn: Mat?): Aligned {
        val h = ref.rows()
        val w = ref.cols()
        val fullSize = Size(w.toDouble(), h.toDouble())
        val s = min(800.0 / max(w, h), 1.0)
        val ga = gray(ref)
        val gb = gray(img)
        val a = Mat()
        val b = Mat()
        Imgproc.resize(ga, a, ZERO, s, s, Imgproc.INTER_AREA)
        Imgproc.resize(gb, b, ZERO, s, s, Imgproc.INTER_AREA)
        ga.release()
        gb.release()
        Imgproc.GaussianBlur(a, a, ZERO, sigma)
        Imgproc.GaussianBlur(b, b, ZERO, sigma)
        val mk = Mat()
        if (mask != null) {
            val ms = Mat()
            Imgproc.resize(mask, ms, Size(a.cols().toDouble(), a.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bin = Mat()
            Imgproc.threshold(ms, bin, 0.5, 255.0, Imgproc.THRESH_BINARY)
            bin.convertTo(mk, CvType.CV_8U)
            ms.release()
            bin.release()
        }
        val base = validIn ?: Mat.ones(h, w, F32)
        return try {
            val warp = Mat.eye(2, 3, F32)
            val crit = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 300, 1e-7)
            Video.findTransformECC(a, b, warp, Video.MOTION_AFFINE, crit, mk, 5)
            val v = FloatArray(6)
            warp.get(0, 0, v)
            v[2] = (v[2] / s).toFloat()
            v[5] = (v[5] / s).toFloat()
            warp.put(0, 0, v)
            val flags = Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP
            val out = Mat()
            Imgproc.warpAffine(img, out, warp, fullSize, flags, Core.BORDER_REPLICATE)
            val valid = Mat()
            Imgproc.warpAffine(base, valid, warp, fullSize, flags, Core.BORDER_CONSTANT, Scalar(0.0))
            val vb = Mat()
            Imgproc.threshold(valid, vb, 0.999, 1.0, Imgproc.THRESH_BINARY)
            Imgproc.erode(vb, vb, Mat.ones(9, 9, CvType.CV_8U))
            valid.release()
            Aligned(out, vb)
        } catch (e: Exception) {
            Aligned(img, base)      // allineamento non riuscito: si usa l'immagine com'è
        } finally {
            a.release()
            b.release()
        }
    }

    // ------------------------------------------------------------------
    // Stima del piano a fuoco
    // ------------------------------------------------------------------
    /** Nitidezza locale normalizzata sulla luminosità locale (le zone scure ma a fuoco non sembrano sfocate). */
    private fun sharp(img: Mat): Mat {
        val gg = gray(img)
        val g = Mat()
        Imgproc.GaussianBlur(gg, g, ZERO, 1.0)
        val lap = Mat()
        Imgproc.Laplacian(g, lap, F32, 3)
        Core.multiply(lap, lap, lap)
        Imgproc.GaussianBlur(lap, lap, ZERO, 6.0)
        Core.sqrt(lap, lap)
        val lum = Mat()
        Imgproc.GaussianBlur(gg, lum, ZERO, 25.0)
        Core.add(lum, Scalar(0.08), lum)
        Core.divide(lap, lum, lap)
        gg.release()
        g.release()
        lum.release()
        return lap
    }

    /** u in 0..1: 1 = sul piano del soggetto, 0 = fuori fuoco. */
    private fun estimateFocus(a: Mat, c: Mat, b: Mat): Mat {
        val sa = sharp(a)
        val sc = sharp(c)
        val sb = sharp(b)
        val e = 0.02 * pct(sa, 95.0)[0] + 1e-6

        val saE = Mat(); Core.add(sa, Scalar(e), saE)
        val sbE = Mat(); Core.add(sb, Scalar(e), sbE)
        val scE = Mat(); Core.add(sc, Scalar(e), scE)
        val r1 = Mat(); Core.divide(saE, sbE, r1); Core.log(r1, r1)
        val r2 = Mat(); Core.divide(saE, scE, r2); Core.log(r2, r2)
        val v = Mat()
        Core.addWeighted(r1, 0.5, r2, 0.5, 0.0, v)

        val tex = Mat()
        Core.max(sa, sb, tex)
        Core.max(tex, sc, tex)
        val p95 = pct(tex, 95.0)[0]
        val conf = Mat()
        tex.convertTo(conf, F32, 1.0 / (0.15 * p95 + 1e-6))
        clip01(conf)

        val guida = gray(a)
        val vc = mul(v, conf)
        val num = guided(guida, vc, RAGGIO_GUIDA * 2, 1e-3)
        val den = guided(guida, conf, RAGGIO_GUIDA * 2, 1e-3)
        Core.max(den, Scalar(1e-3), den)
        val vs = Mat()
        Core.divide(num, den, vs)
        Imgproc.GaussianBlur(vs, vs, ZERO, 3.0)

        // soglia automatica (Otsu) tra «a fuoco» e «fuori fuoco»
        val pp = pct(vs, 1.0, 99.0)
        val loV = pp[0]
        val span = pp[1] - pp[0] + 1e-6
        val vn = Mat()
        vs.convertTo(vn, CvType.CV_8U, 255.0 / span, -loV * 255.0 / span)
        val dummy = Mat()
        val tOtsu = Imgproc.threshold(vn, dummy, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val t = max((loV + tOtsu / 255.0 * (pp[1] - pp[0])) * SOGLIA_FUOCO, 0.05)
        val lo = t * 0.55
        val hi = t * (0.85 + 0.5 * MORBIDEZZA)
        val k = 1.0 / max(hi - lo, 1e-6)
        val u = Mat()
        vs.convertTo(u, F32, k, -lo * k)
        clip01(u)

        // pulizia: via i piccoli isolotti «a fuoco» nello sfondo (punti, riflessi, spigoli isolati)
        val core32 = Mat()
        Imgproc.threshold(u, core32, 0.5, 255.0, Imgproc.THRESH_BINARY)
        val core8 = Mat()
        core32.convertTo(core8, CvType.CV_8U)
        val labels = Mat()
        val stats = Mat()
        val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(core8, labels, stats, cent, 8, CvType.CV_32S)
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        val areaLimit = 0.0003 * u.rows() * u.cols()
        val small = BooleanArray(n)
        var anySmall = false
        for (i in 1 until n) if (st[i * 5 + 4] < areaLimit) { small[i] = true; anySmall = true }
        if (anySmall) {
            val lab = IntArray(u.rows() * u.cols())
            labels.get(0, 0, lab)
            val bytes = ByteArray(lab.size) { if (small[lab[it]]) 1 else 0 }
            val pm = Mat(u.rows(), u.cols(), CvType.CV_8U)
            pm.put(0, 0, bytes)
            val zona8 = Mat()
            Imgproc.dilate(pm, zona8, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0)))
            val zona = Mat()
            zona8.convertTo(zona, F32)
            Imgproc.GaussianBlur(zona, zona, ZERO, 5.0)
            Core.multiply(zona, Scalar(1.5), zona)
            clip01(zona)
            val inv = Mat()
            zona.convertTo(inv, F32, -1.0, 1.0)
            Core.multiply(u, inv, u)
            for (m in listOf(pm, zona8, zona, inv)) m.release()
        }
        val res = guided(guida, u, RAGGIO_GUIDA, 1e-4)
        clip01(res)

        for (m in listOf(sa, sb, sc, saE, sbE, scE, r1, r2, v, tex, conf, guida, vc, num, den, vs, vn, dummy,
            u, core32, core8, labels, stats, cent)) m.release()
        return res
    }

    // ------------------------------------------------------------------
    // Sfocato
    // ------------------------------------------------------------------
    /** Disco con bordo anti-aliasing, normalizzato a somma 1. */
    private fun disc(r0: Double): Mat {
        val r = max(r0, 0.5)
        val n = ceil(r).toInt() * 2 + 1
        val c = n / 2
        val k = FloatArray(n * n)
        var sum = 0f
        for (y in 0 until n) for (x in 0 until n) {
            val d = sqrt(((y - c) * (y - c) + (x - c) * (x - c)).toDouble())
            val v = (r + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
            k[y * n + x] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        val m = Mat(n, n, F32)
        m.put(0, 0, k)
        return m
    }

    /** Sfocatura a disco che usa solo i pixel con peso alto (niente aloni dal soggetto). */
    private fun blurNorm(img: Mat, w: Mat, r: Double): Mat {
        val ker = disc(r)
        val w3 = Mat()
        Core.merge(listOf(w, w, w), w3)
        val prod = Mat()
        Core.multiply(img, w3, prod)
        val num = Mat()
        Imgproc.filter2D(prod, num, -1, ker, ANCHOR, 0.0, Core.BORDER_REFLECT)
        val den = Mat()
        Imgproc.filter2D(w, den, -1, ker, ANCHOR, 0.0, Core.BORDER_REFLECT)
        val plain = Mat()
        Imgproc.filter2D(img, plain, -1, ker, ANCHOR, 0.0, Core.BORDER_REFLECT)
        val denC = Mat()
        Core.max(den, Scalar(1e-3), denC)
        val dc3 = Mat()
        Core.merge(listOf(denC, denC, denC), dc3)
        val out = Mat()
        Core.divide(num, dc3, out)
        val bad = Mat()
        Core.compare(den, Scalar(1e-3), bad, Core.CMP_LE)
        plain.copyTo(out, bad)
        for (m in listOf(ker, w3, prod, num, den, plain, denC, dc3, bad)) m.release()
        return out
    }

    /** Raggio del disco per cui blur(A) assomiglia di più a B nella zona di sfondo «pulita». */
    private fun calibrate(a: Mat, b: Mat, zone8: Mat): Double {
        val s = min(700.0 / max(a.cols(), a.rows()), 1.0)
        val ga0 = gray(a)
        val gb0 = gray(b)
        val ga = Mat()
        val gb = Mat()
        Imgproc.resize(ga0, ga, ZERO, s, s, Imgproc.INTER_AREA)
        Imgproc.resize(gb0, gb, ZERO, s, s, Imgproc.INTER_AREA)
        ga0.release()
        gb0.release()
        val z0 = Mat()
        Imgproc.resize(zone8, z0, Size(ga.cols().toDouble(), ga.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val z = Mat()
        Imgproc.threshold(z0, z, 229.0, 255.0, Imgproc.THRESH_BINARY)
        z0.release()
        if (Core.countNonZero(z) < 500) z.setTo(Scalar(255.0))
        var best = Double.MAX_VALUE
        var bestR = 8.0
        var r = 1.0
        while (r < 45.0) {
            val ker = disc(r)
            val bl = Mat()
            Imgproc.filter2D(ga, bl, -1, ker, ANCHOR, 0.0, Core.BORDER_REFLECT)
            val d = Mat()
            Core.subtract(bl, gb, d)
            Core.multiply(d, d, d)
            val err = Core.mean(d, z).`val`[0]
            if (err < best) { best = err; bestR = r }
            ker.release(); bl.release(); d.release()
            r += 1.0
        }
        ga.release(); gb.release(); z.release()
        return bestR / s
    }

    private fun compose(a: Mat, c: Mat, b: Mat, u: Mat, valid: Mat): Mat {
        val w = u.cols()

        // soggetto «sicuro» e distanza da esso
        val sM = Mat()
        u.convertTo(sM, F32, 1.0 / 0.4, -0.5 / 0.4)
        smooth(sM)
        val inS = Mat()                       // 1 - S
        sM.convertTo(inS, F32, -1.0, 1.0)
        val outside = Mat()
        Core.compare(u, Scalar(0.5), outside, Core.CMP_LE)   // 255 fuori dal nucleo del soggetto
        val d = Mat()
        Imgproc.distanceTransform(outside, d, Imgproc.DIST_L2, 5)

        val fascia = max(FASCIA_ALONE * w, 2.0)
        val wc = Mat()
        d.convertTo(wc, F32, -1.0 / fascia, 1.0)
        clip01(wc)
        Imgproc.GaussianBlur(wc, wc, ZERO, fascia * 0.15)
        Core.multiply(wc, inS, wc)
        // dove l'allineamento ha lasciato bordi vuoti si usa lo sfocato sintetico
        val fuori = Mat()
        valid.convertTo(fuori, F32, -1.0, 1.0)
        Imgproc.GaussianBlur(fuori, fuori, ZERO, 6.0)
        Core.multiply(fuori, Scalar(3.0), fuori)
        clip01(fuori)
        Core.multiply(fuori, inS, fuori)
        Core.max(wc, fuori, wc)

        // scala dei fuochi: u=0 -> vicina, u=0.5 -> intermedia, u=1 -> nitida
        val t1 = Mat(); u.convertTo(t1, F32, 2.0, 0.0); clip01(t1)
        val t2 = Mat(); u.convertTo(t2, F32, 2.0, -1.0); clip01(t2)
        val l1 = lerp(b, c, t1)
        val l2 = lerp(l1, a, t2)

        // sfocato sintetico calibrato su B, usando solo i pixel di sfondo
        val m1 = Mat(); Core.compare(d, Scalar(fascia * 1.5), m1, Core.CMP_GT)
        val m2 = Mat(); Core.compare(u, Scalar(0.15), m2, Core.CMP_LT)
        val m3 = Mat(); Core.compare(valid, Scalar(0.5), m3, Core.CMP_GT)
        Core.bitwise_and(m1, m2, m1)
        Core.bitwise_and(m1, m3, m1)
        val r = calibrate(a, b, m1)
        val syn = blurNorm(a, inS, r)

        var out = lerp(l2, syn, wc)

        if (EXTRA > 0.0) {
            val bl = blurNorm(out, inS, EXTRA * w)
            val o2 = lerp(bl, out, sM)
            out.release()
            bl.release()
            out = o2
        }
        for (m in listOf(sM, inS, outside, d, wc, fuori, t1, t2, l1, l2, m1, m2, m3, syn)) m.release()
        return out
    }

    // ------------------------------------------------------------------
    // Uscita
    // ------------------------------------------------------------------
    /** Ritaglia alla zona presente in tutte e tre le foto, raddrizza e comprime in JPEG. */
    private fun encode(out: Mat, valid: Mat, ori: Int): ByteArray {
        val w = out.cols()
        val h = out.rows()
        val ok32 = Mat()
        Imgproc.threshold(valid, ok32, 0.5, 255.0, Imgproc.THRESH_BINARY)
        val ok8 = Mat()
        ok32.convertTo(ok8, CvType.CV_8U)
        val ok = ByteArray(w * h)
        ok8.get(0, 0, ok)
        ok32.release(); ok8.release()

        // si restringono i lati finché i bordi del rettangolo sono tutti validi (la zona valida è convessa)
        var t = 0
        var bt = h
        var l = 0
        var r = w
        val step = 4
        while ((bt - t) > h / 2 && (r - l) > w / 2) {
            var cT = 0; var cB = 0; var cL = 0; var cR = 0
            for (x in l until r) for (k in 0 until step) {
                if (ok[(t + k) * w + x].toInt() == 0) cT++
                if (ok[(bt - 1 - k) * w + x].toInt() == 0) cB++
            }
            for (y in t until bt) for (k in 0 until step) {
                if (ok[y * w + l + k].toInt() == 0) cL++
                if (ok[y * w + r - 1 - k].toInt() == 0) cR++
            }
            val mx = max(max(cT, cB), max(cL, cR))
            if (mx == 0) break
            when (mx) {
                cT -> t += step
                cB -> bt -= step
                cL -> l += step
                else -> r -= step
            }
        }

        val roi = out.submat(t, bt, l, r)
        val u8 = Mat()
        roi.convertTo(u8, CvType.CV_8U, 255.0)
        val rgba = Mat()
        Imgproc.cvtColor(u8, rgba, Imgproc.COLOR_RGB2RGBA)
        val bm = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bm)
        u8.release(); rgba.release()
        val fin = if (ori % 360 != 0)
            Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(ori.toFloat()) }, true)
        else bm
        val bos = ByteArrayOutputStream(1 shl 20)
        fin.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        return bos.toByteArray()
    }
}
