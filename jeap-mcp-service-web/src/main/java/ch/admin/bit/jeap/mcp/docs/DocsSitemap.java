package ch.admin.bit.jeap.mcp.docs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the allow-listed doc paths as a markdown sitemap of ready-to-use, percent-encoded
 * {@code jeap-docs://{ref}} URIs, grouped by repository. Appended to the {@code jeap-docs://index}
 * resource so a client can copy a URI directly instead of having to construct (and correctly
 * percent-encode) one itself.
 */
public final class DocsSitemap {

    private DocsSitemap() {
    }

    /**
     * @param knownPaths allow-listed, index-local doc paths (see {@link DocPathPolicy#listKnownPaths()}),
     *                    each {@code <repo>/...}
     * @return a markdown section, one heading per repository, listing its docs as
     * {@code jeap-docs://<percent-encoded-path>} bullets
     */
    public static String render(List<String> knownPaths) {
        Map<String, List<String>> byRepo = new TreeMap<>();
        for (String path : knownPaths) {
            int slash = path.indexOf('/');
            String repo = slash < 0 ? path : path.substring(0, slash);
            byRepo.computeIfAbsent(repo, _ -> new ArrayList<>()).add(path);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Documentation files\n\n");
        sb.append("Ready-to-use `jeap-docs://{ref}` resource URIs, grouped by repository:\n");
        for (Map.Entry<String, List<String>> entry : byRepo.entrySet()) {
            sb.append("\n### ").append(entry.getKey()).append('\n');
            for (String path : entry.getValue()) {
                sb.append("- `jeap-docs://").append(percentEncode(path)).append("`\n");
            }
        }
        return sb.toString();
    }

    /**
     * Strict RFC 3986 percent-encoding, e.g. a space becomes {@code %20}. {@link URLEncoder} alone
     * implements {@code application/x-www-form-urlencoded} instead, which encodes a space as {@code +}
     * — wrong here since these are URIs, not form data (and {@code +} is left un-encoded, so a client
     * decoding it back would misread it as a space). No allow-listed doc path currently contains a
     * space, so this has not been reachable in practice, but the sitemap is meant for external clients
     * to copy verbatim, so it should be correct regardless.
     */
    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
