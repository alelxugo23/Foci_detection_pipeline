import java.awt.Button
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionListener
import java.io.File
import ij.IJ
import ij.plugin.frame.RoiManager

/**
 * MeasureAutoRename
 * -----------------
 * Two distinct capabilities against ROI Manager's native "Measure"
 * button, both built on the same component lookup:
 *
 *   attach()    - APPENDS a second ActionListener alongside
 *                 RoiManager's own. Both fire, in the order added;
 *                 RoiManager's real measurement always runs FIRST,
 *                 this one runs right after and finishes the job
 *                 (currently: the label-rename macro). This is the
 *                 only option when you want the native behavior to
 *                 still happen exactly as before, with something
 *                 extra tacked on afterward.
 *
 *   intercept() / restore() - SILENCES RoiManager's own click handler
 *                 and runs a peripheral closure in its place instead
 *                 - a parasite, not a replacement. The original
 *                 ActionListener object is never destroyed, only
 *                 detached: removeActionListener() just drops its
 *                 reference from the button's listener list, so the
 *                 real RoiManager instance sits inert in memory,
 *                 unable to hear the click, for as long as this stays
 *                 attached. This is NOT the same as attach() above: a
 *                 second, later-added listener can never silence or
 *                 outrun an earlier one - Swing/AWT fires every
 *                 registered ActionListener on a component in the
 *                 order added, with no way for one to cancel another
 *                 (ActionEvent has no consume() the way MouseEvent/
 *                 KeyEvent do). Actually silencing the host requires
 *                 detaching its listener first, which intercept()
 *                 does - and restore() re-attaches the exact same
 *                 original object afterward, so the host regains its
 *                 voice with no memory of having been silenced.
 *
 * ROI Manager's button bar was confirmed by reading RoiManager.java on
 * GitHub to have used both a legacy AWT Button and (in some button
 * migrations) Swing AbstractButton across FIJI's history - the same
 * reason RoiSelectionGrouper can't assume its list is still
 * java.awt.List either. findButton() below checks for both so this
 * keeps working regardless of which one a given FIJI version uses.
 */
class MeasureAutoRename {

    // Tracks which native Measure button instances have already been
    // wired via attach(), keyed by identity, so re-calling attach()
    // (e.g. once per refresh, since ROI Manager can be closed and
    // reopened as a brand-new Frame/button at any time) never stacks
    // up duplicate listeners on the same live button.
    private static final Set<Component> WIRED =
        Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>())

    // For intercept()/restore(): the exact ActionListener[] that was
    // on a given button before intercept() removed them, so restore()
    // can put back precisely what was there - never a guess, never
    // "recreate a fresh RoiManager instead."
    private static final Map<Component, List<ActionListener>> ORIGINAL_LISTENERS =
        Collections.synchronizedMap(new IdentityHashMap<Component, List<ActionListener>>())

    /**
     * Recursively searches a component tree for a button-like widget
     * with the given label - either legacy AWT (java.awt.Button) or
     * Swing (javax.swing.AbstractButton, e.g. JButton). Returns it as
     * a plain Component; both widget types expose
     * addActionListener/removeActionListener/getActionListeners under
     * their own class, so Groovy can call any of them correctly on
     * whichever one is actually found without needing to know which
     * type it is ahead of time.
     */
    static Component findButton(Container root, String label) {
        if (root == null) return null
        for (Component c : root.getComponents()) {
            if (c instanceof Button && label.equals(((Button) c).getLabel())) {
                return c
            }
            if (c instanceof javax.swing.AbstractButton && label.equals(((javax.swing.AbstractButton) c).getText())) {
                return c
            }
            if (c instanceof Container) {
                Component found = findButton((Container) c, label)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Wires the currently-open RoiManager's native "Measure" button so
     * that, immediately after it performs its own measurement, the
     * RenameLabelsInTable.ijm naming conversion runs automatically
     * against the Results table it just wrote to. Does NOT change
     * what the native click itself does - see intercept() for that.
     *
     * Safe to call repeatedly (idempotent per live Button instance).
     */
    static boolean attach(RoiManager rm, String labelMacroPath, Closure onAutoRenamed) {
        if (rm == null) return false
        Component measureBtn = findButton(rm, "Measure")
        if (measureBtn == null) return false
        if (WIRED.contains(measureBtn)) return true

        measureBtn.addActionListener({ e ->
            javax.swing.SwingUtilities.invokeLater({
                try {
                    File f = new File(labelMacroPath)
                    if (f.exists()) {
                        IJ.runMacroFile(f.getAbsolutePath())
                        if (onAutoRenamed != null) onAutoRenamed()
                    }
                } catch (Exception ignored) { }
            } as Runnable)
        } as ActionListener)

        WIRED.add(measureBtn)
        return true
    }

    /**
     * Silences whatever ActionListener(s) currently sit on ROI
     * Manager's native "Measure" button and runs onMeasureClicked()
     * in their place instead - a peripheral acting on the host's
     * behalf, not a rewrite of it. The button's label, position, and
     * appearance are completely untouched; only who answers the click
     * changes, and only for as long as this stays attached.
     *
     * Idempotent per live button instance: calling this again on a
     * button that's already silenced is a no-op (it does NOT
     * re-capture "the current listener", which by then would just be
     * our own peripheral - that would permanently lose the real host
     * on a second call).
     *
     * @param rm                the live RoiManager instance
     * @param onMeasureClicked  no-arg closure run instead of the
     *                          native measurement when Measure is
     *                          clicked
     * @return true if this button is now silenced (whether by this
     *         call or an earlier one), false if no Measure button was found
     */
    static boolean intercept(RoiManager rm, Closure onMeasureClicked) {
        if (rm == null) return false
        Component measureBtn = findButton(rm, "Measure")
        if (measureBtn == null) return false
        if (ORIGINAL_LISTENERS.containsKey(measureBtn)) return true

        def existing = measureBtn.getActionListeners() as List
        ORIGINAL_LISTENERS[measureBtn] = existing
        existing.each { l -> measureBtn.removeActionListener(l) }

        measureBtn.addActionListener({ e -> onMeasureClicked() } as ActionListener)
        return true
    }

    /**
     * Undoes intercept(): detaches our peripheral listener and
     * re-attaches the exact original host ActionListener(s) that were
     * captured at the time - the host resumes with no memory of
     * having been silenced. Safe to call on a button that was never
     * intercepted (no-op) or has since been replaced by a new
     * RoiManager instance (also a no-op, since ORIGINAL_LISTENERS is
     * keyed by the specific Component identity - a new instance's
     * button was never captured, so there's nothing to restore or
     * accidentally double-restore).
     */
    static boolean restore(RoiManager rm) {
        if (rm == null) return false
        Component measureBtn = findButton(rm, "Measure")
        if (measureBtn == null) return false
        if (!ORIGINAL_LISTENERS.containsKey(measureBtn)) return false

        measureBtn.getActionListeners().each { l -> measureBtn.removeActionListener(l) }
        ORIGINAL_LISTENERS.remove(measureBtn).each { l -> measureBtn.addActionListener(l) }
        return true
    }
}
