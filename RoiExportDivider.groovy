import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.Roi
import ij.gui.GenericDialog
import ij.plugin.frame.RoiManager
import java.awt.Button
import java.awt.Component
import java.awt.Container
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.event.ActionListener
import java.lang.reflect.Field

/**
 * RoiExportDivider
 * ----------------
 * The automation of the one step in this workflow that was still
 * being done by hand: selecting a "group" of ROIs out of the one big
 * shared ROI Manager list and exporting just that group, once per
 * image, over and over.
 *
 * THE PROBLEM
 * -----------
 * ROI Manager is a single flat list. A batch session fills it with
 * ROIs from every image in the run at once - Point_1_72hrs,
 * Point_2_72hrs, Point_1_96hrs, ... - and its native Save writes
 * exactly that: one .zip of everything, with no record of which ROI
 * came from which image. Getting per-image files out of it natively
 * means manually shift-selecting each image's ROIs and saving them
 * one group at a time, which is both slow and the easiest place in
 * the whole workflow to silently mis-group something.
 *
 * WHAT THIS DOES INSTEAD
 * ----------------------
 * It divides that single list automatically, on the same two facts
 * the rest of this toolkit already keys off:
 *
 *   1. the _xhrs timepoint tag carried by the ROI's own name
 *      ("Point_3_96hrs" -> "_96hrs"), and
 *   2. the shared name of the image stack it belongs to
 *      ("HSP70B_1_72hrs.JPG" -> series "HSP70B_1").
 *
 * Together those give the exact image identity - HSP70B_1_72hrs -
 * which becomes the output filename, so each file is named for the
 * image whose coordinates it holds, and files from one stack sort
 * together under their shared series name.
 *
 * WHICH IMAGE AN ROI CAME FROM - EXACTLY, NOT BY INFERENCE
 * -------------------------------------------------------
 * Resolution is tried in strict order of reliability:
 *
 *   1. The ROI's own "fociSourceImage" property, stamped onto it by
 *      RoiLabeler at the moment it was linked (see RoiLabeler's
 *      linkSelectedIndexToImage). This is the ONLY unambiguous
 *      source: it records the image that was actually active when
 *      the ROI was created, and ij.gui.Roi properties are persisted
 *      inside .roi/.zip files, so it survives a save/reload.
 *   2. Tag lookup via RoiLabeler.findOpenImageByTag - the fallback
 *      for ROIs created before this property existed, or reloaded
 *      from an older set. NOTE this is ambiguous by nature: it
 *      returns the FIRST open image containing that tag, so if
 *      HSP70B_1_72hrs and HSP70B_2_72hrs are open simultaneously,
 *      every "_72hrs" ROI without a stamped property resolves to
 *      whichever one ImageJ lists first. Rows resolved this way are
 *      marked resolved_by=tag_lookup in the manifest rather than
 *      being quietly mixed in with exact ones.
 *   3. Neither - the ROI is collected under "(unresolved)" and
 *      written to its own file, never silently dropped and never
 *      guessed into someone else's image.
 *
 * OUTPUT FORMAT
 * -------------
 * One CSV per image, one row per polygon vertex, so the coordinates
 * are usable directly as segmentation/object-detection training
 * annotations against the base-truth image they came from:
 *
 *   image,series,timepoint_hrs,roi_index,roi_name,point,roi_type,
 *   image_width,image_height,bbox_x,bbox_y,bbox_w,bbox_h,
 *   vertex_index,x,y
 *
 * Coordinates are pixel coordinates in the source image's own frame
 * (Roi.getFloatPolygon()), deliberately NOT scaled by Set Scale
 * calibration: a segmentation model is trained against pixels, and
 * image_width/image_height are included on every row so the set can
 * be normalised to 0-1 or converted to COCO/YOLO downstream without
 * needing the original images on hand to look up their dimensions.
 * Calibrated, unit-bearing numbers are what the MEASUREMENT half of
 * the workflow produces - see MeasurementGrouper - and the two are
 * kept strictly separate for that reason.
 *
 * Optionally it also writes a real ROI .zip per image, via
 * RoiManager's own Save command against just that group - i.e.
 * exactly the file the manual select-a-group-and-save routine would
 * have produced, just done for every group in one pass.
 *
 * A manifest (_export_manifest.csv) records every file written, its
 * ROI and vertex counts, and how each group's image was resolved.
 *
 * HOW IT'S TRIGGERED
 * ------------------
 * intercept() silences ROI Manager's OWN Save/Export handler and
 * answers the click itself - the same silence-the-host, don't-stack-
 * a-second-listener pattern MeasureAutoRename uses on Measure, and
 * for the same reason: AWT fires every registered ActionListener in
 * order and gives none of them a way to cancel another, so the only
 * way to actually replace a native click is to detach its listener
 * and put it back later (restore()).
 *
 * Save lives in ROI Manager's "More >>" popup in current FIJI and
 * was a top-level button in older ones, so findAndIntercept() checks
 * BOTH: the popup (located by reflection, since RoiManager exposes
 * no accessor for it) and the button bar. Reflection into another
 * class's private field is exactly the kind of thing that breaks on
 * a version bump, so nothing here depends on it succeeding -
 * interception is an ergonomic shortcut, and every failure path
 * returns false quietly. The panel's own "Export ROIs (Divided)"
 * button calls promptAndExport() directly and always works.
 */
