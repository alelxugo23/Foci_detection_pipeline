// ============================================================
// Foci_Listeners.groovy
// ============================================================
// The single entry point for this toolkit. Everything ships as ONE
// folder - this script and its eight modules side by side, dropped
// into plugins/ - and Foci_Listeners.groovy is the ONLY one of them
// that appears in the Plugins menu, because ImageJ only surfaces a
// script whose filename contains an underscore. None of the module
// filenames do. So they all load, none of them clutter the dropdown,
// and there is one window, one entry, nothing to run in the wrong
// order.
//
// The module folder is FOUND at startup rather than hardcoded (see
// the search below), so the folder can be named anything, or its
// contents tipped straight into plugins/, and this still resolves.
//
//   RETIRED: Rename_Labels_In_Table.ijm - the one other menu entry,
//                                   a macro that rewrote each Results
//                                   row's Label to whatever followed
//                                   the colon. Its cosmetic cleanup
//                                   DESTROYED the image prefix, which
//                                   is the only field identifying
//                                   which stack a row came from - the
//                                   exact field MeasurementGrouper
//                                   now groups by. It is superseded,
//                                   not merely absorbed: the clean
//                                   point name still appears, as its
//                                   own ROI column, with the full
//                                   original Label left intact beside
//                                   it. Delete the .ijm from the
//                                   plugins folder if it is still
//                                   there; leaving it costs a second
//                                   menu entry that now does active
//                                   harm if clicked after a measure.
//
//   RETIRED: Batch_Threshold.ijm and Start_Threshold_Engine.groovy -
//                                   a standalone .ijm entry point
//                                   that bootstrapped ThresholdSync
//                                   via the macro function
//                                   runMacroFile(), so a batch could
//                                   be run without this window open.
//                                   Removed after runMacroFile()
//                                   turned out to throw "Undefined
//                                   identifier" in a real Fiji
//                                   install, despite being documented
//                                   as a real macro function - a
//                                   cross-language bridge that's one
//                                   more thing to break for a
//                                   convenience nobody actually
//                                   needed. Batch Threshold now lives
//                                   ONLY as a button here, pure
//                                   Groovy, no macro bridging at all.
//
// No Set Scale button: that already exists natively (Analyze > Set
// Scale...).
//
// Batch Threshold is deliberately the FIRST button in the row,
// because it is the first thing done in a session: it opens a folder
// tree recursively, converts each image to RGB Color, and - because
// it runs inside THIS already-open window - ThresholdSync is already
// loaded and the engine already running before a single file opens.
// So every image comes up highlighted immediately, AND RoiAssigner/
// the tables are already live and watching the moment a Wand-selected
// blob becomes an ROI. The button order left-to-right is the workflow
// order: threshold a batch, measure what you tagged, export the ROIs,
// group the measurements.
//
// Highlighting itself is automatic and fixed - ThresholdSync.groovy's
// own hardcoded DEFAULTS, no sliders, nothing to adjust. Matches are
// painted as real, solid pixel data (reversible via each image's own
// ImageProcessor undo buffer), not a mask or an Overlay, specifically
// so ImageJ's own, unmodified Magic Wand toolbar tool can select a
// blob directly: pick the Wand tool, click the highlighted patch,
// press 't' - RoiAssigner below tags the result exactly like any
// hand-drawn ROI, since it has no way to tell the difference and was
// never meant to.
//
// What this file actually wires up, and why each piece is a listener
// rather than a button (see the class files themselves for the full
// reasoning, corrected against ImageJ's real GitHub source where
// noted):
//
//   RoiAssigner            - polls RoiManager.getCount() on a fast
//                            timer (roiAddPollTimer below) rather than
//                            subscribing to ij.gui.RoiListener. That
//                            event-based approach was tried first and
//                            fixed twice (RoiListener.ADDED never
//                            existed - real constant is CREATED; and
//                            CREATED also fires on a plain selection
//                            click, not just a genuine add, which
//                            needed its own growing-count guard) - but
//                            even after both fixes, tagging was still
//                            consistently ONE ACTION BEHIND: an ROI
//                            didn't get its Point_N label until the
//                            NEXT one was added. The remaining cause is
//                            timing, not logic - CREATED doesn't fire
//                            at a point where getCount() is guaranteed
//                            to already reflect the just-added ROI.
//                            Polling the count directly, like
//                            calibrationPollTimer already does for Set
//                            Scale and RoiSelectionGrouper already does
//                            for list selection, sidesteps that timing
//                            issue entirely - see RoiAssigner.groovy's
//                            own class comment for the full history.
//
//   RoiSelectionGrouper    - polls RoiManager.getSelectedIndexes()
//                            every 400ms (RoiManager.getList() is a
//                            deprecated/disconnected stub in current
//                            FIJI - confirmed from source - so a real
//                            selection-changed event isn't available
//                            to subscribe to). Feeds the "Selection
//                            Groups (live)" table below.
//
//   calibrationPollTimer   - polls each open image's Calibration
//                            every 800ms. Necessary because Set Scale
//                            (ij.plugin.filter.ScaleDialog, confirmed
//                            from source) finishes by calling
//                            imp.repaintWindow(), never
//                            imp.updateAndDraw() - so it NEVER fires
//                            ImageListener.imageUpdated(), no matter
//                            how it's invoked.
//
//   ImageListener          - native, fires correctly for image
//                            open/close/pixel-update events (verified
//                            against ImagePlus.java's show()/flush()).
//                            Keeps the Images table in sync with
//                            windows opening and closing.
//
//   CommandListener         - native (Executer.addCommandListener).
//                            Logs when "Set Scale..." runs, from
//                            wherever it was invoked.
//
//   MeasureAutoRename       - finds ROI Manager's own native
//                            "Measure" button/widget directly (robust
//                            to either legacy AWT Button or Swing
//                            AbstractButton, since which one FIJI
//                            uses has changed across versions) and
//                            SILENCES its native click handler,
//                            running a peripheral in its place while
//                            attached - not appending a second
//                            listener alongside it. Native ROI
//                            Manager's own Measure always resolves
//                            ONE active image for the whole selection
//                            (confirmed from RoiManager.java's own
//                            source), silently misapplying scale to
//                            any ROI selected from a different image;
//                            the peripheral measures each linked
//                            group against its own actual image
//                            instead, then hands off to
//                            MeasurementGrouper (which supersedes the
//                            retired Rename_Labels_In_Table.ijm) and
//                            brings Results to front - a bubble
//                            labeled "Image ROI Scaling" confirms it
//                            ran.
//                            Stopping the listeners (or closing this
//                            window) re-attaches the real native
//                            handler with no trace of having been
//                            silenced.
//
//   RoiExportDivider       - silences ROI Manager's own Save/Export
//                            (popup item and/or button, whichever
//                            this FIJI build uses) the same way
//                            MeasureAutoRename silences Measure, and
//                            answers the click itself: a prompt shows
//                            how the one shared ROI list divides into
//                            per-image groups - by _xhrs tag AND the
//                            shared stack name, e.g. HSP70B_1_72hrs -
//                            and writes one coordinate CSV per image
//                            instead of one undifferentiated .zip of
//                            everything. This automates the manual
//                            routine of shift-selecting each image's
//                            ROIs out of the shared list and saving
//                            them one group at a time. Interception
//                            needs reflection to find ROI Manager's
//                            private popup, which is exactly the kind
//                            of thing a version bump breaks, so
//                            nothing depends on it: the panel's own
//                            "Export ROIs (Divided)" button calls the
//                            same code path directly and always
//                            works.
//
//   MeasurementGrouper     - runs automatically after every Measure
//                            (and on demand from its own button).
//                            Prepends Point / Timepoint_hrs / ROI to
//                            the Results table and orders rows
//                            Point -> Timepoint, so Point_1_72hrs,
//                            Point_1_96hrs, Point_1_120hrs and
//                            Point_1_144hrs land in one contiguous
//                            block instead of being sorted and
//                            re-pasted by hand. Timepoints sort
//                            NUMERICALLY - as text, 120hrs and 144hrs
//                            both come before 72hrs, which is the
//                            kind of mis-ordering that survives a
//                            visual check.
//
//                            It groups on the ROI NAME ONLY, via the
//                            row map recorded by runGroupedMeasure
//                            below. It does not infer anything from
//                            the image title: an earlier version
//                            parsed a "Series" out of the Results
//                            label and grouped by that, which failed
//                            wholesale the moment Set Measurements >
//                            Display label was off, since the label
//                            it parsed did not exist. This is what
//                            replaces Rename_Labels_In_Table.ijm.
//
//   ThresholdSync          - loaded below (soft dependency - a
//                            missing file just disables the "Batch
//                            Threshold" button's highlighting, it
//                            doesn't block this window) and started
//                            immediately, so this window is always
//                            "already listening" the moment it's open.
// ============================================================

