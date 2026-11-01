package de.tum.cit.aet.hephaestus.observability;

import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import de.tum.cit.aet.hephaestus.core.web.RequestReference;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Names the request in every problem that a controller or Spring MVC returns ({@link RequestReference}). */
@RestControllerAdvice
@ReceiverComponent
public class ProblemRequestIdAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem && response instanceof ServletServerHttpResponse servlet) {
            RequestReference.name(problem, servlet.getServletResponse());
        }
        return body;
    }
}
