package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-document read and the security matrix. Reference parsing ({@code #anchor} / {@code ?query}
 * stripping) is covered by {@link DocRefTest}; the reshape pipeline by {@code DocumentSearchTest}.
 */
class DocsReaderTest {

    @TempDir
    Path root;

    private DocsReader reader;

    @BeforeEach
    void setUp() throws IOException {
        // fixture: <root>/jeap-messaging/docs/outbox.md, <root>/jeap-messaging/README.md, a code file
        Files.createDirectories(root.resolve("jeap-messaging/docs"));
        Files.writeString(root.resolve("jeap-messaging/docs/outbox.md"), "# Outbox\nbody text");
        Files.writeString(root.resolve("jeap-messaging/README.md"), "# Readme");
        Files.createDirectories(root.resolve("jeap-messaging/src/main"));
        Files.writeString(root.resolve("jeap-messaging/src/main/Foo.java"), "class Foo {}");
        reader = new DocsReader(new DocPathPolicy(TestDocsProperties.withDocsRoot(root.toString())));
    }

    // --- happy path ---

    @Test
    void readsAllowedDocAndExposesNormalizedPath() {
        Doc doc = reader.readDocument("jeap-messaging/docs/outbox.md");
        assertEquals("jeap-messaging/docs/outbox.md", doc.ref().path());
        assertNull(doc.ref().anchor());
        assertTrue(doc.content().contains("body text"));
    }

    @Test
    void readsDocAndKeepsFragmentAsAnchor() {
        Doc doc = reader.readDocument("jeap-messaging/docs/outbox.md#configuration");
        assertEquals("jeap-messaging/docs/outbox.md", doc.ref().path());
        assertEquals("configuration", doc.ref().anchor());
        assertTrue(doc.content().contains("body text"), "the normalized path is the file actually read");
    }

    @Test
    void allowsRepoRootReadme() {
        Doc doc = reader.readDocument("jeap-messaging/README.md");
        assertEquals("jeap-messaging/README.md", doc.ref().path());
        assertTrue(doc.content().contains("Readme"));
    }

    // --- find: best-effort, non-throwing variant ---

    @Test
    void findReturnsDocWhenReadable() {
        Optional<Doc> doc = reader.find("jeap-messaging/docs/outbox.md");
        assertTrue(doc.isPresent());
        assertTrue(doc.get().content().contains("body text"));
    }

    @Test
    void findReturnsEmptyWhenUnreadableInsteadOfThrowing() {
        // the security/not-found cases that readDocument throws on must degrade to empty for find
        assertTrue(reader.find("jeap-messaging/docs/missing.md").isEmpty(), "not found");
        assertTrue(reader.find("/etc/passwd").isEmpty(), "absolute path");
        assertTrue(reader.find("jeap-messaging/docs/../../etc/passwd").isEmpty(), "traversal");
        assertTrue(reader.find("jeap-messaging/src/main/Foo.java").isEmpty(), "non-allow-listed extension");
        assertTrue(reader.find("jeap-messaging/docs/a\0b.md").isEmpty(), "invalid path char (embedded NUL)");
    }

    // --- security matrix ---

    @Test
    void rejectsParentTraversal() {
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/docs/../../etc/passwd"));
    }

    @Test
    void rejectsAbsolutePath() {
        assertThrows(DocAccessException.class, () -> reader.readDocument("/etc/passwd"));
    }

    @Test
    void rejectsBlank() {
        assertThrows(DocAccessException.class, () -> reader.readDocument("   "));
    }

    @Test
    void rejectsNonAllowlistedExtension() {
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/src/main/Foo.java"));
    }

    @Test
    void rejectsNonDocsTextFileOutsideAllowlist() throws IOException {
        Files.writeString(root.resolve("jeap-messaging/notes.txt"), "loose note");
        // a text file that is neither under docs/ nor a repo-root README is denied
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/notes.txt"));
    }

    @Test
    void rejectsBareFilename() {
        assertThrows(DocAccessException.class, () -> reader.readDocument("README.md"));
    }

    @Test
    void rejectsInvalidPathCharAsCleanError() {
        // An embedded NUL makes Path.resolve throw InvalidPathException (a RuntimeException).
        // It must be mapped to DocAccessException, not leak out as a raw tool crash.
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/docs/a\0b.md"));
    }

    @Test
    void notFoundYieldsCleanError() {
        DocAccessException ex = assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/docs/missing.md"));
        assertFalse(ex.getMessage().contains(root.toString()), "error must not leak the root path");
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path secret = Files.writeString(root.getParent().resolve("secret.md"), "top secret");
        Path link = root.resolve("jeap-messaging/docs/leak.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException _) {
            return;   // platform without symlink support - skip
        }
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/docs/leak.md"));
    }

    @Test
    void rejectsSymlinkUnderDocsToNonDocsFileInsideRoot() throws IOException {
        // A symlink that stays INSIDE the root but resolves OUTSIDE <repo>/docs/** (here: <repo>/src/secret.md)
        // must be denied: the allow-list runs on the real, symlink-resolved target, not the requested shape.
        Path secret = Files.writeString(root.resolve("jeap-messaging/src/main/secret.md"), "in-root secret");
        Path link = root.resolve("jeap-messaging/docs/leak.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException _) {
            return;   // platform without symlink support - skip
        }
        assertThrows(DocAccessException.class,
                () -> reader.readDocument("jeap-messaging/docs/leak.md"));
    }

    @Test
    void allowsSymlinkUnderDocsToAnotherDoc() {
        // A symlink whose real target is ALSO under <repo>/docs/** is legitimate and must still be read.
        Path target = root.resolve("jeap-messaging/docs/outbox.md");
        Path link = root.resolve("jeap-messaging/docs/alias.md");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException _) {
            return;   // platform without symlink support - skip
        }
        Doc doc = reader.readDocument("jeap-messaging/docs/alias.md");
        assertTrue(doc.content().contains("body text"));
    }
}
