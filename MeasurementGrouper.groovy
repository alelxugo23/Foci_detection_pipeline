import ij.IJ
import ij.gui.GenericDialog
import ij.measure.ResultsTable
import java.io.File

/**
 * MeasurementGrouper
 * ------------------
 * Groups measurement rows so every timepoint of one point sits
 * together: Point_1 at 72hrs, 96hrs, 120hrs, 144hrs as four
 * consecutive rows, then Point_2, and so on.
 *
 * Replaces Rename_Labels_In_Table.ijm.
 *
 * WHAT IT READS - AND WHAT IT DELIBERATELY DOESN'T
 * ------------------------------------------------
 * Everything it needs is in the ROI's own name. "Point_1_96hrs" gives
 * a point number and an hour count, and those two numbers are the
 * whole grouping. Nothing is derived from the image title.
 *
 * An earlier version also parsed a "Series" (HSP70B_1) out of the
 * image half of the Results label and grouped by it. That is gone. It
 * inferred a fact nobody had stated, from a string ImageJ only writes
 * when a preference is switched on, and it added a column whose value
 * was wrong exactly when it mattered. The ROI name is authoritative
 * and always present; the image title is neither.
 *
 * Consequence worth knowing: rows are grouped by point NUMBER alone.
 * Since RoiLabeler numbers points per source image starting at 1,
 * measuring two different stacks into one Results table puts both
 * their Point_1 rows in the same group. Measure one stack at a time,
 * or clear Results between stacks.
 *
 * WHERE THE ROI NAME COMES FROM
 * -----------------------------
 * From the row map Foci_Listeners hands in: it knows precisely which
 * ROIs it measured, in which order, so it records row index -> ROI
 * name as the measurement runs.
 *
 * That map exists because the previous version read the Results
 * "Label" column instead, and that column is written ONLY when
 * Set Measurements > Display label is ticked - a preference this
 * toolkit doesn't own and can't assume. With it off there is no Label
 * at all, every row parsed as nothing, and the table was rebuilt with
 * NaN in every added column: a total failure caused by a checkbox,
 * announced as "no rows carried a parseable timepoint".
 *
 * The label is still used as a fallback for rows the map doesn't
 * cover - a table measured before this window was open, or reloaded
 * from disk - and Foci_Listeners now also switches Display label on
 * so that fallback has something to read. But nothing depends on it.
 *
 * FAILING WITHOUT DAMAGE
 * ----------------------
 * If not one row yields a point or an hour, this returns a failure
 * and leaves the Results window ALONE. The old version rebuilt the
 * table regardless, so a parse failure was paid for with a table full
 * of NaN that had to be re-measured. A failed grouping should cost
 * nothing but a log line.
 */
class MeasurementGrouper {

    static final String COL_POINT = "Point"
    static final String COL_HOURS = "Timepoint_hrs"
    static final String COL_ROI = "ROI"

    static final def TAG_PATTERN = ~/(?i)(_\d+h(?:ou)?rs?)(?=$|[^a-zA-Z0-9])/

    // ---------------------------------------------------------------
    // Parsing - all of it against the ROI name
    // ---------------------------------------------------------------

    /** "Point_1_96hrs" -> 96. -1 when absent. */
    static int hoursOf(String s) {
        if (s == null) return -1
        def m = s =~ /(?i)_(\d+)\s*h(?:ou)?rs?/
        return m.find() ? Integer.parseInt(m.group(1)) : -1
    }

    /** "Point_1_96hrs" -> 1. -1 when absent. */
    static int pointNumberOf(String s) {
        if (s == null) return -1
        def m = s =~ /(?i)point[_\s-]?(\d+)/
        return m.find() ? Integer.parseInt(m.group(1)) : -1
    }

    /** "HSP70B_1_96hrs.JPG:Point_1_96hrs" -> "Point_1_96hrs". */
    static String stripImagePrefix(String s) {
        if (s == null) return null
        int colon = s.indexOf(":")
        return (colon >= 0) ? s.substring(colon + 1) : s
    }