import javax.swing.*
import javax.swing.table.DefaultTableModel
import java.util.List
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import ij.IJ
import ij.WindowManager
import ij.ImagePlus
import ij.ImageListener
import ij.CommandListener
import ij.Executer
import ij.plugin.frame.RoiManager
import ij.measure.ResultsTable

def requiredFiles = [
    "CalibrationIO.groovy",
    "ScaleMonitor.groovy",
    "RoiLabeler.groovy",
    "RoiAssigner.groovy",
    "RoiSelectionGrouper.groovy",
    "MeasureAutoRename.groovy",
    "RoiExportDivider.groovy",
    "MeasurementGrouper.groovy"
]

// -------------------------------------------------------------
// Where the modules live: FOUND, not assumed.
//
// This used to be hardcoded to plugins/Foci Workflow, which made the
// single most likely install mistake - dropping the folder one level
// too deep, or renaming it - fail with a "missing files" dialog that
// named files which were, in fact, sitting right next to this script.
//
// Everything now ships as ONE folder containing this file and its
// modules together, so the honest answer to "where are the modules"
// is "wherever this file ended up". Since a Groovy script run through
// ImageJ's script engine has no reliable way to ask for its own path
// (no __FILE__, and getCodeSource() is null for scripts), this looks
// instead for a directory that CONTAINS the modules, checking:
//
//   1. plugins/Foci Workflow      - the documented layout, checked
//                                   first so the normal case costs
//                                   one File.exists() call
//   2. plugins/ itself            - everything dropped in loose
//   3. every immediate subfolder  - any other name the folder was
//      of plugins/                  given, or a nested unzip
//
// RoiLabeler.groovy is the sentinel: a directory qualifies only if
// EVERY required file is present, so a stray partial copy can never
// win the search over a complete install.
// -------------------------------------------------------------
String pluginsPath = IJ.getDirectory("plugins")
if (pluginsPath == null || pluginsPath.trim().isEmpty()) {
    // Fiji normally always answers this, but a non-standard or
    // portable install can leave plugins.dir unset - in which case
    // failing with a clear reason beats an NPE on new File(null).
    JOptionPane.showMessageDialog(null,
        "ImageJ did not report a plugins directory, so Foci Listeners\n" +
        "cannot locate its modules.\n\n" +
        "Check Edit > Options > Misc, or run this from a standard Fiji install.",
        "Foci Listeners - install problem",
        JOptionPane.ERROR_MESSAGE)
    return
}
File pluginsDir = new File(pluginsPath)
List<File> searched = []
List<File> candidates = []
candidates << new File(pluginsDir, "Foci Workflow")
candidates << pluginsDir
pluginsDir.listFiles()?.sort { it.name }?.each { File f ->
    if (f.isDirectory()) candidates << f
}