class RoiExportDivider {

    /**
     * Property key stamped on each ROI by RoiLabeler at link time.
     * The same literal appears in RoiLabeler and MeasurementGrouper:
     * all three classes are parsed independently by Foci_Listeners'
     * GroovyClassLoader, so a cross-class constant reference would
     * not resolve at parse time. Changing it means changing it in all
     * three.
     */
    static final String SOURCE_PROPERTY = "fociSourceImage"

    static final def TAG_PATTERN = ~/(?i)(_\d+h(?:ou)?rs?)(?=$|[^a-zA-Z0-9])/

    // Captured original listeners, keyed by widget identity, so
    // restore() puts back precisely what was there - never a guess.
    private static final Map ORIGINAL_LISTENERS =
        Collections.synchronizedMap(new IdentityHashMap())

    // ---------------------------------------------------------------
    // Name parsing
    // ---------------------------------------------------------------

    static String stripExtension(String title) {
        if (title == null) return null
        int dot = title.lastIndexOf('.')
        return (dot > 0) ? title.substring(0, dot) : title
    }

    static String extractTag(String s) {
        if (s == null) return null
        def m = s =~ TAG_PATTERN
        return m.find() ? m.group(1) : null
    }

    /** Hours as an integer for sorting: "_96hrs" -> 96, null -> -1. */
    static int extractHours(String s) {
        if (s == null) return -1
        def m = s =~ /(?i)_(\d+)\s*h(?:ou)?rs?/
        return m.find() ? Integer.parseInt(m.group(1)) : -1
    }

    /**
     * The shared name of the stack: the image stem with its timepoint
     * tag removed, so HSP70B_1_72hrs -> HSP70B_1. Any underscore left
     * dangling where the tag used to be is trimmed, so
     * HSP70B_72hrs_b -> HSP70B_b rather than HSP70B__b.
     */
    static String seriesName(String imageStem) {
        if (imageStem == null) return "(unresolved)"
        String s = imageStem.replaceAll(TAG_PATTERN, "")
        s = s.replaceAll(/__+/, "_").replaceAll(/^_|_$/, "")
        return s.isEmpty() ? imageStem : s
    }

    /** "Point_3_96hrs" -> "Point_3"; falls back to the whole name. */
    static String pointName(String roiName) {
        if (roiName == null) return ""
        String s = roiName
        int colon = s.indexOf(":")
        if (colon >= 0) s = s.substring(colon + 1)
        s = s.replaceAll(TAG_PATTERN, "")
        s = s.replaceAll(/__+/, "_").replaceAll(/_$/, "")
        return s.trim()
    }

    /** Point number for sorting: "Point_3_96hrs" -> 3, else -1. */
    static int pointNumber(String roiName) {
        if (roiName == null) return -1
        def m = roiName =~ /(?i)point[_\s-]?(\d+)/
        return m.find() ? Integer.parseInt(m.group(1)) : -1
    }

