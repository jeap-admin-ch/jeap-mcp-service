package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocPathsTest {

    @ParameterizedTest
    @CsvSource({
            "jeap-messaging/docs/outbox.md, docs",
            "docs/overview/x.md,            docs",
            "src/docs/foo.md,               docs",
            "jeap-messaging/src/main/Foo.java, code",
            "jeap-messaging/README.md,      code",
            "mydocs/notes.md,               code",
            "documentation/x.md,            code"
    })
    void area(String path, String expected) {
        assertEquals(expected, DocPaths.area(path));
    }

    @ParameterizedTest
    @CsvSource({
            "jeap-messaging/docs/outbox.md, md",
            "a/b/c.MD,                       md",
            "a/b/c.Markdown,                 markdown",
            "a/b/c.adoc,                     adoc",
            "jeap-messaging/README,          none",
            "a/b/c.,                         none",
            "noextension,                    none"
    })
    void extension(String path, String expected) {
        assertEquals(expected, DocPaths.extension(path));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("code", DocPaths.area(null));
        assertEquals("code", DocPaths.area(""));
        assertEquals("none", DocPaths.extension(null));
        assertEquals("none", DocPaths.extension(""));
    }
}
