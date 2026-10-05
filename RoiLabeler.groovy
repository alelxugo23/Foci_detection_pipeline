import ij.ImagePlus
import ij.WindowManager
import ij.plugin.frame.RoiManager

/**
 * ROI/image linking utilities.
 *
 * The authoritative relationship is the timepoint tag, e.g. "_72hrs".
 * A linked ROI may also contain the source image title in a prefix:
 *
 *   ROI 1:myObject_72hrs
 *
 * The image title might be:
 *
 *   image_72hrs.tif
 *
 * Measurement resolves the ROI back to that image and therefore to
 * that image's live Set Scale calibration.
 */
class RoiLabeler {

    /**
     * Property key stamped on each ROI at link time (see
     * linkSelectedIndexToImage below). The same literal appears in
     * RoiExportDivider and MeasurementGrouper: all of these classes are
     * parsed independently by Foci_Listeners' GroovyClassLoader, so a
     * cross-class constant reference would not resolve at parse time.
     * Changing it means changing it in all of them.
     */
    static final String SOURCE_PROPERTY = "fociSourceImage"

    // ---------------------------------------------------------------
    // Sequential "Point_N" numbering
    // ---------------------------------------------------------------
    //
    // HISTORY - why this is a scan, not a counter (kept for context):
    //
    // This was originally a static Map<String,Integer> POINT_COUNTERS,
    // keyed by source image title, incremented once per link and never
    // read back from anything. It was write-only state: the number it
    // handed out described how many times this class had been ASKED for
    // a number, not what was actually sitting in the ROI Manager.
    //
    // That is fine exactly as long as nobody ever deletes or renames an
    // ROI - which is not a workflow anyone can actually hold to, since
    // a misclick with the Wand is a normal event and its only remedy is
    // Delete. Delete Point_5 and the counter stayed at 5, so the next
    // Add became Point_6 with no Point_5 in the list; the sequence was
    // permanently gapped and permanently one ahead of the truth, with
    // no way to resynchronise short of restarting the listeners (which
    // reloaded the class and reset every image's count to zero - the
    // opposite failure, colliding with the points still on screen).
    // Rename had the same shape: correcting Point_7 back to Point_5 by
    // hand left the counter still at 7.
    //
    // The fix is not to track the deletes and renames - it is to stop
    // tracking anything. The ROI Manager list IS the count. Reading the
    // highest Point_N currently present for this image and adding one
    // means a delete, a rename, a reload from .zip and a manual
    // re-capture are all automatically and identically accounted for,
    // because none of them are special cases: they are just the list
    // being different next time it is read. No new listener, no delete
    // hook, no rename hook, nothing to keep in sync.
    //
    // This also closes a latent bug that predates the delete problem:
    // loading a previously saved ROI set from .zip and then adding a
    // new point used to restart that image at Point_1, silently
    // duplicating a name the loaded set already contained. The scan
    // sees the loaded ROIs and carries on past them.
    //
    // Numbering is per SOURCE IMAGE, not per tag - two open images that
    // share an _xhrs tag (HSP70B_1_72hrs, HSP70B_2_72hrs) each keep an
    // independent 1, 2, 3... sequence. Ownership is decided by the
    // stamped SOURCE_PROPERTY where present, falling back to the
    // timepoint tag in the name for ROIs that predate the stamp.

    /**
     * The N in a "Point_N..." name, or -1 if this name is not one.
     * Requires the digits to be followed by an underscore or the end of
     * the string, so "Point_12_72hrs" -> 12 and never 1.
     */
    static int parsePointNumber(String roiName) {
        if (roiName == null) return -1
        def m = roiName =~ /(?i)^point_(\d+)(?:_|$)/
        if (!m.find()) return -1
        try {
            return Integer.parseInt(m.group(1))
        } catch (NumberFormatException ignored) {
            return -1
        }
    }

