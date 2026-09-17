package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The containment guard on the {@code file_path} argument forwarded to project-rag's
 * find_definition/find_references/get_call_graph. Purely lexical (no filesystem access, see
 * RagFilePathPolicy's javadoc) so a fixed root works without an actual /jeap/src on disk.
 *
 * <p>Only absolute paths are ever accepted - a relative one is rejected outright, even when it
 * would land inside the source root if resolved against it, because {@link JeapRagTools} forwards
 * the caller's original (unresolved) string, and project-rag resolves a relative value against its
 * own process cwd, not against this policy's root. See the class javadoc for the bypass this
 * closes.
 */
class RagFilePathPolicyTest {

    private final RagFilePathPolicy policy = new RagFilePathPolicy(TestDocsProperties.withDocsRoot("/jeap/src"));

    @Test
    void acceptsNull() {
        assertDoesNotThrow(() -> policy.requireContained("file_path", null));
    }

    @Test
    void acceptsAbsolutePathsContainedInTheSourceRoot() {
        // The documented calling convention (root_path + "/" + file_path) produces an absolute
        // path here, since jeap_find_code_examples' own "root_path" result field is itself always
        // absolute (e.g. "/jeap/src/jeap-messaging").
        assertDoesNotThrow(() -> policy.requireContained("file_path", "/jeap/src/jeap-messaging/src/main/Foo.java"));
        assertDoesNotThrow(() -> policy.requireContained("file_path", "/jeap/src/jeap-messaging/pom.xml"));
    }

    @Test
    void rejectsRelativePathsEvenWhenTheyWouldLandInsideTheSourceRoot() {
        // Not a containment failure - "resolves inside the root" isn't even checked for these,
        // since project-rag would resolve them against its own cwd, not sourceRoot. See class javadoc.
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("file_path", "jeap-messaging/src/main/Foo.java"));
        assertEquals("Argument 'file_path' must be an absolute path under the indexed source root.", ex.getMessage());
    }

    @Test
    void rejectsBareRelativeFilename() {
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("file_path", "pom.xml"));
        assertEquals("Argument 'file_path' must be an absolute path under the indexed source root.", ex.getMessage());
    }

    @Test
    void rejectsAbsolutePathsOutsideTheSourceRoot() {
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("file_path", "/etc/passwd"));
        assertEquals("Argument 'file_path' must resolve inside the indexed source root.", ex.getMessage());
    }

    @Test
    void rejectsAbsolutePathTraversalThatEscapesTheSourceRoot() {
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("file_path", "/jeap/src/../../etc/passwd"));
        assertEquals("Argument 'file_path' must resolve inside the indexed source root.", ex.getMessage());
    }

    @Test
    void rejectsRelativeParentTraversal() {
        // Rejected for being relative in the first place - never gets as far as the containment check.
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("file_path", "../outside.txt"));
        assertEquals("Argument 'file_path' must be an absolute path under the indexed source root.", ex.getMessage());
    }

    @Test
    void allowsInternalTraversalThatStaysInsideTheRoot() {
        // "a/../b" normalizes to "b", still inside the root - not every ".." is an escape.
        assertDoesNotThrow(() -> policy.requireContained("file_path", "/jeap/src/jeap-messaging/src/../pom.xml"));
    }

    @Test
    void usesTheGivenFieldNameInErrorMessages() {
        JeapToolValidationException ex = assertThrows(JeapToolValidationException.class,
                () -> policy.requireContained("some_other_field", "/etc/passwd"));
        assertEquals("Argument 'some_other_field' must resolve inside the indexed source root.",
                ex.getMessage());
    }
}
