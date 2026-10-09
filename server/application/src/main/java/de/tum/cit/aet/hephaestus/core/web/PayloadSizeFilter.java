package de.tum.cit.aet.hephaestus.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an oversized request before anything buffers its body. Tomcat's {@code max-http-post-size}
 * bounds only form and multipart parameter parsing, so a JSON body is otherwise unconstrained. A body
 * sent without a {@code Content-Length} ({@code Transfer-Encoding: chunked}) is refused with 411 as well:
 * its size is knowable only after it has been read, which is the cost the limit exists to avoid. A
 * request with neither header carries no body (RFC 9112 § 6.3), so it passes.
 */
public class PayloadSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public PayloadSizeFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0 && request.getHeader(HttpHeaders.TRANSFER_ENCODING) == null) {
            chain.doFilter(request, response);
            return;
        }
        if (contentLength < 0) {
            refused(request, "length-required");
            response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
            return;
        }
        if (contentLength > maxBytes) {
            refused(request, "payload-too-large");
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Runs before a refusal is answered, for a surface that meters its own refusals. */
    protected void refused(HttpServletRequest request, String reason) {}
}