    /** The SOURCE_PROPERTY stamped on an ROI, or null if absent. */
    static String getStampedSourceImage(roi) {
        if (roi == null) return null
        try {
            def m = roi.getClass().getMethod("getProperty", String.class)
            String v = (String) m.invoke(roi, SOURCE_PROPERTY)
            return (v == null || v.isEmpty()) ? null : v
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Does this ROI belong to the given image's point sequence?
     *
     * The stamp is authoritative when present - it records the image
     * that was actually active at link time. Only when it is missing
     * (ROIs from before the stamp existed, or saved by an older ImageJ)
     * do we fall back to matching the timepoint tag, which is the same
     * guess the rest of the toolkit makes in that situation.
     */
    private static boolean belongsToImage(roi, String imageTitle, String tag) {
        if (roi == null || imageTitle == null) return false

        String stamped = getStampedSourceImage(roi)
        if (stamped != null) return stamped == imageTitle

        String roiTag = getLinkedTag(roi.getName())
        return roiTag != null && tag != null && roiTag.equalsIgnoreCase(tag)
    }

    /**
     * The next point number for this image: one past the highest
     * Point_N currently in the ROI Manager for it.
     *
     * skipIndex is the ROI about to be renamed, excluded from its own
     * lookup - otherwise re-capturing an already-named Point_3 would
     * count Point_3 as competition and bump it to Point_4 every time
     * the button was pressed.
     *
     * Highest-plus-one rather than lowest-free deliberately: deleting
     * Point_5 of 1..5 gives 5 back on the next add, which is what
     * "undo that misclick" should do, while deleting Point_3 of 1..5
     * leaves the gap at 3 alone and continues at 6 rather than
     * reissuing a number whose measurements may already be sitting in
     * the Results table under the old identity.
     */
    static synchronized int nextPointNumber(RoiManager rm, String imageTitle,
                                            String tag, int skipIndex) {
        if (rm == null || imageTitle == null) return 1

        int highest = 0
        for (int i = 0; i < rm.getCount(); i++) {
            if (i == skipIndex) continue
            def roi = rm.getRoi(i)
            if (roi == null) continue

            int n = parsePointNumber(roi.getName())
            if (n < 1) continue
            if (!belongsToImage(roi, imageTitle, tag)) continue

            if (n > highest) highest = n
        }
        return highest + 1
    }

    static String normalizeTag(String raw) {
        if (raw == null) return null
        String s = raw.trim()
        if (s.isEmpty()) return null
        return s.startsWith("_") ? s : "_" + s
    }

    static String getLinkedTag(String roiName) {
        if (roiName == null) return null
        def m = roiName =~ /(?i)(_\d+h(?:ou)?rs?)(?=$|[^a-zA-Z0-9])/
        return m.find() ? m.group(1) : null
    }

    static ImagePlus findOpenImageByTag(String tag) {
        if (tag == null) return null
        int[] ids = WindowManager.getIDList()
        if (ids == null) return null
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id)
            if (imp == null) continue
            if (imp.getTitle().toLowerCase().contains(tag.toLowerCase())) return imp
        }
        return null
    }

    static String getLinkedImageTitle(String roiName) {
        String tag = getLinkedTag(roiName)
        ImagePlus imp = findOpenImageByTag(tag)
        return imp != null ? imp.getTitle() : null
    }