File moduleDir = candidates.find { File d ->
    if (d == null || !d.isDirectory()) return false
    searched << d
    return requiredFiles.every { new File(d, it).exists() }
}

if (moduleDir == null) {
    // Report against the best candidate rather than an abstract path:
    // whichever searched folder held the MOST of the required files is
    // almost certainly the intended install, so its specific gaps are
    // the useful thing to print.
    File best = searched.max { File d -> requiredFiles.count { new File(d, it).exists() } }
    String detail
    if (best == null) {
        detail = "No folders were searched under:\n" + pluginsDir.getAbsolutePath()
    } else {
        def missing = requiredFiles.findAll { !new File(best, it).exists() }
        detail = "Closest match:\n" + best.getAbsolutePath() +
                 "\n\nMissing from it:\n" + missing.join("\n")
    }
    JOptionPane.showMessageDialog(null,
        "Foci Listeners could not find its modules.\n\n" +
        "All files ship as ONE folder - this script and its modules must sit\n" +
        "side by side in the same directory, inside the plugins folder.\n\n" +
        detail,
        "Foci Listeners - install problem",
        JOptionPane.ERROR_MESSAGE)
    return
}

def MODULE_DIR = moduleDir.getAbsolutePath()

def loader = new GroovyClassLoader(this.class.classLoader)
def CalibrationIO      = loader.parseClass(new File(MODULE_DIR, "CalibrationIO.groovy"))
def ScaleMonitor        = loader.parseClass(new File(MODULE_DIR, "ScaleMonitor.groovy"))
def RoiLabeler          = loader.parseClass(new File(MODULE_DIR, "RoiLabeler.groovy"))
def RoiAssigner         = loader.parseClass(new File(MODULE_DIR, "RoiAssigner.groovy"))
def RoiSelectionGrouper = loader.parseClass(new File(MODULE_DIR, "RoiSelectionGrouper.groovy"))
def MeasureAutoRename   = loader.parseClass(new File(MODULE_DIR, "MeasureAutoRename.groovy"))
def RoiExportDivider    = loader.parseClass(new File(MODULE_DIR, "RoiExportDivider.groovy"))
def MeasurementGrouper  = loader.parseClass(new File(MODULE_DIR, "MeasurementGrouper.groovy"))

// ThresholdSync - automatic batch highlighting, kept on its OWN cached
// classloader (a system property, not a local variable) rather than
// the plain `loader` above: running this file twice in the same Fiji
// session would otherwise spin up a SECOND ThresholdSync class with
// its own statics and its own duplicate ImageListener, silently
// double-processing every image. Caching the loader means a second run
// attaches to the same running engine instead of forking. Soft
// dependency: if the file's missing, "Batch Threshold" below still
// opens folders, it just can't highlight them.
File thresholdSyncFile = new File(MODULE_DIR, "ThresholdSync.groovy")
boolean adjustThresholdAvailable = thresholdSyncFile.exists()
def ThresholdSync = null
if (adjustThresholdAvailable) {
    def props = System.getProperties()
    def LOADER_KEY = "fociworkflow.thresholdLoader"
    def tsLoader = props.get(LOADER_KEY)
    if (tsLoader == null) {
        tsLoader = new GroovyClassLoader(this.class.classLoader)
        props.put(LOADER_KEY, tsLoader)
    }
    ThresholdSync = tsLoader.parseClass(thresholdSyncFile)
    ThresholdSync.start() // idempotent - safe even if this file (or its engine) is already running
}

// -------------------------------------------------------------
// Three passive tables. No action buttons feed into them - they only
// ever get read from live ImageJ state by the listeners/pollers
// below.
// -------------------------------------------------------------
def imageColumns = ["Image", "Scale (px/unit)", "Timepoint Tag"] as Object[]
def imageModel = new DefaultTableModel(imageColumns, 0) {
    boolean isCellEditable(int row, int col) { return false }
}
JTable imageTable = new JTable(imageModel)

def roiColumns = [
    "ROI #", "Obtained ROI Label", "Source Image", "Timepoint Tag",
    "Scale (px/unit)", "Scale Linked?"
] as Object[]
def roiModel = new DefaultTableModel(roiColumns, 0) {
    boolean isCellEditable(int row, int col) { return false }
}
JTable roiTable = new JTable(roiModel)

def selColumns = ["_xhrs Tag", "Parent Image", "Linked?", "Scale (px/unit)", "# ROIs Selected"] as Object[]
def selModel = new DefaultTableModel(selColumns, 0) {
    boolean isCellEditable(int row, int col) { return false }
}
JTable selTable = new JTable(selModel)

JTextArea logArea = new JTextArea(6, 70)
logArea.setEditable(false)
def log = { String msg ->
    logArea.append(msg + "\n")
    logArea.setCaretPosition(logArea.document.length)
}

String INTERNAL_PREFIXES_NOTE = "__TSM_SOURCE__|__TSM_ADJ__|TSM_|work_copy|final_mask|AND_temp"
def isInternalTitle = { String title ->
    title.startsWith("__TSM_SOURCE__") || title.startsWith("__TSM_ADJ__") ||
        title.startsWith("TSM_") || title == "work_copy" || title == "final_mask" || title == "AND_temp"
}

// Small, non-blocking "bubble" - shows briefly near the top of the
// screen and disposes itself, rather than a modal dialog the user has
// to click through. Used to confirm the peripheral silently took over
// a native click, without interrupting whatever the user is doing.
def showBubble = { String message ->
    JWindow bubble = new JWindow()
    bubble.setAlwaysOnTop(true)
    JLabel label = new JLabel("  " + message + "  ")
    label.setOpaque(true)
    label.setBackground(new Color(255, 255, 210))
    label.setBorder(BorderFactory.createLineBorder(new Color(120, 120, 90)))
    label.setFont(label.getFont().deriveFont(Font.BOLD, 13f))
    bubble.getContentPane().add(label)
    bubble.pack()
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize()
    bubble.setLocation(((screen.width - bubble.getWidth()) / 2) as int, 60)
    bubble.setVisible(true)
    Timer dismissTimer = new Timer(1400, { e -> bubble.dispose() } as ActionListener)
    dismissTimer.setRepeats(false)
    dismissTimer.start()
}

