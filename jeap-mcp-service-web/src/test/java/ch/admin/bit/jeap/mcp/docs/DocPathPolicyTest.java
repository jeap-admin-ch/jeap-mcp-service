package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DocPathPolicy#listKnownPaths()} — enumeration for {@code jeap-docs://{ref}} completion. The
 * read-time allow-list matrix itself is covered by {@link DocsReaderTest}; this only checks that
 * enumeration applies the same rule.
 */
class DocPathPolicyTest {

    @TempDir
    Path root;

    private DocPathPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("jeap-messaging/docs"));
        Files.writeString(root.resolve("jeap-messaging/docs/outbox.md"), "# Outbox");
        Files.writeString(root.resolve("jeap-messaging/README.md"), "# Readme");
        Files.createDirectories(root.resolve("jeap-messaging/src/main"));
        Files.writeString(root.resolve("jeap-messaging/src/main/Foo.java"), "class Foo {}");
        Files.writeString(root.resolve("jeap-messaging/notes.txt"), "loose note");
        Files.createDirectories(root.resolve("jeap-crypto/docs"));
        Files.writeString(root.resolve("jeap-crypto/docs/configuration.md"), "# Configuration");
        policy = new DocPathPolicy(TestDocsProperties.withDocsRoot(root.toString()));
    }

    @Test
    void listsAllowedDocsAndRepoReadmes() {
        List<String> paths = policy.listKnownPaths();

        assertTrue(paths.contains("jeap-messaging/docs/outbox.md"));
        assertTrue(paths.contains("jeap-messaging/README.md"));
        assertTrue(paths.contains("jeap-crypto/docs/configuration.md"));
    }

    @Test
    void excludesNonAllowlistedFiles() {
        List<String> paths = policy.listKnownPaths();

        assertFalse(paths.contains("jeap-messaging/src/main/Foo.java"), "non-docs code file");
        assertFalse(paths.contains("jeap-messaging/notes.txt"), "loose text file outside docs/ and not a repo README");
    }

    @Test
    void isSorted() {
        List<String> paths = policy.listKnownPaths();

        assertEquals(paths.stream().sorted().toList(), paths);
    }

    @Test
    void everyListedPathIsActuallyResolvable() {
        // listKnownPaths and resolve must agree - a suggested completion must always be readable.
        for (String path : policy.listKnownPaths()) {
            assertDoesNotThrow(() -> policy.resolve(path));
        }
    }

    @Test
    void listKnownPathsIsCachedAfterTheFirstCall() throws IOException {
        // Prime the cache.
        List<String> first = policy.listKnownPaths();
        assertFalse(first.contains("jeap-messaging/docs/new.md"));

        // A file added after the first call must NOT show up - the filesystem is not re-walked (the docs
        // root is static for the lifetime of a deployed instance, so this is the intended behaviour, not
        // just an artifact of caching).
        Files.writeString(root.resolve("jeap-messaging/docs/new.md"), "# New");

        assertEquals(first, policy.listKnownPaths());
    }
}
