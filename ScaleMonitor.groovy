/**
 * Timepoint tag parsing.
 */
class ScaleMonitor {
    static final def TAG_PATTERN = ~/(?i)(_\d+h(?:ou)?rs?)(?=$|[^a-zA-Z0-9])/

    static String extractTag(String title) {
        if (title == null) return null
        def m = title =~ TAG_PATTERN
        return m.find() ? m.group(1) : null
    }

    static boolean isTimepointImage(String title) {
        return extractTag(title) != null
    }
}