// Forward-declared here, assigned once the grouping/measure machinery
// it depends on (grouper, runGroupedMeasure) exists further down - the
// same "declare first, assign separately" reasoning as refreshRois
// just below: this needs to be a real, already-existing variable at
// the point refreshRois's body references it, even though it isn't
// actually assigned its real closure until later in the script.
def onNativeMeasureClicked = null

// Same forward-declare-then-assign reasoning as onNativeMeasureClicked
// above: refreshRois references this when it wires interception, but
// the export machinery it closes over doesn't exist until further
// down.
def onNativeExportClicked = null

// Pixel width/height alone (what these tables used to show) means you
// have to do the "1 / pixelWidth" math yourself to know how many
// pixels actually make up one unit - the number that matters when
// judging whether a measurement makes sense. This computes that
// directly, formatted with the actual unit name so nothing is lost by
// dropping the separate Unit column. Accepts pixelWidth as a number OR
// "" (the unlinked/no-calibration case elsewhere in this file), so one
// helper covers every call site below.
def formatScale = { pixelWidth, String unit ->
    if (pixelWidth == null || pixelWidth == "") return "n/a"
    double pw
    try { pw = (pixelWidth as double) } catch (Throwable ignored) { return "n/a" }
    if (pw <= 0) return "n/a"
    return String.format("%.3f px/%s", 1.0 / pw, (unit ?: "pixel"))
}

def refreshImages = {
    imageModel.setRowCount(0)
    int[] ids = WindowManager.getIDList()
    if (ids != null) {
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id)
            if (imp == null) continue
            String title = imp.getTitle()
            if (isInternalTitle(title)) continue
            def cal = CalibrationIO.getLiveCalibration(imp)
            String tag = ScaleMonitor.extractTag(title) ?: ""
            imageModel.addRow([title, formatScale(cal[0], cal[2]), tag] as Object[])
        }
    }
}

// Forward-declared, then assigned in a SEPARATE statement below - not
// "def refreshRois = { ... refreshRois() ... }" in one line. This
// closure calls itself (see the MeasureAutoRename callback inside it),
// and a closure that self-references from within its OWN initializing
// assignment statement is a real Groovy gotcha: at the moment that
// literal is being constructed, the variable it's being assigned to
// doesn't yet have a resolvable captured value, so the compiler can't
// bind the inner self-call as "invoke this local variable" - it falls
// back to treating it as a call to a global script method instead
// (Fiji's JSR223 Groovy engine surfaces this as
// GroovyScriptEngineImpl.callGlobal), which doesn't exist, throwing
// MissingMethodException. Declaring the variable first (as null) and
// assigning the closure in a second, separate statement - the same
// pattern already used for `grouper` below - means refreshRois is
// already a real, resolvable local variable by the time the closure
// literal captures its enclosing scope, so the self-call resolves
// normally.
// Which RoiManager instance has already had its Measure and
// Save/Export handlers intercepted - see refreshRois below.
def interceptedManager = null

def refreshRois = null
refreshRois = {
    roiModel.setRowCount(0)
    RoiManager rm = RoiManager.getInstance()

    // Silence (not attach alongside) native Measure every time this
    // refreshes - idempotent per live button instance, and this way
    // it's ready even if ROI Manager was closed and reopened as a
    // brand-new window since the last check. onNativeMeasureClicked
    // is only a real closure once it's assigned further down (see its
    // forward declaration above); by the time this actually executes
    // (via refreshAll() at the bottom of the script, or any later
    // refresh), it always is.
    // Interception is wired ONCE PER RoiManager INSTANCE, not on every
    // refresh. Both calls are idempotent, so repeating them was
    // harmless in principle - but RoiExportDivider.intercept reflects
    // over RoiManager's declared fields and walks its whole component
    // tree, and refreshRois runs on image opens, closes, calibration
    // changes and every auto-tag. Doing reflective work at that
    // cadence is churn with no purpose. Comparing instance identity
    // covers the only case that actually needs re-wiring: ROI Manager
    // being closed and reopened as a brand-new window.
    if (rm != null && !rm.is(interceptedManager)) {
        MeasureAutoRename.intercept(rm, onNativeMeasureClicked)
        // Returns 0 when this FIJI build keeps its Save somewhere the
        // reflective lookup can't reach - not an error. The "Export
        // ROIs (Divided)" button runs the identical code path.
        RoiExportDivider.intercept(rm, onNativeExportClicked)
        interceptedManager = rm
    }

    if (rm == null || rm.getCount() == 0) return
    for (int i = 0; i < rm.getCount(); i++) {
        def roi = rm.getRoi(i)
        String name = roi.getName() ?: ("ROI-" + (i + 1))
        String sourceTitle = RoiLabeler.getLinkedImageTitle(name)
        ImagePlus sourceImp = null
        String tag = RoiLabeler.getLinkedTag(name)
        if (sourceTitle != null) sourceImp = WindowManager.getImage(sourceTitle)
        if (sourceImp == null && tag != null) {
            sourceImp = RoiLabeler.findOpenImageByTag(tag)
            if (sourceImp != null) sourceTitle = sourceImp.getTitle()
        }
        if (sourceImp != null) {
            def cal = CalibrationIO.getLiveCalibration(sourceImp)
            roiModel.addRow([
                i + 1, RoiLabeler.cleanObtainedLabel(name), sourceTitle,
                tag ?: ScaleMonitor.extractTag(sourceTitle) ?: "",
                formatScale(cal[0], cal[2]), "YES"
            ] as Object[])
        } else {
            roiModel.addRow([
                i + 1, RoiLabeler.cleanObtainedLabel(name), sourceTitle ?: "",
                tag ?: "", "n/a", "NO"
            ] as Object[])
        }
    }
}

