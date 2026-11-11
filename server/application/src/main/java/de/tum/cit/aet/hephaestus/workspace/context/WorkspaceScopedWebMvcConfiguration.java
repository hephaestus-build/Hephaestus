package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers the {@link RequestMappingHandlerMapping} that prefixes workspace-scoped controllers with
 * {@code /workspaces/{workspaceSlug}} and places every application route under {@link ApiPath#PREFIX}.
 *
 * <p>The API prefix is chosen by path, not by handler type as {@code PathMatchConfigurer#addPathPrefix} would: the
 * routes that keep their own root are path families that the Gateway also routes by path. Framework handlers, such as
 * the error controller and the OpenAPI description, keep their own paths.
 */
@Configuration
public class WorkspaceScopedWebMvcConfiguration implements WebMvcRegistrations {

    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new WorkspaceScopedHandlerMapping();
    }

    private static final class WorkspaceScopedHandlerMapping extends RequestMappingHandlerMapping {

        private static final String WORKSPACE_PREFIX = "/workspaces/{workspaceSlug}";

        private static final String APPLICATION_PACKAGE = "de.tum.cit.aet.hephaestus.";

        /** Provider webhooks and the sandbox gateway's connector; neither is part of the API. */
        private static final List<String> OWN_ROOTS = List.of("/webhooks/", "/internal/");

        @Override
        protected void registerHandlerMethod(Object handler, Method method, RequestMappingInfo mapping) {
            RequestMappingInfo effective = mapping;
            if (mapping != null && isWorkspaceScoped(method.getDeclaringClass())) {
                RequestMappingInfo workspacePrefix = RequestMappingInfo.paths(WORKSPACE_PREFIX)
                        .options(getBuilderConfiguration())
                        .build();

                effective = normalizePaths(workspacePrefix.combine(mapping));
            }
            if (effective != null && isApiRoute(method.getDeclaringClass(), effective)) {
                effective = RequestMappingInfo.paths(ApiPath.PREFIX)
                        .options(getBuilderConfiguration())
                        .build()
                        .combine(effective);
            }
            super.registerHandlerMethod(handler, method, effective);
        }

        private static boolean isApiRoute(Class<?> handlerType, RequestMappingInfo mapping) {
            if (!handlerType.getName().startsWith(APPLICATION_PACKAGE) || mapping.getPathPatternsCondition() == null) {
                return false;
            }
            return mapping.getPathPatternsCondition().getPatternValues().stream()
                    .noneMatch(pattern -> OWN_ROOTS.stream().anyMatch(pattern::startsWith));
        }

        private boolean isWorkspaceScoped(Class<?> handlerType) {
            return AnnotatedElementUtils.hasAnnotation(handlerType, WorkspaceScopedController.class);
        }

        private RequestMappingInfo normalizePaths(RequestMappingInfo mapping) {
            if (mapping == null || mapping.getPathPatternsCondition() == null) {
                return mapping;
            }

            var originalPatterns = mapping.getPathPatternsCondition().getPatternValues();
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String pattern : originalPatterns) {
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                normalized.add(trimTrailingSlash(pattern));
            }

            if (normalized.size() == originalPatterns.size()) {
                return mapping;
            }

            return mapping.mutate().paths(normalized.toArray(String[]::new)).build();
        }

        private String trimTrailingSlash(String pattern) {
            if (pattern.length() > 1 && pattern.endsWith("/")) {
                return pattern.substring(0, pattern.length() - 1);
            }
            return pattern;
        }
    }
}