    /** Anything a filesystem might object to becomes an underscore. */
    static String safeFileName(String s) {
        if (s == null || s.trim().isEmpty()) return "unnamed"
        return s.replaceAll(/[\\\/:*?"<>|]/, "_").replaceAll(/\s+/, "_")
    }

    // ---------------------------------------------------------------
    // Resolving each ROI back to its image
    // ---------------------------------------------------------------

    /**
     * Reads ij.gui.Roi's own getProperty(String) REFLECTIVELY rather
     * than as roi.getProperty(key). That looks like the long way
     * round, and it is deliberate: "getProperty" is the exact name
     * Groovy's MetaClass reserves for property access dispatch, so
     * writing the direct call invites Groovy to route it through its
     * own property machinery - which answers with a
     * MissingPropertyException for a key that is an ImageJ Roi
     * property rather than a JavaBean property. Going through
     * java.lang.reflect names the Java method unambiguously.
     *
     * The same reflection also makes the ImageJ version floor a
     * non-issue: Roi.getProperty arrived later than this toolkit's
     * minimum, and on an older build this returns null (falling back
     * to tag lookup) instead of failing to load the class at all.
     */
    static String readRoiProperty(Roi roi, String key) {
        if (roi == null) return null
        try {
            def m = roi.getClass().getMethod("getProperty", String.class)
            Object v = m.invoke(roi, key)
            return (v == null) ? null : String.valueOf(v)
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Returns a Map describing one ROI:
     *   index, name, point, pointNum, tag, hours,
     *   image      - image stem, e.g. "HSP70B_1_72hrs"
     *   series     - shared stack name, e.g. "HSP70B_1"
     *   imp        - the live ImagePlus, or null if it isn't open
     *   resolvedBy - "roi_property" | "tag_lookup" | "unresolved"
     */
    static Map describeRoi(RoiManager rm, int index, roiLabelerClass) {
        Roi roi = rm.getRoi(index)
        String name = (roi?.getName()) ?: ("ROI-" + (index + 1))
        String tag = roiLabelerClass.getLinkedTag(name) ?: extractTag(name)

        String sourceTitle = readRoiProperty(roi, SOURCE_PROPERTY)
        String resolvedBy = "unresolved"

        if (sourceTitle != null && !sourceTitle.trim().isEmpty()) {
            resolvedBy = "roi_property"
        } else if (tag != null) {
            ImagePlus byTag = roiLabelerClass.findOpenImageByTag(tag)
            if (byTag != null) {
                sourceTitle = byTag.getTitle()
                resolvedBy = "tag_lookup"
            }
        }

        ImagePlus imp = (sourceTitle != null) ? WindowManager.getImage(sourceTitle) : null
        String stem = (sourceTitle != null) ? stripExtension(sourceTitle) : "(unresolved)"

        return [
            index     : index,
            name      : name,
            point     : pointName(name),
            pointNum  : pointNumber(name),
            tag       : tag ?: "",
            hours     : extractHours(tag ?: name),
            image     : stem,
            series    : (sourceTitle != null) ? seriesName(stem) : "(unresolved)",
            imp       : imp,
            resolvedBy: resolvedBy
        ]
    }

    /**
     * Divides the given ROI indices into per-image groups, keyed by
     * image stem and ordered the way the data is actually read:
     * series, then timepoint, then point number.
     */
    static Map<String, List<Map>> groupByImage(RoiManager rm, int[] indices, roiLabelerClass) {
        Map<String, List<Map>> byImage = new LinkedHashMap<String, List<Map>>()
        if (rm == null || indices == null) return byImage

        List<Map> described = []
        indices.each { int i ->
            if (i >= 0 && i < rm.getCount()) described << describeRoi(rm, i, roiLabelerClass)
        }
        described.sort { a, b ->
            int c = (a.series <=> b.series)
            if (c != 0) return c
            c = (a.hours <=> b.hours)
            if (c != 0) return c
            c = (a.pointNum <=> b.pointNum)
            if (c != 0) return c
            return (a.index <=> b.index)
        }
        described.each { Map d ->
            if (!byImage.containsKey(d.image)) byImage[d.image] = []
            byImage[d.image] << d
        }
        return byImage
    }

    // ---------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------

    static final String CSV_HEADER =
        "image,series,timepoint_hrs,roi_index,roi_name,point,roi_type," +
        "image_width,image_height,bbox_x,bbox_y,bbox_w,bbox_h,vertex_index,x,y"

    private static String fmt(double v) {
        return String.format("%.3f", v)
    }

    /**
     * Writes one image's ROIs as vertex rows. Returns
     * [rois: n, vertices: n] so the manifest can record whether a
     * group actually produced geometry - a count of 0 vertices for a
     * non-zero ROI count is the signal that something upstream
     * produced ROIs this can't polygonise.
     */
    static Map writeImageCsv(RoiManager rm, String imageStem, List<Map> rois, File outFile) {
        StringBuilder sb = new StringBuilder()
        sb.append(CSV_HEADER).append("\n")

        int vertexTotal = 0
        rois.each { Map d ->
            Roi roi = rm.getRoi(d.index as int)
            if (roi == null) return

            ImagePlus imp = d.imp as ImagePlus
            int iw = (imp != null) ? imp.getWidth() : -1
            int ih = (imp != null) ? imp.getHeight() : -1

            def bounds = roi.getBounds()
            String typeName
            try { typeName = roi.getTypeAsString() } catch (Throwable ignored) { typeName = String.valueOf(roi.getType()) }

            def poly = null
            try { poly = roi.getFloatPolygon() } catch (Throwable ignored) { poly = null }

            String prefix = [
                imageStem, d.series, (d.hours as int) >= 0 ? String.valueOf(d.hours) : "",
                String.valueOf((d.index as int) + 1), d.name, d.point, typeName,
                String.valueOf(iw), String.valueOf(ih),
                String.valueOf(bounds.x), String.valueOf(bounds.y),
                String.valueOf(bounds.width), String.valueOf(bounds.height)
            ].collect { csvCell(it) }.join(",")

            if (poly != null && poly.npoints > 0) {
                for (int v = 0; v < poly.npoints; v++) {
                    sb.append(prefix).append(",")
                      .append(v).append(",")
                      .append(fmt(poly.xpoints[v] as double)).append(",")
                      .append(fmt(poly.ypoints[v] as double)).append("\n")
                    vertexTotal++
                }
            } else {
                // No polygon geometry (e.g. a point/line ROI type that
                // returns nothing): the ROI is still recorded, with an
                // empty vertex index and its bounding box centre, so it
                // is never silently absent from the file.
                sb.append(prefix).append(",,")
                  .append(fmt(bounds.x + bounds.width / 2.0d)).append(",")
                  .append(fmt(bounds.y + bounds.height / 2.0d)).append("\n")
            }
        }

        outFile.getParentFile()?.mkdirs()
        outFile.setText(sb.toString(), "UTF-8")
        return [rois: rois.size(), vertices: vertexTotal]
    }

    private static String csvCell(Object o) {
        String s = (o == null) ? "" : String.valueOf(o)
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    /**
     * Saves one image's ROIs as a real ROI .zip using RoiManager's own
     * Save command - the identical file the manual "select this group,
     * save it" routine produces. The manager's selection is restored
     * afterwards so this never leaves the UI in a state the user
     * didn't ask for.
     */
    static boolean writeImageRoiZip(RoiManager rm, List<Map> rois, File outFile) {
        try {
            int[] before = rm.getSelectedIndexes()
            int[] group = rois.collect { it.index as int } as int[]
            rm.setSelectedIndexes(group)
            outFile.getParentFile()?.mkdirs()
            rm.runCommand("Save", outFile.getAbsolutePath())
            if (before != null && before.length > 0) rm.setSelectedIndexes(before)
            else rm.deselect()
            return outFile.exists()
        } catch (Throwable t) {
            IJ.log("RoiExportDivider: ROI .zip save failed for " + outFile.getName() + " - " + t.getMessage())
            return false
        }
    }

    /**
     * The whole divide-and-write pass.
     *
     * @param opts  writeCsv, writeZip, seriesSubfolders (booleans)
     * @return summary Map: files, rois, vertices, unresolved, dir
     */
    static Map exportDivided(RoiManager rm, int[] indices, File destDir, Map opts, roiLabelerClass) {
        boolean writeCsv = (opts?.writeCsv != false)
        boolean writeZip = (opts?.writeZip == true)
        boolean subfolders = (opts?.seriesSubfolders == true)

        Map<String, List<Map>> byImage = groupByImage(rm, indices, roiLabelerClass)

        StringBuilder manifest = new StringBuilder()
        manifest.append("file,image,series,timepoint_hrs,roi_count,vertex_count,resolved_by\n")

        int fileCount = 0
        int roiCount = 0
        int vertexCount = 0
        int unresolved = 0
        List<String> written = []

        byImage.each { String imageStem, List<Map> rois ->
            String series = rois[0].series
            int hours = rois[0].hours as int
            String resolvedBy = rois.collect { it.resolvedBy }.unique().sort().join("|")
            if (imageStem == "(unresolved)") unresolved += rois.size()

            File dir = subfolders ? new File(destDir, safeFileName(series)) : destDir
            String base = safeFileName(imageStem)

            if (writeCsv) {
                File csv = new File(dir, base + ".csv")
                Map r = writeImageCsv(rm, imageStem, rois, csv)
                fileCount++
                roiCount += (r.rois as int)
                vertexCount += (r.vertices as int)
                written << csv.getAbsolutePath()
                manifest.append([
                    csvCell(csv.getName()), csvCell(imageStem), csvCell(series),
                    (hours >= 0 ? String.valueOf(hours) : ""),
                    String.valueOf(r.rois), String.valueOf(r.vertices), csvCell(resolvedBy)
                ].join(",")).append("\n")
            }

            if (writeZip) {
                File zip = new File(dir, base + "_RoiSet.zip")
                if (writeImageRoiZip(rm, rois, zip)) {
                    fileCount++
                    written << zip.getAbsolutePath()
                    manifest.append([
                        csvCell(zip.getName()), csvCell(imageStem), csvCell(series),
                        (hours >= 0 ? String.valueOf(hours) : ""),
                        String.valueOf(rois.size()), "", csvCell(resolvedBy)
                    ].join(",")).append("\n")
                }
            }
        }

        File manifestFile = new File(destDir, "_export_manifest.csv")
        manifestFile.getParentFile()?.mkdirs()
        manifestFile.setText(manifest.toString(), "UTF-8")

        return [
            files: fileCount, groups: byImage.size(), rois: roiCount,
            vertices: vertexCount, unresolved: unresolved,
            dir: destDir.getAbsolutePath(), written: written,
            manifest: manifestFile.getAbsolutePath()
        ]
    }

    /**
     * The prompt that appears in place of a plain native save. Shows
     * exactly how many groups the division found BEFORE anything is
     * written, so a wrong grouping is visible at the point it can
     * still be cancelled. Returns the summary Map from
     * exportDivided(), or null if the user cancelled or chose the
     * native single-file save (in which case doNativeSave is called).
     */
    static Map promptAndExport(RoiManager rm, roiLabelerClass, Closure doNativeSave, Closure logger) {
        if (rm == null || rm.getCount() == 0) {
            IJ.showMessage("Export ROIs", "ROI Manager is empty - nothing to export.")
            return null
        }

        int[] selected = rm.getSelectedIndexes()
        boolean hadSelection = (selected != null && selected.length > 0)
        int[] indices = hadSelection ? selected : ((0..<rm.getCount()) as int[])

        Map<String, List<Map>> preview = groupByImage(rm, indices, roiLabelerClass)
        int unresolvedCount = (preview["(unresolved)"]?.size()) ?: 0

        GenericDialog gd = new GenericDialog("Export ROIs - divide by image?")
        gd.addMessage(
            (hadSelection ? (indices.length + " selected ROI(s)") : (indices.length + " ROI(s) (nothing selected - using all)")) +
            " divide into " + preview.size() + " image group(s):")
        StringBuilder lines = new StringBuilder()
        preview.each { String img, List<Map> rois ->
            lines.append("    ").append(img).append("  -  ").append(rois.size()).append(" ROI(s)\n")
        }
        gd.addMessage(lines.toString())
        if (unresolvedCount > 0) {
            gd.addMessage(unresolvedCount + " ROI(s) could not be resolved to an image and will go to\n" +
                "their own \"(unresolved).csv\" rather than being guessed into a group.")
        }
        gd.addCheckbox("Write coordinate CSV per image (segmentation training data)", true)
        gd.addCheckbox("Write ROI .zip set per image (what manual group-saving produces)", false)
        gd.addCheckbox("One subfolder per series (e.g. HSP70B_1/)", true)
        gd.addCheckbox("Skip dividing - do the plain native save instead", false)
        gd.showDialog()
        if (gd.wasCanceled()) {
            if (logger) logger("Export cancelled - nothing written.")
            return null
        }

        boolean wantCsv = gd.getNextBoolean()
        boolean wantZip = gd.getNextBoolean()
        boolean wantSubfolders = gd.getNextBoolean()
        boolean wantNative = gd.getNextBoolean()

        if (wantNative) {
            if (logger) logger("Export: native single-file save requested - divider stood aside.")
            if (doNativeSave) doNativeSave()
            return null
        }
        if (!wantCsv && !wantZip) {
            IJ.showMessage("Export ROIs", "Nothing to write - pick CSV, ROI .zip, or both.")
            return null
        }

        String dir = IJ.getDirectory("Choose a destination folder for the divided ROI files")
        if (dir == null || dir.isEmpty()) {
            if (logger) logger("Export cancelled at folder selection - nothing written.")
            return null
        }

        Map summary = exportDivided(rm, indices, new File(dir),
            [writeCsv: wantCsv, writeZip: wantZip, seriesSubfolders: wantSubfolders], roiLabelerClass)

        if (logger) {
            logger("Export divided: " + summary.groups + " image group(s) -> " + summary.files +
                " file(s) in " + summary.dir +
                " (" + summary.rois + " ROI(s), " + summary.vertices + " vertex row(s)" +
                (summary.unresolved > 0 ? ", " + summary.unresolved + " unresolved" : "") + ").")
        }
        return summary
    }

    // ---------------------------------------------------------------
    // Interception of ROI Manager's own Save/Export
    // ---------------------------------------------------------------

    /**
     * Finds ROI Manager's "More >>" PopupMenu. RoiManager keeps it in
     * a private field and exposes no accessor, so this reflects over
     * its declared fields (and its superclasses') looking for a
     * PopupMenu - rather than assuming a field name that has changed
     * across versions. Returns null on any failure, including the
     * JVM refusing the access: no caller treats null as an error.
     */
    static PopupMenu findPopupMenu(RoiManager rm) {
        if (rm == null) return null
        try {
            Class c = rm.getClass()
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (PopupMenu.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true)
                        Object v = f.get(rm)
                        if (v instanceof PopupMenu) return (PopupMenu) v
                    }
                }
                c = c.getSuperclass()
            }
        } catch (Throwable ignored) { }
        return null
    }

    /** Same dual AWT/Swing button search MeasureAutoRename uses. */
    static Component findButton(Container root, String labelPrefix) {
        if (root == null) return null
        for (Component c : root.getComponents()) {
            if (c instanceof Button) {
                String l = ((Button) c).getLabel()
                if (l != null && l.startsWith(labelPrefix)) return c
            }
            if (c instanceof javax.swing.AbstractButton) {
                String l = ((javax.swing.AbstractButton) c).getText()
                if (l != null && l.startsWith(labelPrefix)) return c
            }
            if (c instanceof Container) {
                Component found = findButton((Container) c, labelPrefix)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Silences ROI Manager's own Save/Export handler wherever it lives
     * - popup item and/or button - and runs onExportClicked in its
     * place. Idempotent per widget instance: a second call on an
     * already-silenced widget is a no-op, so it never re-captures our
     * own peripheral as if it were the host and loses the real one.
     *
     * @return the number of widgets now silenced (0 = interception
     *         unavailable in this FIJI build; the panel button still
     *         works, so this is not an error)
     */
    static int intercept(RoiManager rm, Closure onExportClicked) {
        if (rm == null) return 0
        int count = 0

        PopupMenu pm = findPopupMenu(rm)
        if (pm != null) {
            for (int i = 0; i < pm.getItemCount(); i++) {
                MenuItem mi = pm.getItem(i)
                String label = mi?.getLabel()
                if (label == null) continue
                if (!(label.startsWith("Save") || label.startsWith("Export"))) continue
                if (ORIGINAL_LISTENERS.containsKey(mi)) { count++; continue }
                def existing = mi.getActionListeners() as List
                ORIGINAL_LISTENERS[mi] = existing
                existing.each { l -> mi.removeActionListener(l) }
                mi.addActionListener({ e -> onExportClicked() } as ActionListener)
                count++
            }
        }

        Component saveBtn = findButton(rm, "Save")
        if (saveBtn != null && !ORIGINAL_LISTENERS.containsKey(saveBtn)) {
            def existing = saveBtn.getActionListeners() as List
            ORIGINAL_LISTENERS[saveBtn] = existing
            existing.each { l -> saveBtn.removeActionListener(l) }
            saveBtn.addActionListener({ e -> onExportClicked() } as ActionListener)
            count++
        } else if (saveBtn != null) {
            count++
        }

        return count
    }

    /**
     * Re-attaches the exact original handlers captured by intercept().
     * Safe on widgets that were never intercepted, and on a
     * RoiManager that has since been closed and reopened as a new
     * instance (its widgets were never captured, so there is nothing
     * to double-restore).
     */
    static int restore(RoiManager rm) {
        if (rm == null) return 0
        int count = 0

        PopupMenu pm = findPopupMenu(rm)
        if (pm != null) {
            for (int i = 0; i < pm.getItemCount(); i++) {
                MenuItem mi = pm.getItem(i)
                if (mi == null || !ORIGINAL_LISTENERS.containsKey(mi)) continue
                mi.getActionListeners().each { l -> mi.removeActionListener(l) }
                ORIGINAL_LISTENERS.remove(mi).each { l -> mi.addActionListener(l) }
                count++
            }
        }

        Component saveBtn = findButton(rm, "Save")
        if (saveBtn != null && ORIGINAL_LISTENERS.containsKey(saveBtn)) {
            saveBtn.getActionListeners().each { l -> saveBtn.removeActionListener(l) }
            ORIGINAL_LISTENERS.remove(saveBtn).each { l -> saveBtn.addActionListener(l) }
            count++
        }

        return count
    }

    /**
     * Runs ROI Manager's genuine save, by calling the original
     * listener(s) this class silenced - used for the "skip dividing"
     * option in the prompt, so choosing it produces the identical
     * native dialog and file the button would have produced
     * untouched. Falls back to rm.runCommand("Save") if no original
     * was captured (i.e. the panel button path, where nothing was
     * ever intercepted).
     */
    static void runNativeSave(RoiManager rm) {
        if (rm == null) return

        // RoiManager's OWN public runCommand("Save") is tried first,
        // not the captured listener: it is a documented public entry
        // point into the same save() the button calls, including its
        // SaveDialog, so it behaves identically whether or not
        // anything was ever intercepted - which matters for the panel
        // button path, where nothing was.
        try {
            rm.runCommand("Save")
            return
        } catch (Throwable ignored) { }

        // Fallback: replay the exact listener intercept() captured,
        // with the action command RoiManager's own actionPerformed
        // dispatches on.
        try {
            def entry = ORIGINAL_LISTENERS.find { k, v -> v != null && !v.isEmpty() }
            if (entry != null) {
                def evt = new java.awt.event.ActionEvent(entry.key,
                    java.awt.event.ActionEvent.ACTION_PERFORMED, "Save...")
                entry.value.each { ActionListener l -> l.actionPerformed(evt) }
            }
        } catch (Throwable ignored) { }
    }
}
