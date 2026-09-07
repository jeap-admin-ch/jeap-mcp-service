package ch.admin.bit.jeap.mcp.docs;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Securely reads a single jEAP documentation file referenced by an index-local, repo-prefixed path
 * (optionally with a trailing {@code #anchor} / {@code ?query}). The path is vetted by {@link DocPathPolicy}.
 */
@Component
public class DocsReader {

    private final DocPathPolicy pathPolicy;

    public DocsReader(DocPathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    /**
     * Read a single documentation file in full.
     *
     * @throws DocAccessException with a clean, non-leaking message for blank/absolute/{@code ..} paths,
     *                            traversal escapes, non-allow-listed files, and not-found.
     */
    public Doc readDocument(String rawRef) {
        DocRef ref = DocRef.parse(rawRef);
        Path file = pathPolicy.resolve(ref.path());
        return new Doc(ref, read(file));
    }

    /**
     * Best-effort read: the document, or {@link Optional#empty()} when it cannot be read for any
     * reason the read-time policy rejects — a blank/absolute/{@code ..} path, an absent docs root, a
     * not-found target, or a non-allow-listed file.
     */
    public Optional<Doc> find(String rawRef) {
        try {
            return Optional.of(readDocument(rawRef));
        } catch (DocAccessException _) {
            return Optional.empty();
        }
    }

    /**
     * Enumerate every allow-listed, index-local doc path — used to answer completion requests for the
     * {@code jeap-docs://{ref}} resource template.
     */
    public List<String> listKnownPaths() {
        return pathPolicy.listKnownPaths();
    }

    private static String read(Path file) {
        try {
            // Lenient UTF-8 decoding on purpose: new String(bytes, UTF_8) substitutes the replacement
            // char (U+FFFD) for malformed/unmappable bytes, whereas Files.readString uses a strict
            // (REPORT) decoder that throws MalformedInputException on the first bad byte. That exception
            // is an IOException, so it would be caught below and surface to the agent as the misleading
            // "Document not found." For a best-effort docs server we prefer to still serve the file with a
            // replacement char where a stray byte was, rather than fail the whole read on one encoding glitch.
            // (The whole file is read into memory uncapped; that's fine because the allow-list restricts
            // reads deliberately small docs/** files.)
            //noinspection ReadWriteStringCanBeUsed
            return new String(Files.readAllBytes(file), UTF_8);
        } catch (IOException _) {
            throw new DocAccessException("Document not found.");
        }
    }
}