def refreshAll = {
    refreshImages()
    refreshRois()
}

// -------------------------------------------------------------
// Live selection grouping (see RoiSelectionGrouper.groovy for why
// this polls rather than listens).
// -------------------------------------------------------------
def grouper = RoiSelectionGrouper.newInstance(ScaleMonitor, RoiLabeler, CalibrationIO)

// Cached synchronously (NOT inside the invokeLater below) so that
// calling grouper.regroup() and then immediately reading lastGroups -
// as "Measure All" does further down - always sees the fresh result,
// rather than whatever invokeLater's queued Swing update happened to
// have applied by that point.
List lastGroups = []

grouper.onGrouped = { List groups ->
    lastGroups = groups
    SwingUtilities.invokeLater({
        selModel.setRowCount(0)
        groups.each { g ->
            selModel.addRow([
                g.tag, g.image ?: "", g.linked ? "YES" : "NO",
                formatScale(g.pixelW, g.unit), g.rois.size()
            ] as Object[])
        }
    } as Runnable)
}
grouper.attach()

// -------------------------------------------------------------
// Measuring with the correct per-group scale, safely.
//
// Native ROI Manager's Measure/multiMeasure always resolves ONE
// ImagePlus (WindowManager.getCurrentImage(), confirmed from
// RoiManager.java's own source - showAll(), split(), and
// multiMeasure() all do exactly this) and applies its Calibration to
// EVERY selected ROI, regardless of which image that ROI actually
// came from. If a selection spans more than one _xhrs-tagged image,
// a single native Measure click silently applies the wrong scale to
// every ROI that isn't from the currently-active one.
//
// The fix uses RoiManager's OWN public
// runCommand(ImagePlus imp, String cmd) overload - a second, real
// entry point for "Measure", built specifically to let external code
// say which image to use instead of relying on whichever one happens
// to be active. It never touches window focus at all.
//
// Both buttons below share this exact same per-group loop; "Selected"
// seeds it from whatever's currently selected, "All" seeds it by
// briefly selecting every ROI in the manager first. Two entry points,
// one implementation - never two copies of this logic to drift apart.
// -------------------------------------------------------------
// -------------------------------------------------------------
// Results row -> ROI name, recorded AS the measurement runs.
//
// This is what MeasurementGrouper groups by, and it exists because
// the obvious source - the Results "Label" column - is written only
// when Set Measurements > Display label is ticked. With it off there
// is no Label at all, so every row parsed as nothing and the grouped
// table came out as NaN in every column.
//
// The label was never the right source anyway. This loop KNOWS which
// ROIs it is measuring and in what order: rm.runCommand(imp,
// "Measure") appends one row per selected ROI in ascending index
// order, so the row that lands at (size-before + k) belongs to the
// k-th index of the selection. Recording that directly needs no
// preference to be set and no string to be parsed back out of a
// column ImageJ formatted for display.
//
// Keyed by ABSOLUTE row index, so it stays valid as later measures
// append to the same table. It is cleared when the table is cleared -
// see the staleness check at the top of the loop.
// -------------------------------------------------------------
Map measuredRowRois = [:]

def runGroupedMeasure = { RoiManager rm, String label ->
    if (lastGroups.isEmpty()) {
        log(label + ": nothing to measure.")
        return
    }

    // If Results was cleared (or shrank) since the last run, every
    // recorded row index now points at the wrong row - or at no row.
    // Dropping the map is correct; keeping it would mislabel real
    // measurements, which is worse than not labelling them at all.
    ResultsTable rtBefore = ResultsTable.getResultsTable()
    int existingRows = (rtBefore != null) ? rtBefore.size() : 0
    if (!measuredRowRois.isEmpty()) {
        int highest = measuredRowRois.keySet().max() as int
        if (existingRows <= highest) measuredRowRois.clear()
    }

    int measuredGroups = 0
    int skippedRois = 0
    lastGroups.each { g ->
        if (!g.linked) {
            skippedRois += g.rois.size()
            log("Skipped " + g.rois.size() + " ROI(s) tagged " + g.tag + " - no open image is linked to that tag.")
            return
        }
        ImagePlus imp = WindowManager.getImage(g.image)
        if (imp == null) {
            skippedRois += g.rois.size()
            log("Skipped " + g.rois.size() + " ROI(s) - linked image \"" + g.image + "\" is no longer open.")
            return
        }
        // Names captured BEFORE measuring: an ROI renamed between now
        // and the grouping call would otherwise be recorded under a
        // name that no longer matches the row it produced.
        List<String> namesInOrder = (g.rois as List).collect { int idx ->
            def r = rm.getRoi(idx)
            (r?.getName()) ?: ("ROI-" + (idx + 1))
        }

        ResultsTable rtPre = ResultsTable.getResultsTable()
        int rowsBefore = (rtPre != null) ? rtPre.size() : 0

        rm.setSelectedIndexes(g.rois as int[])
        rm.runCommand(imp, "Measure")

        ResultsTable rtPost = ResultsTable.getResultsTable()
        int rowsAfter = (rtPost != null) ? rtPost.size() : rowsBefore
        int appended = rowsAfter - rowsBefore
        // Mapped against what was ACTUALLY appended, not against how
        // many ROIs were selected. If Measure produced a different
        // number of rows than expected, the surplus names are dropped
        // rather than assigned to rows they may not belong to.
        int mappable = Math.min(appended, namesInOrder.size())
        for (int k = 0; k < mappable; k++) {
            measuredRowRois[rowsBefore + k] = namesInOrder[k]
        }
        if (appended != namesInOrder.size()) {
            log("Note: " + g.rois.size() + " ROI(s) tagged " + g.tag + " produced " + appended +
                " Results row(s) - " + (namesInOrder.size() - mappable) + " row(s) left unnamed for grouping.")
        }

        measuredGroups++
        log("Measured " + g.rois.size() + " ROI(s) tagged " + g.tag + " against \"" + g.image + "\".")
    }
    log(label + " complete - " + measuredGroups + " group(s) measured" +
        (skippedRois > 0 ? ", " + skippedRois + " ROI(s) skipped." : "."))
}