    /** "Point_1_96hrs" -> "Point_1". */
    static String cleanPointName(String roiName) {
        if (roiName == null) return ""
        String s = stripImagePrefix(roiName)
        s = s.replaceAll(TAG_PATTERN, "")
        s = s.replaceAll(/__+/, "_").replaceAll(/_$/, "")
        return s.trim()
    }

    /**
     * The ROI name for one Results row: the row map first, the Label
     * column second. Returns null when neither has anything.
     */
    static String roiNameForRow(ResultsTable rt, int row, Map rowSources) {
        if (rowSources != null) {
            Object mapped = rowSources[row]
            if (mapped != null) {
                String s = String.valueOf(mapped).trim()
                if (!s.isEmpty()) return s
            }
        }
        String label = rt.getLabel(row)
        if (label == null) {
            try { label = rt.getStringValue("Label", row) } catch (Throwable ignored) { label = null }
        }
        if (label == null || label.trim().isEmpty()) return null
        return stripImagePrefix(label.trim())
    }

    // ---------------------------------------------------------------
    // Regrouping
    // ---------------------------------------------------------------

    /**
     * Returns a NEW ResultsTable with Point / Timepoint_hrs / ROI
     * prepended and rows ordered Point -> Timepoint, or null if not a
     * single row could be parsed. The input table is never mutated.
     */
    static ResultsTable regroup(ResultsTable rt, Map rowSources) {
        if (rt == null || rt.size() == 0) return null

        String[] headings = rt.getHeadings()

        List<Map> rows = []
        int parsedRows = 0
        for (int r = 0; r < rt.size(); r++) {
            String name = roiNameForRow(rt, r, rowSources)
            int point = pointNumberOf(name)
            int hours = hoursOf(name)
            if (point >= 0 || hours >= 0) parsedRows++
            rows << [row: r, name: name ?: "", roi: cleanPointName(name),
                     point: point, hours: hours]
        }

        // Nothing parsed anywhere: refuse rather than rebuild the table
        // into NaN columns. See the class comment.
        if (parsedRows == 0) return null

        rows.sort { a, b ->
            // Unparsed rows (-1) sort to the END, not ahead of Point_1:
            // the bottom of the table is where a reader looks for what
            // didn't work, the top is where they read real data.
            int pa = (a.point as int) < 0 ? Integer.MAX_VALUE : (a.point as int)
            int pb = (b.point as int) < 0 ? Integer.MAX_VALUE : (b.point as int)
            int c = (pa <=> pb)
            if (c != 0) return c
            int ha = (a.hours as int) < 0 ? Integer.MAX_VALUE : (a.hours as int)
            int hb = (b.hours as int) < 0 ? Integer.MAX_VALUE : (b.hours as int)
            c = (ha <=> hb)
            if (c != 0) return c
            return (a.row <=> b.row)
        }

        ResultsTable out = new ResultsTable()
        rows.each { Map m ->
            int r = m.row as int
            out.incrementCounter()

            String label = rt.getLabel(r)
            if (label != null && !label.isEmpty()) out.addLabel(label)

            // Point and Timepoint_hrs are written as numbers even when
            // absent (NaN), never as "". A ResultsTable column is one
            // type for every row: an empty string anywhere makes the
            // whole column a string column, and a string column sorts
            // 120 before 72 downstream - the exact mis-ordering this
            // class exists to prevent.
            out.addValue(COL_POINT, ((m.point as int) >= 0) ? ((m.point as int) as double) : Double.NaN)
            out.addValue(COL_HOURS, ((m.hours as int) >= 0) ? ((m.hours as int) as double) : Double.NaN)
            out.addValue(COL_ROI, (String) m.roi)

            headings.each { String h ->
                if (h == null || h == "Label" || h == " ") return
                if (h == COL_POINT || h == COL_HOURS || h == COL_ROI) return
                int col = rt.getColumnIndex(h)
                if (col == ResultsTable.COLUMN_NOT_FOUND) return
                double v = Double.NaN
                try { v = rt.getValueAsDouble(col, r) } catch (Throwable ignored) { v = Double.NaN }
                if (Double.isNaN(v)) {
                    String sv = ""
                    try { sv = rt.getStringValue(col, r) } catch (Throwable ignored) { sv = "" }
                    out.addValue(h, sv ?: "")
                } else {
                    out.addValue(h, v)
                }
            }
        }
        return out
    }

