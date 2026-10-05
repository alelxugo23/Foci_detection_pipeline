import ij.ImagePlus
import ij.ImageListener
import ij.WindowManager
import ij.process.ColorProcessor

/**
 * Live RGB threshold engine, shared by Batch_Threshold.ijm and
 * Foci_Listeners.groovy's own "Batch Threshold" button. This is the
 * ONE place the current R/G/B min/max values live.
 *
 * Paints a SOLID highlight color directly into each matched image's
 * real pixels - not a mask, not an Overlay/ImageRoi. That's what makes
 * the highlighted region immediately usable with ImageJ's own,
 * unmodified Magic Wand toolbar tool: the Wand traces by real pixel
 * color similarity, so it needs real, uniform pixel data to read, not
 * a semi-transparent layer drawn on top that the Wand can't see at
 * all. No custom Wand-simulation code, no hijacked mouse listener -
 * select the actual Wand tool from the toolbar and click, same as any
 * other ImageJ workflow.
 *
 * Non-destructive in the sense that matters: each image's own
 * ColorProcessor keeps ImageJ's built-in undo buffer (ip.snapshot()/
 * ip.reset(), the same mechanism Edit > Undo relies on everywhere in
 * ImageJ). snapshot() is called once, the first time an image is
 * seen, on its clean pixels; every subsequent apply() restores from
 * that buffer before repainting, so re-running never compounds, and
 * clear() restores the original untouched image on demand. Nothing is
 * ever written to disk unless the person explicitly saves.
 *
 * Order independence:
 *   - start() sweeps every image already open (applyAll()), so if
 *     Batch_Threshold.ijm ran first, opening Foci_Listeners.groovy (or
 *     clicking its "Batch Threshold" button) backfills highlights on
 *     everything already open.
 *   - the ImageListener highlights each new image the moment it opens,
 *     so if Foci_Listeners.groovy's engine was already running first,
 *     Batch_Threshold.ijm's images come up already highlighted.
 */
class ThresholdSync {

    /** Read by Batch_Threshold.ijm via getInfo() - purely informational, never blocks it. */
    static final String LIVE_FLAG = 'fociworkflow.live'
    static final int HIT_R = 255, HIT_G = 0, HIT_B = 255 // solid magenta

    static final Map<String, Integer> DEFAULTS = [
        redMin: 0,   redMax: 122,
        greenMin: 134, greenMax: 255,
        blueMin: 117, blueMax: 243
    ]

    static int redMin   = DEFAULTS.redMin
    static int redMax   = DEFAULTS.redMax
    static int greenMin = DEFAULTS.greenMin
    static int greenMax = DEFAULTS.greenMax
    static int blueMin  = DEFAULTS.blueMin
    static int blueMax  = DEFAULTS.blueMax

    // Which images have had their clean pixels snapshotted. That's
    // ImageProcessor's own undo buffer doing the storage - this set
    // only remembers WHICH images have one, not the pixels themselves.
    private static final Set<ImagePlus> WATCHED =
        Collections.newSetFromMap(new WeakHashMap<ImagePlus, Boolean>())
    private static final Map<ImagePlus, int[]> STATS = new WeakHashMap<ImagePlus, int[]>()

    private static ImageListener imageListener
    private static boolean listening = false

    // ---------------------------------------------------------------- lifecycle

    static synchronized void start() {
        if (listening) return
        listening = true

        imageListener = [
            imageOpened : { ImagePlus imp -> if (imp != null) watch(imp) },
            imageClosed : { ImagePlus imp ->
                if (imp == null) return
                WATCHED.remove(imp); STATS.remove(imp)
            },
            imageUpdated: { ImagePlus imp -> }
        ] as ImageListener

        ImagePlus.addImageListener(imageListener)
        System.setProperty(LIVE_FLAG, '1')
        applyAll() // backfill anything already open (e.g. a prior batch run)
    }