// "Measure Selected (Linked Scale)" - acts on whatever is currently
// selected in the native ROI list, exactly as shown in the Selection
// Groups tab.
def measureSelectedLinkedAction = {
    RoiManager rm = RoiManager.getInstance()
    if (rm == null) {
        log("ROI Manager is not open.")
        return
    }
    if (lastGroups.isEmpty()) {
        int[] currentlySelected = rm.getSelectedIndexes()
        if (currentlySelected == null || currentlySelected.length == 0) {
            log("No ROIs selected in ROI Manager - select some first, or use " +
                "\"Measure All (Linked Scale)\" to measure everything at once.")
        } else {
            log("Selection not yet grouped - try again in a moment.")
        }
        return
    }
    runGroupedMeasure(rm, "Measure Selected (Linked Scale)")
}

// "Measure All (Linked Scale)" - the two-step wrapper: (1) briefly
// selects every ROI in the manager so the SAME live grouping logic
// used for the selection tab groups the WHOLE table by tag, then (2)
// runs the identical per-group runCommand(imp, "Measure") loop above.
// Each group still gets measured against its own actual parent image,
// resolved fresh at the moment this runs - never a stale or assumed
// scale.
def measureAllLinkedAction = {
    RoiManager rm = RoiManager.getInstance()
    if (rm == null || rm.getCount() == 0) {
        log("ROI Manager is empty - nothing to measure.")
        return
    }
    int[] all = (0..<rm.getCount()) as int[]
    rm.setSelectedIndexes(all)
    grouper.regroup()
    runGroupedMeasure(rm, "Measure All (Linked Scale)")
}

// The peripheral that silently answers native ROI Manager's own
// "Measure" click (see MeasureAutoRename.intercept()/restore()) -
// the host's own listener is detached for as long as this stays
// attached, so THIS runs instead, not in addition. Mirrors whatever
// the two explicit buttons above already do (empty selection ->
// treat as "measure everything"; otherwise measure just what's
// selected), so clicking the familiar native button and clicking one
// of this window's own buttons both end up doing the same correct,
// per-image-scaled thing.
onNativeMeasureClicked = {
    RoiManager rm = RoiManager.getInstance()
    if (rm == null) return
    showBubble("Image ROI Scaling")

    int[] selected = rm.getSelectedIndexes()
    if (selected == null || selected.length == 0) {
        int[] all = (0..<rm.getCount()) as int[]
        rm.setSelectedIndexes(all)
    }
    grouper.regroup()
    runGroupedMeasure(rm, "Measure (native button, linked scale)")

    // Where Rename_Labels_In_Table.ijm used to run. Grouping
    // supersedes it rather than sitting next to it: the macro's
    // colon-stripping cleanup destroyed the image prefix that
    // identifies which stack a row came from, which is the field this
    // groups by. The clean point name still appears, in its own ROI
    // column, with the full Label intact beside it.
    //
    // This runs on EVERY measure, not on a button, because the
    // grouping is not an optional report - it is the only ordering in
    // which these rows can be read as a time series per point, and
    // leaving it to a separate click means the ungrouped table is
    // what gets copied out whenever the click is forgotten.
    try {
        def summary = MeasurementGrouper.applyToResultsWindow(measuredRowRois)
        if (summary.ok) {
            log("Results grouped: " + summary.rows + " row(s), " + summary.points +
                " point series (Point -> Timepoint).")
        } else {
            log("Grouping skipped - " + summary.reason)
        }
    } catch (Throwable t) {
        // A grouping failure must never cost the measurement itself -
        // the raw Results table is already written and correct at this
        // point, so this is reported and stepped over, not rethrown.
        log("Measurement grouping failed (raw Results are unaffected): " + t.getMessage())
    }

    // "Opens the measurement table" - Measure already shows/raises
    // Results as part of its own normal native behavior, but this is
    // a cheap, harmless guarantee rather than an assumption.
    try { IJ.selectWindow("Results") } catch (Exception ignored) { }

    refreshRois.call()
}

// -------------------------------------------------------------
// The peripheral that answers ROI Manager's own Save/Export click
// (see RoiExportDivider.intercept()/restore()). Mirrors the Measure
// interception exactly: the host's listener is detached for as long
// as this is attached, so THIS runs instead of the native save, not
// alongside it - and the prompt's "skip dividing" option calls the
// captured original listener, producing the identical native dialog
// and file the button would have produced untouched.
// -------------------------------------------------------------
onNativeExportClicked = {
    RoiManager rm = RoiManager.getInstance()
    if (rm == null) return
    showBubble("ROI Export Divider")
    RoiExportDivider.promptAndExport(rm, RoiLabeler,
        { RoiExportDivider.runNativeSave(rm) }, log)
}

def exportDividedAction = {
    RoiManager rm = RoiManager.getInstance()
    if (rm == null) {
        log("ROI Manager is not open - nothing to export.")
        return
    }
    RoiExportDivider.promptAndExport(rm, RoiLabeler,
        { RoiExportDivider.runNativeSave(rm) }, log)
}

def groupMeasurementsAction = {
    try {
        MeasurementGrouper.promptAndExport(measuredRowRois, log)
    } catch (Throwable t) {
        log("Group Measurements failed: " + t.getMessage())
    }
}

