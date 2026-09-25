package de.tum.cit.aet.hephaestus.account.userview;

import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Binds a read-only user view to the normal app. Runs after Spring Security, which admits only an instance
 * administrator's {@code GET} in view mode, and before {@link WorkspaceContextFilter}, which reads
 * the view. A route outside {@link #READ_PATHS} is refused before a {@code USER_VIEW} row is written.
 */
@Component
@ConditionalOnServerRole
@Profile("!specs")
@Order(WorkspaceContextFilter.ORDER - 1)
@RequiredArgsConstructor
public class UserViewSessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserViewSessionFilter.class);
    private static final PathPatternParser PATHS = new PathPatternParser();
    static final List<PathPattern> READ_PATHS = List.of(
                    "/workspaces",
                    "/workspaces/{workspaceSlug}",
                    "/workspaces/{workspaceSlug}/members/me",
                    "/workspaces/{workspaceSlug}/connections/catalog",
                    "/workspaces/{workspaceSlug}/team",
                    "/workspaces/{workspaceSlug}/leaderboard",
                    "/workspaces/{workspaceSlug}/leaderboard/users/{login}/league-stats",
                    "/workspaces/{workspaceSlug}/profile/{login}",
                    "/workspaces/{workspaceSlug}/profile/{login}/activity-monitor",
                    "/workspaces/{workspaceSlug}/practice-groups",
                    "/workspaces/{workspaceSlug}/practice-groups/standings",
                    "/workspaces/{workspaceSlug}/practice-groups/{groupSlug}/review-runs",
                    "/workspaces/{workspaceSlug}/practice-groups/{groupSlug}/trend",
                    "/workspaces/{workspaceSlug}/practices/reviewed",
                    "/workspaces/{workspaceSlug}/practices/feedback/in-app",
                    "/workspaces/{workspaceSlug}/practices/standings",
                    "/workspaces/{workspaceSlug}/practices/trace",
                    "/workspaces/{workspaceSlug}/practices/trace/{artifactKind}/{artifactId}",
                    "/workspaces/{workspaceSlug}/practices/observations/{observationId}",
                    "/workspaces/{workspaceSlug}/mentor/threads",
                    "/workspaces/{workspaceSlug}/mentor/threads/{threadId}")
            .stream()
            .map(PATHS::parse)
            .toList();

    private final WorkspaceRepository workspaces;
    private final ViewedUserService users;
    private final UserViewAccess access;
    private final ObjectMapper mapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!UserViewContextHolder.USER_VIEW_REQUEST.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        String workspaceSlug = request.getHeader(UserViewContextHolder.WORKSPACE_HEADER);
        String userHeader = request.getHeader(UserViewContextHolder.USER_HEADER);
        String reason = request.getHeader(UserViewContextHolder.REASON_HEADER);

        try {
            if (workspaceSlug == null || userHeader == null || reason == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incomplete user view context");
            }
            String path = request.getRequestURI();
            Map<String, String> pathVariables = readPathVariables(PathContainer.parsePath(path));
            if (pathVariables == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This page is not available in user view");
            }
            String pathSlug = pathVariables.get("workspaceSlug");
            if (pathSlug != null && !pathSlug.equals(workspaceSlug)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found in user view");
            }
            long accountId = SecurityUtils.getCurrentAccountId()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            access.requireRecentSignIn(SecurityContextHolder.getContext().getAuthentication(), accountId);
            long userId;
            try {
                userId = Long.parseLong(userHeader);
                if (userId <= 0) throw new NumberFormatException();
            } catch (NumberFormatException invalidId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid viewed user id", invalidId);
            }
            Workspace workspace = workspaces
                    .findByWorkspaceSlug(workspaceSlug)
                    .filter(candidate -> candidate.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
            var user = users.requireUser(workspace.getId(), userId);
            String query = request.getQueryString();
            access.record(
                    workspace.getId(), userId, user.accountId(), reason, query == null ? path : path + "?" + query);
            UserViewContextHolder.set(new UserViewContextHolder.View(workspace.getId(), userId));
            response.setHeader("Cache-Control", "no-store");
        } catch (RuntimeException error) {
            writeFailure(response, error);
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            UserViewContextHolder.clear();
        }
    }

    /** The URI variables of the allowed read this path is, or {@code null} when it is none. */
    private static @Nullable Map<String, String> readPathVariables(PathContainer path) {
        for (PathPattern pattern : READ_PATHS) {
            PathPattern.PathMatchInfo match = pattern.matchAndExtract(path);
            if (match != null) {
                return match.getUriVariables();
            }
        }
        return null;
    }

    private void writeFailure(HttpServletResponse response, RuntimeException error) throws IOException {
        ProblemDetail problem;
        if (error instanceof ErrorResponse responseError) {
            problem = responseError.getBody();
        } else if (error instanceof EntityNotFoundException) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Viewed user not found");
        } else {
            log.error("User view validation failed", error);
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "User view is unavailable");
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