    /**
     * Regroups the live Results window. Returns
     *   [ok: true, rows: n, points: n, table: rt]   on success
     *   [ok: false, reason: "..."]                  on failure
     * and on failure the Results window is left untouched.
     */
    static Map applyToResultsWindow(Map rowSources) {
        ResultsTable rt = ResultsTable.getResultsTable()
        if (rt == null || rt.size() == 0) {
            return [ok: false, reason: "the Results table is empty."]
        }

        ResultsTable grouped = regroup(rt, rowSources)
        if (grouped == null) {
            return [ok: false, reason:
                "no row carried a Point number or an _xhrs tag, so Results was left unchanged. " +
                "Either these rows were measured before this window was open, or the ROIs were " +
                "never auto-tagged (an ROI still named like \"2387-2251\" was added while no " +
                "_xhrs-tagged image was active)."]
        }

        grouped.show("Results")

        Set points = new LinkedHashSet()
        for (int r = 0; r < grouped.size(); r++) points << grouped.getStringValue(COL_ROI, r)
        return [ok: true, rows: grouped.size(), points: points.size(), table: grouped]
    }

    static Map applyToResultsWindow() { return applyToResultsWindow(null) }

    // ---------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------

    /** Long format: the grouped table exactly as shown. */
    static boolean writeLongCsv(ResultsTable rt, File outFile) {
        if (rt == null) return false
        try {
            outFile.getParentFile()?.mkdirs()
            rt.saveAs(outFile.getAbsolutePath())
            return true
        } catch (Throwable t) {
            IJ.log("MeasurementGrouper: long CSV write failed - " + t.getMessage())
            return false
        }
    }