// -------------------------------------------------------------
// Live ROI auto-tagging (see RoiAssigner.groovy for the full
// history of why this polls instead of listening for an event).
// -------------------------------------------------------------
def assigner = RoiAssigner.newInstance(ScaleMonitor, RoiLabeler)
assigner.onAssigned = { int idx, String oldName, String newName, String tag ->
    SwingUtilities.invokeLater({
        log("Auto-tagged ROI #" + (idx + 1) + ": \"" + oldName + "\" -> \"" + newName + "\" (" + tag + ").")
        refreshRois()
    } as Runnable)
}
assigner.onSkipped = { int idx, String oldName, String reason ->
    SwingUtilities.invokeLater({
        log("Auto-tag skipped for ROI #" + (idx + 1) + " (\"" + oldName + "\") - " + reason)
        refreshRois()
    } as Runnable)
}
Timer roiAddPollTimer = new Timer(150, { e -> assigner.checkForNewRoi() } as ActionListener)
roiAddPollTimer.start()

// -------------------------------------------------------------
// Calibration change detector. No native "calibration changed" event
// exists in FIJI (confirmed from ScaleDialog/ImagePlus source) - this
// polls and diffs instead of guessing at one.
// -------------------------------------------------------------
Map calibrationCache = [:]
Timer calibrationPollTimer = new Timer(800, null)
calibrationPollTimer.addActionListener({ e ->
    boolean changed = false
    int[] ids = WindowManager.getIDList()
    if (ids != null) {
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id)
            if (imp == null) continue
            def cal = imp.getCalibration()
            String sig = cal.pixelWidth + "|" + cal.pixelHeight + "|" + cal.getUnit()
            String title = imp.getTitle()
            if (calibrationCache[title] != sig) {
                calibrationCache[title] = sig
                changed = true
            }
        }
    }
    if (changed) { refreshImages(); refreshRois() }
} as ActionListener)
calibrationPollTimer.start()

// -------------------------------------------------------------
// Native image lifecycle + native command notifications.
//
// Refinement: ij.plugin.filter.ScaleDialog.run() (native Set Scale)
// calls imp.repaintWindow(), never imp.updateAndDraw() - confirmed
// from ImageJ's own source - so it still fires no ImageListener event
// at all, no matter how well understood updateAndDraw() itself is.
// The poll above is still what actually catches the change. What
// CommandListener adds here is knowing the INSTANT "Set Scale..." was
// invoked, which lets the poll speed itself up temporarily right when
// a calibration change is actually likely, instead of always polling
// at the same flat interval - cutting perceived lag for this one
// known case without changing the general-purpose fallback.
// -------------------------------------------------------------
ImageListener imageListener = new ImageListener() {
    void imageOpened(ImagePlus imp) { SwingUtilities.invokeLater({ refreshAll() } as Runnable) }
    void imageClosed(ImagePlus imp) { SwingUtilities.invokeLater({ refreshAll() } as Runnable) }
    void imageUpdated(ImagePlus imp) { SwingUtilities.invokeLater({ refreshImages() } as Runnable) }
}
ImagePlus.addImageListener(imageListener)

final int NORMAL_POLL_MS = 800
final int FAST_POLL_MS = 150
final int FAST_POLL_DURATION_MS = 6000

// One-shot: restores the normal poll speed a few seconds after Set
// Scale was invoked - long enough for a typical dialog interaction,
// short enough not to burn cycles polling fast indefinitely.
Timer restorePollSpeedTimer = new Timer(FAST_POLL_DURATION_MS, null)
restorePollSpeedTimer.setRepeats(false)
restorePollSpeedTimer.addActionListener({ e -> calibrationPollTimer.setDelay(NORMAL_POLL_MS) } as ActionListener)

CommandListener setScaleCommandListener = { String command ->
    if (command == "Set Scale...") {
        ImagePlus cur = WindowManager.getCurrentImage()
        log("Native Set Scale invoked" + (cur != null ? " on \"" + cur.getTitle() + "\"." : "."))
        calibrationPollTimer.setDelay(FAST_POLL_MS)
        restorePollSpeedTimer.restart()
    }
    return command
} as CommandListener
Executer.addCommandListener(setScaleCommandListener)

// -------------------------------------------------------------
// The window. Three tabs, a log, and a single row of buttons in
// workflow order. Stop Listeners is the only one that isn't a
// workflow action - it's a lifecycle control, so it sits at the end.
// -------------------------------------------------------------
JTabbedPane tabs = new JTabbedPane()
tabs.addTab("Images / Set Scale", new JScrollPane(imageTable))
tabs.addTab("ROIs / Linked Scale", new JScrollPane(roiTable))
tabs.addTab("Selection Groups (live)", new JScrollPane(selTable))

// Declared before the button row is assembled so Batch Threshold can
// be added to it FIRST - see batchThresholdAction further below for
// what it does. The row reads left-to-right as the workflow order:
// Batch Threshold -> Measure -> Export ROIs -> Group Measurements ->
// Stop Listeners.
JButton measureSelectedBtn = new JButton("Measure Selected (Linked Scale)")
measureSelectedBtn.addActionListener({ e -> measureSelectedLinkedAction() } as ActionListener)

JButton measureAllBtn = new JButton("Measure All (Linked Scale)")
measureAllBtn.addActionListener({ e -> measureAllLinkedAction() } as ActionListener)

