import ij.ImagePlus
import ij.IJ
import ij.measure.Calibration
import java.io.File

/**
 * Reads the live ImageJ Calibration / Set Scale state.
 *
 * For Bio-Formats or other native-format imports, this is the
 * ImagePlus calibration after the file has been opened. The manager
 * does not need to know the native format: it uses the calibration
 * ImageJ actually assigned to that ImagePlus.
 */
class CalibrationIO {

    static Object[] getLiveCalibration(ImagePlus imp) {
        Calibration cal = imp.getCalibration()
        return [cal.pixelWidth, cal.pixelHeight, cal.getUnit()] as Object[]
    }

    static String describeCalibrationSource(ImagePlus imp) {
        def fi = imp.getOriginalFileInfo()
        if (fi == null) return "ImageJ / in-memory"
        return "Opened file metadata + live Set Scale"
    }

    static String onDiskStatus(ImagePlus imp) {
        def fi = imp.getOriginalFileInfo()
        if (fi == null) return "N/A"

        Calibration cal = imp.getCalibration()
        double eps = 1e-9
        boolean w = Math.abs(cal.pixelWidth - fi.pixelWidth) < eps
        boolean h = Math.abs(cal.pixelHeight - fi.pixelHeight) < eps
        boolean u = (cal.getUnit() ?: "") == (fi.unit ?: "")
        return (w && h && u) ? "Yes" : "Changed in memory"
    }

    static String getOriginalPath(ImagePlus imp) {
        def fi = imp.getOriginalFileInfo()
        return fi != null ? new File(fi.directory, fi.fileName).getAbsolutePath() : null
    }

    static boolean saveCalibrationToFile(ImagePlus imp, String destPath) {
        try {
            IJ.saveAsTiff(imp, destPath)
            return true
        } catch (Exception ex) {
            IJ.log("CalibrationIO: save failed - " + ex.getMessage())
            return false
        }
    }
}
