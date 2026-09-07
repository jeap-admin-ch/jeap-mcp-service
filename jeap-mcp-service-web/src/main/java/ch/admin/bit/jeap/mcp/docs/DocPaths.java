package ch.admin.bit.jeap.mcp.docs;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Path-derivation helpers. Single source of truth so {@code area} / {@code extension} are derived consistently
 * everywhere (always off a normalized path, never a raw {@code #anchor}-bearing reference).
 */
public final class DocPaths {

    private static final Pattern DOCS_SEGMENT = Pattern.compile("(^|/)docs/");

    private DocPaths() {
    }

    /** {@code "docs"} if a path segment equals {@code docs}, else {@code "code"}. */
    public static String area(String path) {
        if (path == null || path.isEmpty()) {
            return "code";
        }
        return DOCS_SEGMENT.matcher(path).find() ? "docs" : "code";
    }

    /** Lowercased suffix after the last {@code .} of the last segment, or {@code "none"} if absent. */
    public static String extension(String path) {
        return Optional.ofNullable(StringUtils.getFilenameExtension(path)).
                filter(s -> !s.isEmpty()).
                map(s -> s.toLowerCase(Locale.ROOT)).
                orElse("none");
    }
}