// -------------------------------------------------------------
// Batch Threshold - opens a folder tree recursively, converting each
// image to RGB Color, entirely in Groovy: no separate .ijm file, no
// runMacroFile, no cross-language bridging of any kind. Because this
// runs inside THIS already-open window, ThresholdSync is already
// loaded and the engine is already running before a single file opens
// - so every image comes up highlighted immediately, AND RoiAssigner/
// the tables above are already live and watching the moment a
// Wand-selected blob becomes an ROI. No ordering question at all.
// -------------------------------------------------------------
def openFolderRecursively = null
openFolderRecursively = { File dir ->
    int count = 0
    File[] entries = dir.listFiles()
    if (entries == null) return 0
    entries.sort { it.name }
    entries.each { File f ->
        if (f.isDirectory()) {
            count += openFolderRecursively(f)
        } else if (f.name.toLowerCase() =~ /\.(jpg|jpeg|png|tif|tiff|bmp)$/) {
            IJ.open(f.getAbsolutePath())
            ImagePlus opened = WindowManager.getCurrentImage()
            if (opened != null) IJ.run(opened, "RGB Color", "")
            count++
        }
    }
    count
}

def batchThresholdAction = {
    if (!adjustThresholdAvailable) {
        log("ThresholdSync.groovy not found - can't highlight, but folder opening would still work.")
    } else {
        ThresholdSync.start() // idempotent - guarantees the engine is live BEFORE anything below opens
    }
    String dir = IJ.getDirectory("Choose the top-level batch folder")
    if (dir == null || dir == "") return
    int opened = openFolderRecursively(new File(dir))
    log("Batch Threshold: opened " + opened + " image(s)" +
        (adjustThresholdAvailable ? ", highlighted live." : " (not highlighted - engine unavailable)."))
}

JButton batchThresholdBtn = new JButton("Batch Threshold")
batchThresholdBtn.addActionListener({ e -> batchThresholdAction() } as ActionListener)
if (!adjustThresholdAvailable) {
    batchThresholdBtn.setToolTipText("ThresholdSync.groovy was not found in " + MODULE_DIR +
        " - folder opening still works, but nothing will be highlighted.")
}

JButton stopBtn = new JButton("Stop Listeners")
JLabel statusLabel = new JLabel("Listeners running.")
statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 12f))

JButton exportDividedBtn = new JButton("Export ROIs (Divided)")
exportDividedBtn.addActionListener({ e -> exportDividedAction() } as ActionListener)
exportDividedBtn.setToolTipText("Splits the one shared ROI list into per-image coordinate CSVs, " +
    "keyed on the _xhrs tag and the shared stack name. Same code path as intercepting " +
    "ROI Manager's own Save, so it works even where that interception doesn't.")

JButton groupMeasurementsBtn = new JButton("Group Measurements")
groupMeasurementsBtn.addActionListener({ e -> groupMeasurementsAction() } as ActionListener)
groupMeasurementsBtn.setToolTipText("Regroups the Results table Point -> Timepoint and " +
    "optionally writes it out. Runs automatically after every Measure; this button is for " +
    "regrouping a table that was edited, reloaded, or measured before this window was open.")

// Batch Threshold FIRST: it is the first action of a session, and the
// only one that has to happen before anything else is worth clicking.
JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT))
top.add(batchThresholdBtn)
top.add(measureSelectedBtn)
top.add(measureAllBtn)
top.add(exportDividedBtn)
top.add(groupMeasurementsBtn)
top.add(stopBtn)
top.add(statusLabel)

JPanel bottom = new JPanel(new BorderLayout())
bottom.add(new JLabel("<html>Exception to \"passive only\" above: native ROI Manager's Measure and Save/Export " +
    "are both silenced while this window is open. <b>Measure</b> (there or here) shows an " +
    "\"Image ROI Scaling\" bubble, measures each linked group against its own image - native Measure " +
    "resolves only ONE active image for an entire selection (confirmed from RoiManager.java's own source) - " +
    "then groups the Results table Point &rarr; Timepoint. <b>Save/Export</b> shows an " +
    "\"ROI Export Divider\" bubble and offers to split the one shared ROI list into per-image coordinate " +
    "CSVs instead of a single undifferentiated .zip; the prompt's \"skip dividing\" option runs the real " +
    "native save. Closing this window / Stop Listeners restores both exactly as they were. Everything else " +
    "(Set Scale, Add) is still 100% native FIJI, untouched.</html>"), BorderLayout.NORTH)
bottom.add(new JScrollPane(logArea), BorderLayout.CENTER)

JFrame frame = new JFrame("Foci Listeners")
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
frame.setLayout(new BorderLayout(5, 5))
frame.add(top, BorderLayout.NORTH)
frame.add(tabs, BorderLayout.CENTER)
frame.add(bottom, BorderLayout.SOUTH)
frame.setSize(1250, 620)
frame.setLocationRelativeTo(null)

def stopAll = {
    roiAddPollTimer.stop()
    ImagePlus.removeImageListener(imageListener)
    Executer.removeCommandListener(setScaleCommandListener)
    calibrationPollTimer.stop()
    restorePollSpeedTimer.stop()
    grouper.detach()
    // The parasite detaches: native Measure's own listener - never
    // destroyed, only silenced - is re-attached here, so the button
    // goes back to stock behavior with no trace of having been
    // intercepted.
    RoiManager rm = RoiManager.getInstance()
    if (rm != null) {
        MeasureAutoRename.restore(rm)
        RoiExportDivider.restore(rm)
    }
}

stopBtn.addActionListener({ e ->
    stopAll()
    statusLabel.setText("Listeners stopped.")
    stopBtn.setEnabled(false)
    log("All listeners detached. Close this window or reopen Foci Listeners to restart them.")
} as ActionListener)

frame.addWindowListener(new WindowAdapter() {
    void windowClosed(WindowEvent e) { stopAll() }
})

refreshAll()
frame.setVisible(true)
log("Listeners attached: RoiAssigner (auto-tag poll, 150ms), RoiSelectionGrouper (poll, 400ms), " +
    "calibration poll (800ms), ImageListener, CommandListener, " +
    "MeasureAutoRename (native Measure), RoiExportDivider (native Save/Export).")
