package ch.admin.bit.jeap.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Rejects a {@code /mcp/**} request whose declared body size exceeds {@link #maxBytes}, before
 * Spring reads and parses it as JSON-RPC.
 * <p>
 * {@code JeapRagProperties}' {@code maxTextLength}/{@code maxListSize} only bound the individual
 * argument fields a {@code jeap_*} tool actually reads - they say nothing about the size of the
 * raw request body Jackson has to buffer and parse first. Since {@code /mcp/**} is anonymous
 * (see {@link McpSecurityConfig}), an oversized body is a cheap way to spend server CPU/memory on
 * parsing alone, independent of what any individual argument later validates to.
 * <p>
 * Primarily checked against the declared {@code Content-Length}. A request that omits it via
 * {@code Transfer-Encoding: chunked} is rejected outright rather than silently let through
 * unbounded: {@link HttpServletRequest#getContentLengthLong()} returns {@code -1} for such a
 * request, which would otherwise sail past the {@code > maxBytes} check below with the body then
 * streamed into Jackson with no cap at all. Every legitimate JSON-RPC POST this server expects
 * carries a {@code Content-Length}, so this costs no real client compatibility; a bodyless GET
 * (e.g. opening the SSE stream) is unaffected, since it never carries
 * {@code Transfer-Encoding: chunked} either. Every legitimate JSON-RPC body this server expects
 * is a few hundred KB at most (the worst case, {@code jeap_search_by_filters} with three
 * maxed-out filter lists, is roughly 150 KB); {@link #maxBytes} defaults with generous headroom
 * over that, not as a measured capacity figure.
 */
@Slf4j
class McpRequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    McpRequestSizeFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String transferEncoding = request.getHeader(HttpHeaders.TRANSFER_ENCODING);
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            log.warn("Rejected {} {}: chunked Transfer-Encoding declares no Content-Length, which this guard cannot bound.",
                    request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            log.warn("Rejected {} {}: declared Content-Length {} exceeds the {}-byte limit.",
                    request.getMethod(), request.getRequestURI(), declaredLength, maxBytes);
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(request, response);
    }
}