    static synchronized void stop() {
        if (!listening) return
        try { ImagePlus.removeImageListener(imageListener) } catch (Throwable ignored) {}
        imageListener = null
        listening = false
        clearAll()
        WATCHED.clear(); STATS.clear()
        System.clearProperty(LIVE_FLAG)
    }

    static boolean isLive() { listening }

    static Map<String, Integer> values() {
        [redMin: redMin, redMax: redMax, greenMin: greenMin,
         greenMax: greenMax, blueMin: blueMin, blueMax: blueMax]
    }

    // ---------------------------------------------------------------- watching

    /** Takes the one-time clean snapshot, then paints immediately. */
    private static void watch(ImagePlus imp) {
        if (imp == null || imp.getBitDepth() != 24) return
        if (WATCHED.contains(imp)) { apply(imp); return }
        ColorProcessor cp = imp.getProcessor() as ColorProcessor
        if (cp == null) return
        cp.snapshot()
        WATCHED.add(imp)
        apply(imp)
    }

    // ---------------------------------------------------------------- applying

    /** Recompute the highlight on every open RGB image - watching any not seen yet. */
    static void applyAll() {
        int[] ids = WindowManager.getIDList()
        if (ids == null) return
        ids.each { int id ->
            ImagePlus imp = WindowManager.getImage(id)
            if (imp == null || imp.getBitDepth() != 24) return
            if (WATCHED.contains(imp)) apply(imp) else watch(imp)
        }
    }

    /**
     * Restores this image's clean pixels from its own snapshot buffer,
     * then paints matches solid - real pixel data, Wand-traceable,
     * reversible via the same buffer on the next call or clear().
     */
    static Map apply(ImagePlus imp) {
        if (imp == null) return [error: 'No image.']
        if (imp.getBitDepth() != 24) return [error: 'Image must be RGB Color.']
        if (!WATCHED.contains(imp)) { watch(imp); return statsFor(imp) ?: [error: 'Could not watch image.'] }

        ColorProcessor cp = imp.getProcessor() as ColorProcessor
        if (cp == null) return [error: 'No processor.']
        cp.reset() // restore clean pixels from this processor's own snapshot buffer

        int[] pixels = (int[]) cp.getPixels()
        int rlo = redMin, rhi = redMax, glo = greenMin, ghi = greenMax, blo = blueMin, bhi = blueMax
        int n = pixels.length
        int hit = (HIT_R << 16) | (HIT_G << 8) | HIT_B

        int matched = 0
        for (int i = 0; i < n; i++) {
            int p = pixels[i]
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255
            if (r >= rlo && r <= rhi && g >= glo && g <= ghi && b >= blo && b <= bhi) {
                pixels[i] = hit
                matched++
            }
        }
        STATS[imp] = [matched, n] as int[]
        imp.updateAndDraw()

        return [matched: matched, total: n, percent: n == 0 ? 0.0d : 100.0d * matched / n]
    }

    // ---------------------------------------------------------------- stats

    static Map statsFor(ImagePlus imp) {
        if (imp == null) return null
        int[] s = STATS[imp]
        if (s == null) return null
        [matched: s[0], total: s[1], percent: s[1] == 0 ? 0.0d : 100.0d * s[0] / s[1]]
    }

    static int highlightedCount() {
        int[] ids = WindowManager.getIDList()
        if (ids == null) return 0
        int c = 0
        ids.each { int id ->
            ImagePlus imp = WindowManager.getImage(id)
            if (imp != null && STATS.containsKey(imp)) c++
        }
        c
    }

    // ---------------------------------------------------------------- clearing

    /** Restores this image's clean pixels from its own snapshot buffer and redraws. */
    static void clear(ImagePlus imp) {
        if (imp == null || !WATCHED.contains(imp)) return
        ColorProcessor cp = imp.getProcessor() as ColorProcessor
        if (cp != null) {
            cp.reset()
            imp.updateAndDraw()
        }
        STATS.remove(imp)
    }

    static void clearAll() {
        int[] ids = WindowManager.getIDList()
        if (ids == null) return
        ids.each { int id -> clear(WindowManager.getImage(id)) }
    }
}
