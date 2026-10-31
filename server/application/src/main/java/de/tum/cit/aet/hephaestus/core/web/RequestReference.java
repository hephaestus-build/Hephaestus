package de.tum.cit.aet.hephaestus.core.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.http.ProblemDetail;

/**
 * Names the request in a problem as {@code requestId}, with the trace id that the observability
 * module's correlation filter already set as {@link #HEADER}. The header is the id's one home, so a
 * problem that a filter writes itself and one that a controller returns carry the same reference. A
 * reader quotes it from the error view, and it finds every log record of that request.
 */
public final class RequestReference {

    /** The response header that carries the request's trace id. */
    public static final String HEADER = "X-Request-Id";

    public static final String PROPERTY = "requestId";

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private RequestReference() {}

    public static ProblemDetail name(ProblemDetail problem, HttpServletResponse response) {
        String id = response.getHeader(HEADER);
        if (id != null && TRACE_ID.matcher(id).matches()) {
            problem.setProperty(PROPERTY, id);
        }
        return problem;
    }
}
