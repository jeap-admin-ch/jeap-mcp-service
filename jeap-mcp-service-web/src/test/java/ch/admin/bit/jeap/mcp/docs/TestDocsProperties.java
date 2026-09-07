package ch.admin.bit.jeap.mcp.docs;

/**
 * Test factory for {@link JeapDocsProperties} so the record literal is not pinned across many tests.
 * Only tests that exercise a non-default field set it explicitly via {@code withDocsRoot(...)} /
 * {@code withDocsRootAndMaxDocs(...)}.
 */
public final class TestDocsProperties {

    private TestDocsProperties() {
    }

    public static JeapDocsProperties defaults() {
        return new JeapDocsProperties("/jeap/src", 5, 0.5, true);
    }

    public static JeapDocsProperties withDocsRoot(String docsRoot) {
        return new JeapDocsProperties(docsRoot, 5, 0.5, true);
    }

    public static JeapDocsProperties withDocsRootAndMaxDocs(String docsRoot, int maxDocs) {
        return new JeapDocsProperties(docsRoot, maxDocs, 0.5, true);
    }
}
