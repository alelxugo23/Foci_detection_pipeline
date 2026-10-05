import ij.ImagePlus
import ij.WindowManager
import ij.plugin.frame.RoiManager

/**
 * RoiAssigner
 * -----------
 * The missing link between "select an object with Wand" and "the ROI
 * table shows a linked, calibrated, sequentially-numbered datapoint" -
 * without a manual button click in between.
 *
 * checkForNewRoi() is called on a fast poll timer (see
 * Foci_Listeners.groovy) rather than subscribed to any ImageJ event.
 *
 * -------------------------------------------------------------------
 * HISTORY - why this is a poll, not a listener (kept for context):
 *
 * The original version of this class implemented ij.gui.RoiListener
 * and tagged ROIs from roiModified(imp, id) callbacks, filtering for
 * id == RoiListener.CREATED. Two real bugs were found and fixed along
 * the way:
 *   1. RoiListener.ADDED never existed (real constant is CREATED).
 *   2. CREATED also fires when an EXISTING ROI is merely clicked/
 *      selected in the ROI Manager list (RoiManager.restore() calls
 *      imp.setRoi(existingRoi), which also notifies CREATED) - not
 *      just on a genuine new addition. A lastCount-based
 *      "did the manager's count actually grow" check was added to
 *      filter that out.
 *
 * Even after both fixes, tagging was still consistently ONE ACTION
 * BEHIND in practice: an ROI wouldn't get its Point_N label until the
 * NEXT Wand selection/Add happened, not the one that actually created
 * it. The remaining cause is timing, not logic: CREATED does not fire
 * at a point where RoiManager.getCount() is guaranteed to already
 * reflect the just-added ROI, so the "did it grow" check reads a
 * stale count and only catches up on the following event - shifting
 * every tag by one.
 *
 * Rather than chase the exact internal ImageJ call ordering that
 * causes that lag, this class now polls RoiManager.getCount() itself
 * - the actual ground truth - on a short timer, and diffs it, exactly
 * like calibrationPollTimer already does for Set Scale and
 * RoiSelectionGrouper already does for list selection elsewhere in
 * this toolkit (both for the same underlying reason: no trustworthy
 * native event exists for the thing being watched). This also means
 * it now works identically no matter how the ROI was added - mouse
 * click on "Add [t]", the 't' keyboard shortcut, or a macro/script
 * command - since all three end up as a real, visible change to
 * RoiManager.getCount() either way, rather than depending on which
 * Swing/AWT event path each one happens to fire.
 * -------------------------------------------------------------------
 *
 * By the time the ROI table's next refresh runs, the ROI's name
 * already carries "Point_N" + the tag, so RoiLabeler.findOpenImageByTag
 * + CalibrationIO can resolve it to the parent image's live Set Scale
 * calibration without anyone touching "Capture Selected ROIs".
 *
 * "Capture Selected ROIs From Current Image" remains as a manual
 * override/backfill tool - useful for ROIs added before this poll was
 * enabled, or for deliberately re-pointing an ROI at a different
 * image.
 *
 * Nothing here touches Set Scale or Calibration directly. Tagging and
 * calibration-linking stay separate responsibilities:
 *   - RoiAssigner:   ROI name gets "Point_N" + the right _xhrs tag,
 *                     live, the moment it's actually added.
 *   - RoiLabeler +
 *     CalibrationIO:  tag -> open image -> live Calibration lookup,
 *                     read fresh every time the table (or a
 *                     measurement) needs it.
 */
class RoiAssigner {

    def scaleMonitorClass   // ScaleMonitor.groovy, loaded by the GUI
    def roiLabelerClass     // RoiLabeler.groovy, loaded by the GUI

    boolean enabled = true

    /** Closure(int index, String oldName, String newName, String tag) */
    Closure onAssigned

    /** Closure(int index, String oldName, String reason) */
    Closure onSkipped

    // -1 = "no baseline established yet". The very first poll tick
    // just records the current count as the baseline rather than
    // acting on it - there is no way to know, on that first tick,
    // whether the manager's current entries represent genuinely new
    // ROIs or ones that already existed before this poll started.
    private int lastCount = -1

