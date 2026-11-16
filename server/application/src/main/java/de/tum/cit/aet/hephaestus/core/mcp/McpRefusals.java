package de.tum.cit.aet.hephaestus.core.mcp;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * The refusal that the HTTP handler a tool stands for would answer for an exception, so a tool names the same reason
 * as the web app (ADR 0110 decision 15). It asks the same {@code @RestControllerAdvice} handlers that the HTTP request
 * would reach, rather than keeping a second list of exceptions and messages.
 */
final class McpRefusals {

    private record Advice(ControllerAdviceBean bean, ExceptionHandlerMethodResolver resolver) {}

    private final List<Advice> advices;

    McpRefusals(ApplicationContext context) {
        // In the order Spring applies them, which @Order decides.
        this.advices = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                .filter(bean -> bean.getBeanType() != null)
                .map(bean -> new Advice(
                        bean, new ExceptionHandlerMethodResolver(Objects.requireNonNull(bean.getBeanType()))))
                .toList();
    }

    /**
     * The detail of the client error that the controller's request would answer, or empty when it would answer a
     * server error or no handler has a plain mapping for the exception.
     */
    Optional<String> clientError(Class<?> controller, Exception exception) {
        for (Advice advice : advices) {
            if (!advice.bean().isApplicableToBeanType(controller)) {
                continue;
            }
            Method handler = advice.resolver().resolveMethod(exception);
            if (handler == null) {
                continue;
            }
            // A catch-all handler answers a server error, and a handler that needs the request cannot run here:
            // an exception that carries its own status then answers for itself.
            if (handler.getParameterCount() != 1
                    || handler.getParameterTypes()[0] == Exception.class
                    || handler.getParameterTypes()[0] == RuntimeException.class) {
                break;
            }
            // The resolver also matches an exception's cause, so the handler gets the throwable of its own type.
            Throwable matched = exception;
            while (matched != null && !handler.getParameterTypes()[0].isInstance(matched)) {
                matched = matched.getCause();
            }
            if (matched == null) {
                return Optional.empty();
            }
            try {
                ReflectionUtils.makeAccessible(handler);
                ProblemDetail problem = problemOf(
                        ReflectionUtils.invokeMethod(handler, advice.bean().resolveBean(), matched));
                return problem == null ? Optional.empty() : clientError(problem);
            } catch (RuntimeException unanswerable) {
                // A handler that cannot answer here leaves the exception a server error.
                return Optional.empty();
            }
        }
        return exception instanceof ErrorResponse response ? clientError(response.getBody()) : Optional.empty();
    }

    private static @Nullable ProblemDetail problemOf(@Nullable Object answer) {
        if (answer instanceof ProblemDetail problem) {
            return problem;
        }
        if (answer instanceof ResponseEntity<?> entity && entity.getBody() instanceof ProblemDetail problem) {
            return problem;
        }
        return answer instanceof ErrorResponse response ? response.getBody() : null;
    }

    private static Optional<String> clientError(ProblemDetail problem) {
        if (problem.getStatus() < 400 || problem.getStatus() >= 500) {
            return Optional.empty();
        }
        String detail = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
        return Optional.of(detail == null ? "The request was refused." : detail);
    }
}
