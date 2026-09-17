package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.docs.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Containment guard for the {@code file_path} argument of {@link JeapRagTools}' location-based
 * tools ({@code jeap_find_definition}, {@code jeap_find_references}, {@code jeap_get_call_graph}),
 * rejecting anything that does not resolve inside the indexed jEAP source root before it is
 * forwarded to the upstream {@code project-rag} process.
 * <p>
 * {@code project-rag}'s own {@code create_file_info} (see {@code jeap-project-rag}'s
 * {@code src/client/mod.rs}) canonicalizes and reads whatever {@code file_path} it is given, with
 * no containment check of its own - an absolute path like {@code /etc/passwd} is read verbatim,
 * and its content can surface back through {@code jeap_find_definition}'s {@code signature}/
 * {@code doc_comment} fields if it happens to parse as a supported language. Since these tools are
 * reachable anonymously (see {@code McpSecurityConfig}), that is a real arbitrary-file-read
 * primitive, not just a theoretical one.
 * <p>
 * Reuses {@link JeapDocsProperties#docsRoot()} (default {@code /jeap/src}) rather than a separate
 * property: every instance's {@code Dockerfile} {@code COPY}s the {@code jeap-project-rag-preindexed}
 * image's {@code /jeap/src} verbatim (see {@code docs/deployment.md}), and that image's own indexing
 * scripts (@code jeap-index.sh}) clone every repo to {@code /jeap/src/<project>} - so it is already
 * the exact same physical root {@code DocPathPolicy} anchors the docs tools to, not a coincidence.
 * <p>
 * <b>Only an absolute {@code file_path} is accepted - a relative one is rejected outright, not
 * resolved.</b> The documented calling convention (see {@code jeap_find_code_examples}' {@code
 * root_path}/{@code file_path} result fields, and the instance repos' deployment smoke tests, e.g.
 * {@code AfterDeploymentSmokeTestIT.locationBasedToolsWork}) is the literal concatenation
 * {@code root_path + "/" + file_path}, and {@code root_path} is itself always absolute (e.g.
 * {@code /jeap/src/jeap-messaging}) - so a legitimate caller never sends a relative value. A
 * relative value is also unsafe to accept: this class validates it against {@link #sourceRoot},
 * but {@link JeapRagTools} then forwards the caller's original string unchanged, and {@code
 * project-rag} canonicalizes a relative path against <em>its own process's working directory</em>,
 * not {@link #sourceRoot}. Those two bases can differ (the reference {@code Dockerfile} in {@code
 * docs/deployment.md} sets no explicit {@code WORKDIR}), which would let a relative value like
 * {@code "etc/passwd"} pass this check as {@code sourceRoot}-relative while {@code project-rag}
 * actually reads it relative to its own cwd - defeating the containment check entirely. Requiring
 * an absolute, pre-vetted value removes that mismatch: both this class and {@code project-rag}
 * then agree on the exact same path, with no base-directory ambiguity.
 * <p>
 * Containment is checked on the lexically {@link Path#normalize() normalized} path only - no
 * {@code toRealPath()}/symlink resolution, and no requirement that the target actually exist.
 * Unlike {@code DocPathPolicy} (which always resolves an existing doc), a {@code file_path} that is
 * contained but simply wrong/missing must still be forwarded upstream so {@code project-rag}'s own
 * "not found" message reaches the caller unchanged - only paths that resolve outside the root are
 * rejected here. Symlink escapes are already ruled out upstream of this: {@code jeap-index.sh}
 * strips every symlink from the tree before it is indexed, specifically because {@code project-rag}
 * reads through symlink-following filesystem calls.
 */
@Slf4j
@Component
class RagFilePathPolicy {

    private final Path sourceRoot;

    RagFilePathPolicy(JeapDocsProperties docsProperties) {
        this.sourceRoot = Path.of(docsProperties.docsRoot()).normalize();
        log.info("RagFilePathPolicy anchored at indexed source root '{}'.", sourceRoot);
    }

    /**
     * @param fieldName the argument's name, used in the thrown exception's message
     * @param filePath  the caller-supplied {@code file_path} - must be absolute (see class
     *                  javadoc); {@code null} is always accepted (an absent optional argument,
     *                  though {@code file_path} is required at the {@code @Tool} method level)
     * @throws JeapToolValidationException if {@code filePath} is not absolute, or resolves outside
     *                                      the indexed source root
     */
    void requireContained(String fieldName, String filePath) {
        if (filePath == null) {
            return;
        }
        if (!filePath.startsWith("/")) {
            log.warn("Rejected RAG '{}': '{}' is not an absolute path.", fieldName, LogSanitizer.forLog(filePath));
            throw new JeapToolValidationException(fieldName,
                    "Argument '" + fieldName + "' must be an absolute path under the indexed source root.");
        }
        Path resolved;
        try {
            resolved = sourceRoot.resolve(filePath).normalize();
        } catch (InvalidPathException e) {
            log.warn("Rejected RAG '{}': '{}' is not a valid path ({}).", fieldName, LogSanitizer.forLog(filePath), e.toString());
            throw new JeapToolValidationException(fieldName, "Argument '" + fieldName + "' is not a valid path.");
        }
        if (!resolved.startsWith(sourceRoot)) {
            log.warn("Rejected RAG '{}': '{}' resolves to '{}', which escapes the indexed source root '{}'.",
                    fieldName, LogSanitizer.forLog(filePath), LogSanitizer.forLog(resolved.toString()), sourceRoot);
            throw new JeapToolValidationException(fieldName,
                    "Argument '" + fieldName + "' must resolve inside the indexed source root.");
        }
    }
}