    // Whether ROI Manager was ALREADY open at the moment this class
    // was constructed. This single flag fixes a bug where the first
    // ROI of a session was never tagged:
    //
    // The baseline rule is "ROIs that existed before I started are not
    // mine to tag". Reading the count on the first poll tick expressed
    // that rule wrongly whenever ROI Manager was CLOSED at startup -
    // which is the normal case, since Foci Listeners is opened first
    // and ROI Manager springs into existence on the very first "Add
    // [t]". By that first tick the manager already reports a count of
    // 1, so 1 was recorded as "pre-existing" and the ROI that had just
    // been created was written off as something that was already
    // there. It kept its coordinate name ("2387-2251") forever, and
    // every later ROI tagged correctly, which made it look like a
    // one-off glitch rather than a rule applied consistently to the
    // wrong starting point.
    //
    // Captured at construction, not at first tick: that is the only
    // moment "was it open before we started" is actually knowable.
    private final boolean managerOpenAtStart = (RoiManager.getInstance() != null)

    RoiAssigner(scaleMonitorClass, roiLabelerClass) {
        this.scaleMonitorClass = scaleMonitorClass
        this.roiLabelerClass = roiLabelerClass
    }

    /**
     * Called every poll tick (see Foci_Listeners.groovy's
     * roiAddPollTimer). Tags every ROI added since the last tick, in
     * order - not just the newest one - so a fast burst of adds within
     * a single poll interval still gets each one tagged/numbered
     * individually instead of silently skipping all but the last.
     */
    void checkForNewRoi() {
        if (!enabled) return

        RoiManager rm = RoiManager.getInstance()
        if (rm == null) return
        int count = rm.getCount()

        if (lastCount == -1) {
            // Establish the baseline.
            //
            // Manager open before this class existed -> whatever it
            // holds is pre-existing and not ours to touch.
            //
            // Manager CLOSED at start and now holding exactly one ROI
            // -> it opened because of that single "Add [t]", which
            // happened after we started, so baseline 0 and we fall
            // through to tag it on this same tick. This is the case
            // that used to leave the session's first ROI stuck with
            // its coordinate name.
            //
            // Manager closed at start but now holding SEVERAL ROIs ->
            // they came from somewhere else (a loaded .zip, another
            // macro) and are treated as pre-existing. Baseline 0 here
            // would mass-tag them, and tagging is not a quiet
            // operation: RoiLabeler renames through
            // rm.setSelectedIndexes(), which makes ROI Manager RESTORE
            // that ROI onto the current image - wiping whatever
            // selection is on screen. A backfill sweep firing while
            // someone is mid-polygon, or has just drawn the line they
            // are about to Set Scale from, takes that selection away
            // from under them. Not worth it to rescue ROIs this class
            // never saw created.
            if (managerOpenAtStart) {
                lastCount = count
            } else {
                lastCount = (count == 1) ? 0 : count
            }
        }
        if (count <= lastCount) {
            // The list did not grow (or shrank, e.g. a delete) - nothing
            // to tag this tick.
            lastCount = count
            return
        }

        ImagePlus imp = WindowManager.getCurrentImage()
        int from = lastCount
        lastCount = count
        for (int idx = from; idx < count; idx++) {
            tagOneRoi(rm, imp, idx)
        }
    }

    private void tagOneRoi(RoiManager rm, ImagePlus imp, int idx) {
        def roi = rm.getRoi(idx)
        String oldName = roi.getName() ?: ("ROI-" + (idx + 1))

        // Already carries a Point_N + _xhrs name: leave it exactly as
        // it is. Without this, the baseline-0 path above would re-tag
        // and RENUMBER ROIs that were already correctly named - e.g. a
        // set reloaded from a saved .zip would come back as Point_1,
        // Point_2... in load order, silently destroying the numbering
        // that the measurement grouping depends on.
        if (oldName.toLowerCase().startsWith("point_") &&
            roiLabelerClass.getLinkedTag(oldName) != null) {
            return
        }

        if (imp == null) {
            if (onSkipped) onSkipped(idx, oldName, "no active image was found to link this ROI to.")
            return
        }

        String tag = scaleMonitorClass.extractTag(imp.getTitle())
        if (tag == null) {
            if (onSkipped) {
                onSkipped(idx, oldName,
                    "active image \"" + imp.getTitle() + "\" has no _xhrs tag - " +
                    "add one to the image title, or use \"Capture Selected ROIs\" " +
                    "to assign a tag manually.")
            }
            return
        }

        int linked = roiLabelerClass.linkSelectedIndexToImage(rm, imp, tag, idx)
        if (linked > 0) {
            def newRoi = rm.getRoi(idx)
            if (onAssigned) onAssigned(idx, oldName, newRoi.getName(), tag)
        } else if (onSkipped) {
            onSkipped(idx, oldName, "auto-tag failed for an unknown reason.")
        }
    }
}
