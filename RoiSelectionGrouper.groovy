import ij.plugin.frame.RoiManager
import javax.swing.Timer
import java.awt.event.ActionListener

/**
 * RoiSelectionGrouper
 * --------------------
 * The intermediary that reacts to SELECTION in FIJI's native ROI
 * Manager list - clicking, shift-clicking, ctrl-clicking, or
 * "Select All" - as distinct from RoiAssigner, which reacts to ROIs
 * being CREATED.
 *
 * On every detected selection change it re-reads whatever is
 * CURRENTLY selected directly from ROI Manager (the native list is
 * the single source of truth; nothing here keeps its own copy of ROI
 * state), classifies/separates the selected ROIs into groups by their
 * _xhrs tag, and resolves each group back to its parent image.
 *
 * "Resolves back to its parent image" means reading that image's
 * LIVE ij.measure.Calibration object - the same live, in-memory
 * object CalibrationIO already reads elsewhere in this toolkit.
 *
 * IMPORTANT - corrected after actually reading RoiManager.java on
 * GitHub: an earlier version of this class attached a
 * java.awt.event.ItemListener to whatever RoiManager.getList()
 * returned, on the assumption that ROI Manager's selectable list was
 * a java.awt.List (true in very old ImageJ versions). The current
 * source shows RoiManager's real, internal list is a Swing JList,
 * which RoiManager listens to itself via
 * javax.swing.event.ListSelectionListener - a completely different
 * event type that ItemListener never receives. getList() itself still
 * exists, but only for legacy source compatibility - it's marked
 * Deprecated/Obsolete in the current API docs and isn't wired to real
 * selection events anymore. That meant this class's whole live-update
 * mechanism never actually fired.
 *
 * Since RoiManager doesn't expose its internal JList (or any public
 * "selection changed" event) for external code to hook directly, this
 * now polls ROI Manager's own public, always-current
 * getSelectedIndexes() on a short timer instead - the same
 * "can't subscribe to it, so read it live and diff" approach used for
 * Calibration elsewhere in this toolkit, and for the same reason:
 * it's correct regardless of which internal widget FIJI happens to be
 * using this version, because it never depends on that widget at all.
 */
class RoiSelectionGrouper {

    def scaleMonitorClass
    def roiLabelerClass
    def calibrationIOClass

    /**
     * Closure(List<Map> groups), called whenever the polled selection
     * differs from what was last reported (including "selection
     * cleared", with an empty list).
     * Each group is a Map:
     *   tag:     String  ("(untagged)" if the ROI carries no _xhrs tag)
     *   image:   String  or null if unresolved
     *   linked:  boolean
     *   pixelW, pixelH, unit: from the parent image's live Calibration
     *   rois:    List<Integer> - ROI Manager indices in this group
     */
    Closure onGrouped

    boolean enabled = true

    private Timer pollTimer
    private String lastSelectionSignature = null

    RoiSelectionGrouper(scaleMonitorClass, roiLabelerClass, calibrationIOClass) {
        this.scaleMonitorClass = scaleMonitorClass
        this.roiLabelerClass = roiLabelerClass
        this.calibrationIOClass = calibrationIOClass
    }

    /**
     * Starts (or restarts) the poll. Safe to call repeatedly - unlike
     * the old ItemListener approach, there is no per-instance widget
     * to re-target, so this just makes sure exactly one poll timer is
     * running.
     */
    void attach() {
        if (pollTimer != null) return
        pollTimer = new Timer(400, { e -> if (enabled) checkForChange() } as ActionListener)
        pollTimer.start()
    }

    void detach() {
        if (pollTimer != null) {
            pollTimer.stop()
            pollTimer = null
        }
        lastSelectionSignature = null
    }

    /** Polls RoiManager's selection and only regroups if it actually changed. */
    private void checkForChange() {
        RoiManager rm = RoiManager.getInstance()
        int[] selected = (rm != null) ? rm.getSelectedIndexes() : null
        String sig = (selected == null || selected.length == 0) ? "" : selected.join(",")
        if (sig == lastSelectionSignature) return
        lastSelectionSignature = sig
        regroup()
    }

    /** Re-classify whatever is selected right now and report it. */
    void regroup() {
        RoiManager current = RoiManager.getInstance()
        if (current == null) {
            if (onGrouped) onGrouped([])
            return
        }

        int[] selected = current.getSelectedIndexes()
        if (selected == null || selected.length == 0) {
            if (onGrouped) onGrouped([])
            return
        }

        Map<String, List<Integer>> byTag = new LinkedHashMap<>()
        selected.each { idx ->
            def roi = current.getRoi(idx)
            String name = roi.getName() ?: ("ROI-" + (idx + 1))
            String tag = roiLabelerClass.getLinkedTag(name) ?: scaleMonitorClass.extractTag(name)
            String key = tag ?: "(untagged)"
            if (!byTag.containsKey(key)) byTag[key] = []
            byTag[key] << idx
        }

        List groups = []
        byTag.each { tag, indices ->
            def imp = (tag != "(untagged)") ? roiLabelerClass.findOpenImageByTag(tag) : null
            if (imp != null) {
                def cal = calibrationIOClass.getLiveCalibration(imp)
                groups << [
                    tag: tag, image: imp.getTitle(), linked: true,
                    pixelW: cal[0], pixelH: cal[1], unit: cal[2],
                    rois: indices
                ]
            } else {
                groups << [
                    tag: tag, image: null, linked: false,
                    pixelW: "", pixelH: "", unit: "",
                    rois: indices
                ]
            }
        }

        if (onGrouped) onGrouped(groups)
    }
}
