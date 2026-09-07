package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure reference parsing: {@code #anchor} kept, {@code ?query} dropped, blank rejected. */
class DocRefTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jeap-messaging/docs/outbox.md",         // plain path, no fragment
            "jeap-messaging/docs/outbox.md?plain=1", // query dropped, no fragment
            "jeap-messaging/docs/outbox.md#"         // empty fragment
    })
    void parsesPathWithNullAnchor(String reference) {
        DocRef ref = DocRef.parse(reference);
        assertEquals("jeap-messaging/docs/outbox.md", ref.path());
        assertNull(ref.anchor());
    }

    @ParameterizedTest
    @CsvSource({
            // reference                                     -> expected anchor
            "jeap-messaging/docs/outbox.md#configuration,    configuration",  // fragment kept as anchor
            "jeap-messaging/docs/outbox.md?plain=1#L12,      L12",            // query dropped, trailing fragment kept
            "jeap-messaging/docs/outbox.md # configuration,  configuration"   // whitespace around '#' stripped from path and anchor
    })
    void keepsFragmentAsAnchor(String reference, String expectedAnchor) {
        DocRef ref = DocRef.parse(reference);
        assertEquals("jeap-messaging/docs/outbox.md", ref.path());
        assertEquals(expectedAnchor, ref.anchor());
    }

    @Test
    void stripsSurroundingWhitespace() {
        DocRef ref = DocRef.parse("  jeap-messaging/docs/outbox.md  ");
        assertEquals("jeap-messaging/docs/outbox.md", ref.path());
    }

    @Test
    void rejectsNull() {
        assertThrows(DocAccessException.class, () -> DocRef.parse(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(DocAccessException.class, () -> DocRef.parse("   "));
    }

    @Test
    void rejectsFragmentOnlyReference() {
        // "#frag" strips to an empty path -> rejected, never resolved against the filesystem
        assertThrows(DocAccessException.class, () -> DocRef.parse("#frag"));
    }
}
