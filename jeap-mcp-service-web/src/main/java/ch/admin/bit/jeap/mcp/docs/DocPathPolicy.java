package ch.admin.bit.jeap.mcp.docs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Security policy that turns a normalized, repo-prefixed documentation path into a real, readable
 * file. It enforces, in order:
 * <ol>
 *     <li>the path-traversal guard (no absolute paths, no {@code ..} segments),</li>
 *     <li>symlink-resolved containment inside the docs root (segment-aware {@link Path#startsWith},
 *         not string prefix, so {@code /jeap/srcXYZ} cannot slip past {@code /jeap/src}),</li>
 *     <li>the allow-list — {@code <repo>/docs/**} or a repo-root {@code <repo>/README.md}, with a
 *         text extension — applied to the REAL, symlink-resolved target so a symlink under
 *         {@code docs/} pointing at a non-docs file inside the root is still rejected.</li>
 * </ol>
 */
@Slf4j
@Component
public class DocPathPolicy {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "markdown", "adoc", "rst", "txt");

    private final Path docsRoot;

    // Lazily computed, then cached for the lifetime of this (singleton) bean: the docs root is baked
    // into the container image at build time and never changes while the process is running, so
    // re-walking the filesystem on every completion/complete keystroke would be wasted work.
    private volatile List<String> knownPathsCache;

    public DocPathPolicy(JeapDocsProperties props) {
        this.docsRoot = Path.of(props.docsRoot());
    }

    /**
     * Vet a normalized path and return its real, symlink-resolved target.
     *
     * @throws DocAccessException for absolute/{@code ..} paths, an absent docs root, a not-found
     *                            target, or a target outside the allow-list.
     */
    public Path resolve(String pathPart) {
        guardTraversal(pathPart);
        Path rootReal = realRoot();
        Path real = realTarget(pathPart);
        if (!real.startsWith(rootReal)) {
            log.error("Denied documentation access: requested path '{}' resolved to '{}', which escapes the docs root '{}'.",
                    pathPart, real, rootReal);
            throw new DocAccessException("Access to this path is not allowed.");
        }
        if (!isAllowed(rootReal.relativize(real))) {
            log.error("Denied documentation access: requested path '{}' resolved to '{}', which is not on the docs allow-list.",
                    pathPart, real);
            throw new DocAccessException("Access to this path is not allowed.");
        }
        return real;
    }

    /**
     * Enumerate every allow-listed path under the docs root, for completion suggestions
     * ({@code jeap-docs://{ref}}). Applies the same {@link #isAllowed} rule as {@link #resolve}, so a
     * suggested path is always readable. Computed once and cached — see {@link #knownPathsCache}.
     *
     * @throws DocAccessException if the docs root is not available.
     */
    public List<String> listKnownPaths() {
        List<String> cached = knownPathsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (knownPathsCache == null) {
                knownPathsCache = walkKnownPaths();
            }
            return knownPathsCache;
        }
    }

    private List<String> walkKnownPaths() {
        Path rootReal = realRoot();
        try (Stream<Path> walk = Files.walk(rootReal)) {
            return walk.filter(Files::isRegularFile)
                    .map(rootReal::relativize)
                    .filter(DocPathPolicy::isAllowed)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Documentation store is not available: could not list known paths under '{}' ({}).",
                    rootReal, e.toString());
            throw new DocAccessException("Document store is not available.");
        }
    }

    private static void guardTraversal(String pathPart) {
        if (pathPart.startsWith("/")) {
            log.error("Denied documentation access: requested path '{}' is an absolute path.", pathPart);
            throw new DocAccessException("Absolute paths are not allowed.");
        }
        for (String segment : pathPart.split("/")) {
            if (segment.equals("..")) {
                log.error("Denied documentation access: requested path '{}' contains a '..' traversal segment.", pathPart);
                throw new DocAccessException("Access to this path is not allowed.");
            }
        }
    }

    private Path realRoot() {
        try {
            return docsRoot.toRealPath();
        } catch (IOException e) {
            log.error("Documentation store is not available: configured docs root '{}' could not be resolved ({}).",
                    docsRoot, e.toString());
            throw new DocAccessException("Document store is not available.");
        }
    }

    private Path realTarget(String pathPart) {
        try {
            // resolve() can throw InvalidPathException (a RuntimeException) on invalid path
            // characters such as an embedded NUL.
            Path candidate = docsRoot.resolve(pathPart).normalize();
            return candidate.toRealPath();
        } catch (IOException | InvalidPathException e) {
            log.warn("Documentation not found: requested path '{}' could not be read ({}).",
                    pathPart, e.toString());
            throw new DocAccessException("Document not found.");
        }
    }

    /** {@code <repo>/docs/**} (with a text extension) OR a repo-root {@code <repo>/README.md}. */
    private static boolean isAllowed(Path rootRelative) {
        int names = rootRelative.getNameCount();
        if (names == 0) {
            return false;
        }
        String last = rootRelative.getName(names - 1).toString();
        if (!ALLOWED_EXTENSIONS.contains(DocPaths.extension(last))) {
            return false;
        }
        boolean underDocs = names >= 3 && rootRelative.getName(1).toString().equals("docs");
        boolean repoReadme = names == 2 && last.equalsIgnoreCase("README.md");
        return underDocs || repoReadme;
    }
}