    private static String csvCell(Object o) {
        String s = (o == null) ? "" : String.valueOf(o)
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    /**
     * Wide format: one row per point, one column per timepoint,
     * holding the chosen measurement - the layout a per-point trend
     * across time gets plotted from. Missing cells are left EMPTY, not
     * zero-filled: an absent measurement and a measured zero are not
     * the same thing and must not become the same cell.
     */
    static boolean writeWideCsv(ResultsTable rt, String measurement, File outFile) {
        if (rt == null || rt.size() == 0) return false

        int mcol = rt.getColumnIndex(measurement)
        if (mcol == ResultsTable.COLUMN_NOT_FOUND) {
            IJ.log("MeasurementGrouper: column \"" + measurement + "\" not in the Results table.")
            return false
        }
        int hcol = rt.getColumnIndex(COL_HOURS)
        if (hcol == ResultsTable.COLUMN_NOT_FOUND) {
            IJ.log("MeasurementGrouper: no " + COL_HOURS + " column - regroup the table first.")
            return false
        }

        Map<String, Map<Integer, String>> cells = new LinkedHashMap<>()
        TreeSet<Integer> allHours = new TreeSet<>()

        for (int r = 0; r < rt.size(); r++) {
            double hoursVal = rt.getValueAsDouble(hcol, r)
            if (Double.isNaN(hoursVal)) continue
            int hours = (int) hoursVal
            String roi = rt.getStringValue(COL_ROI, r)
            if (roi == null || roi.trim().isEmpty()) continue

            if (!cells.containsKey(roi)) cells[roi] = new LinkedHashMap<Integer, String>()
            double v = rt.getValueAsDouble(mcol, r)
            cells[roi][hours] = Double.isNaN(v) ? "" : String.valueOf(v)
            allHours << hours
        }

        if (cells.isEmpty()) {
            IJ.log("MeasurementGrouper: no rows carried a timepoint - wide file not written.")
            return false
        }

        StringBuilder sb = new StringBuilder()
        sb.append("Point,Measurement")
        allHours.each { h -> sb.append(",").append(h).append("hrs") }
        sb.append("\n")
        cells.each { String roi, Map<Integer, String> byHour ->
            sb.append(csvCell(roi)).append(",").append(csvCell(measurement))
            allHours.each { h -> sb.append(",").append(csvCell(byHour[h] ?: "")) }
            sb.append("\n")
        }

        try {
            outFile.getParentFile()?.mkdirs()
            outFile.setText(sb.toString(), "UTF-8")
            return true
        } catch (Throwable t) {
            IJ.log("MeasurementGrouper: wide CSV write failed - " + t.getMessage())
            return false
        }
    }

    /**
     * The "Group Measurements" action: regroup the live Results
     * window, then offer to write it out long and/or wide.
     */
    static Map promptAndExport(Map rowSources, Closure logger) {
        Map summary = applyToResultsWindow(rowSources)
        if (!summary.ok) {
            IJ.showMessage("Group Measurements", "Could not group: " + summary.reason)
            if (logger) logger("Group Measurements: " + summary.reason)
            return summary
        }

        ResultsTable grouped = summary.table as ResultsTable
        if (logger) {
            logger("Grouped " + summary.rows + " measurement row(s) into " +
                summary.points + " point series (Point -> Timepoint).")
        }

        List<String> numericCols = []
        grouped.getHeadings().each { String h ->
            if (h == null || h == "Label" || h == COL_ROI || h == COL_POINT || h == COL_HOURS) return
            int col = grouped.getColumnIndex(h)
            if (col == ResultsTable.COLUMN_NOT_FOUND) return
            // Probed across several rows, not just row 0: one NaN in
            // the first row would otherwise hide a good column.
            int probe = Math.min(grouped.size(), 20)
            for (int r = 0; r < probe; r++) {
                if (!Double.isNaN(grouped.getValueAsDouble(col, r))) { numericCols << h; break }
            }
        }

        GenericDialog gd = new GenericDialog("Group Measurements")
        gd.addMessage("Results is grouped: " + summary.rows + " row(s), " +
            summary.points + " point series.")
        gd.addCheckbox("Write grouped CSV (long - one row per measurement)", true)
        if (!numericCols.isEmpty()) {
            gd.addCheckbox("Also write wide (one row per point, one column per timepoint)", true)
            gd.addChoice("Measurement to pivot:", numericCols as String[], numericCols[0])
        }
        gd.showDialog()
        if (gd.wasCanceled()) {
            if (logger) logger("Grouping applied to the Results window; no files written.")
            return summary
        }

        boolean wantLong = gd.getNextBoolean()
        boolean wantWide = false
        String measurement = null
        if (!numericCols.isEmpty()) {
            wantWide = gd.getNextBoolean()
            measurement = gd.getNextChoice()
        }
        if (!wantLong && !wantWide) return summary

        String dir = IJ.getDirectory("Choose a destination folder for the grouped measurements")
        if (dir == null || dir.isEmpty()) {
            if (logger) logger("Grouping applied; file write cancelled at folder selection.")
            return summary
        }

        if (wantLong) {
            File f = new File(dir, "measurements_grouped.csv")
            if (writeLongCsv(grouped, f) && logger) logger("Wrote " + f.getAbsolutePath())
        }
        if (wantWide && measurement != null) {
            File f = new File(dir, "measurements_by_point_" +
                measurement.replaceAll(/[^A-Za-z0-9]+/, "_") + ".csv")
            if (writeWideCsv(grouped, measurement, f) && logger) logger("Wrote " + f.getAbsolutePath())
        }
        return summary
    }
}
