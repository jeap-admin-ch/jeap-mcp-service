package ch.admin.bit.jeap.mcp.docs;

import java.util.regex.Pattern;

/**
 * Strips characters an attacker-controlled value could use to forge extra log lines/entries before
 * it is embedded in a log message. Covers ASCII control characters ({@code \p{Cntrl}}, includes
 * CR/LF) plus the Unicode line separators NEL ({@code U+0085}), LINE SEPARATOR ({@code U+2028}) and
 * PARAGRAPH SEPARATOR ({@code U+2029}) - several log viewers and JSON-log consumers treat those as
 * line breaks too, and {@code \p{Cntrl}} alone does not cover them.
 * <p>
 * Shared by {@link DocPathPolicy} and {@code ch.admin.bit.jeap.mcp.tool.RagFilePathPolicy} - both
 * log a caller-controlled path on rejection.
 */
public final class LogSanitizer {

    private static final Pattern LOG_FORGING_CHARS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private LogSanitizer() {
    }

    /** @param value a caller-controlled string about to be embedded in a log message */
    public static String forLog(String value) {
        return LOG_FORGING_CHARS.matcher(value).replaceAll("?");
    }
}