    /**
     * Link a single ROI (by index) to the given image's timepoint tag.
     *
     * Any existing timepoint suffix on the ROI's current name is
     * stripped and replaced, so re-linking an ROI (whether by the
     * auto-tag listener firing again, or by a manual re-capture)
     * updates the link in place instead of creating duplicate tags.
     *
     * This is the single-ROI primitive used both by the manual
     * "Capture Selected ROIs" button (linkSelectedToImage, below) and
     * by RoiAssigner's live "tag it the instant it's added" listener.
     *
     * The ROI's new name is always "Point_N" + tag, where N is one past
     * the highest Point_N currently in the ROI Manager for that SOURCE
     * IMAGE (see nextPointNumber() above) - e.g. the first point linked
     * to "HSP70B_72hrs.JPG" becomes "Point_1_72hrs", the next one on
     * that same image becomes "Point_2_72hrs", and so on. Because that
     * number is read from the list rather than remembered, deleting the
     * most recent point hands its number straight back, and renaming
     * one by hand is picked up with no further intervention. This
     * replaces
     * whatever coordinate-based name ROI Manager auto-assigned on
     * add (e.g. "0100-0102") entirely - it is not preserved or
     * appended.
     */
    static int linkSelectedIndexToImage(RoiManager rm, ImagePlus imp, String rawTag, int index) {
        if (rm == null || imp == null) return 0
        if (index < 0 || index >= rm.getCount()) return 0

        String tag = normalizeTag(rawTag)
        if (tag == null) return 0

        def roi = rm.getRoi(index)
        String oldName = roi.getName() ?: ("ROI-" + (index + 1))

        int pointNum = nextPointNumber(rm, imp.getTitle(), tag, index)
        String newName = "Point_" + pointNum + tag
        // NOTE: was previously called as RoiManager.setSelectedIndexes(...)
        // (static, no instance) - setSelectedIndexes is an INSTANCE method;
        // that call threw groovy.lang.MissingMethodException the moment it
        // actually ran ("No signature of method: static ...setSelectedIndexes()
        // is applicable"). rm is already in scope right here - use it.
        rm.setSelectedIndexes([index] as int[])
        rm.rename(newName)

        // Stamp the image that was ACTUALLY active at link time onto
        // the ROI itself. The name alone carries only the timepoint
        // tag ("_72hrs"), which is not unique: two open stacks from
        // the same timepoint (HSP70B_1_72hrs and HSP70B_2_72hrs) are
        // indistinguishable from the ROI name afterwards, and any
        // later attempt to resolve one has to fall back to "first
        // open image containing that tag" - a guess that is wrong
        // half the time in exactly that case.
        //
        // Recording it here, at the one moment it is known for
        // certain, is what lets RoiExportDivider split the shared ROI
        // list into per-image files exactly rather than by inference,
        // and MeasurementGrouper recover the series even when
        // Set Measurements > Display label is switched off. ij.gui.Roi
        // properties are written into .roi/.zip files by ImageJ
        // itself, so this survives a save and reload.
        //
        // Wrapped: Roi.setProperty arrived in a later ImageJ version
        // than this toolkit's floor, and a missing property only
        // costs the fallback path, not the tagging.
        // Called reflectively, not as named.setProperty(k, v):
        // "setProperty" is the name Groovy's MetaClass reserves for
        // property-assignment dispatch, so the direct call invites
        // Groovy to route it through its own property machinery
        // instead of ImageJ's Roi.setProperty(String, String).
        // Reflection names the Java method unambiguously, and doubles
        // as the version guard - on an ImageJ build predating
        // Roi.setProperty this is a quiet no-op rather than a load
        // failure.
        try {
            def named = rm.getRoi(index)
            if (named != null) {
                def m = named.getClass().getMethod("setProperty", String.class, String.class)
                m.invoke(named, "fociSourceImage", imp.getTitle())
            }
        } catch (Throwable ignored) { }

        return 1
    }

    /**
     * Link currently selected ROIs (possibly several) to the active
     * image. Kept for the manual "Capture Selected ROIs From Current
     * Image" button - useful as a fallback/override, e.g. re-linking
     * ROIs that were created before RoiAssigner's live listener was
     * enabled, or deliberately re-pointing ROIs at a different image.
     */
    static int linkSelectedToImage(RoiManager rm, ImagePlus imp, String rawTag) {
        if (rm == null || imp == null) return 0

        int[] selected = rm.getSelectedIndexes()
        if (selected == null) return 0

        int count = 0
        for (int i : selected) {
            count += linkSelectedIndexToImage(rm, imp, rawTag, i)
        }
        return count
    }

    /**
     * "Obtained ROI Label" is the label before the timepoint tag.
     * If the ROI has a colon, everything after the colon is used first,
     * matching the original RenameLabelsInTable macro behavior.
     */
    static String cleanObtainedLabel(String name) {
        if (name == null) return ""
        String s = name

        int colon = s.indexOf(":")
        if (colon >= 0) s = s.substring(colon + 1)

        s = s.replaceAll(
            /(?i)_\d+h(?:ou)?rs?(?=$|[^a-zA-Z0-9])/,
            ""
        )

        return s.trim()
    }

    static Map countLinkedRois(RoiManager rm) {
        int linked = 0
        int unlinked = 0

        if (rm == null) return [linked:0, unlinked:0]

        for (int i = 0; i < rm.getCount(); i++) {
            String name = rm.getRoi(i).getName() ?: ""
            String tag = getLinkedTag(name)
            ImagePlus imp = findOpenImageByTag(tag)
            if (tag != null && imp != null) linked++
            else unlinked++
        }
        return [linked:linked, unlinked:unlinked]
    }
}
