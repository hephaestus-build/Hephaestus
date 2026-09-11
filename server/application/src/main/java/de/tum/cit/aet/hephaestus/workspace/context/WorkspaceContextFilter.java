package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceElevationAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.WorkspaceElevationContext;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugHistoryRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Servlet filter that extracts workspace context from request path and populates ThreadLocal.
 * Only processes requests matching /workspaces/{slug} pattern.
 * Returns 404 for missing or non-ACTIVE workspaces.
 * Filter order is set to -5 to ensure it runs after Spring Security filters (typically -10)
 * but before controller execution.
 */
@ConditionalOnServerRole
@Component
@Order(-5)
@Profile("!specs")
public class WorkspaceContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextFilter.class);

    private static final Pattern WORKSPACE_PATH_PATTERN =
            Pattern.compile("^/workspaces/([a-z0-9][a-z0-9-]{2,50})(/.*)?$");

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final CurrentAccountUsers currentAccountUsers;
    private final WorkspaceMembershipAutoSeeder membershipAutoSeeder;
    private final WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final WorkspaceElevationAudit elevationAudit;

    public WorkspaceContextFilter(
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            CurrentAccountUsers currentAccountUsers,
            WorkspaceMembershipAutoSeeder membershipAutoSeeder,
            WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository,
            ConnectionService connectionService,
            ObjectMapper objectMapper,
            WorkspaceElevationAudit elevationAudit) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.currentAccountUsers = currentAccountUsers;
        this.membershipAutoSeeder = membershipAutoSeeder;
        this.workspaceSlugHistoryRepository = workspaceSlugHistoryRepository;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
        this.elevationAudit = elevationAudit;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();

        // Allow workspace registry endpoints that are not slugged
        if ("/workspaces".equals(path)
                || "/workspaces/".equals(path)
                || path.startsWith("/workspaces/providers")
                || path.startsWith("/workspaces/gitlab/")) {
            chain.doFilter(request, response);
            return;
        }

        if (!path.startsWith("/workspaces/")) {
            // Not a workspace-scoped path, continue without context (allows /workspaces root endpoints)
            chain.doFilter(request, response);
            return;
        }

        var matcher = WORKSPACE_PATH_PATTERN.matcher(path);

        if (!matcher.matches()) {
            sendWorkspaceSlugValidationError(httpResponse, extractInvalidSlug(path));
            return;
        }

        String slug = matcher.group(1);
        String safeSlug = LoggingUtils.sanitizeForLog(slug);
        String method = httpRequest.getMethod();
        String remainingPath = matcher.group(2) != null ? matcher.group(2) : "";
        boolean isBasePath = remainingPath.isBlank() || "/".equals(remainingPath);
        boolean isStatusPath = remainingPath.startsWith("/status");

        try {
            // Look up workspace by slug
            var workspaceOpt = workspaceRepository.findByWorkspaceSlug(slug);

            if (workspaceOpt.isEmpty()) {
                if (handleSlugRedirect(httpRequest, httpResponse, slug, remainingPath)) {
                    return;
                }
                sendWorkspaceNotFoundError(httpResponse, slug);
                return;
            }

            var workspace = workspaceOpt.get();

            // Check workspace status - only ACTIVE workspaces are accessible
            boolean isReadRequest = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            boolean allowLifecycleDelete = isBasePath && "DELETE".equalsIgnoreCase(method);
            boolean allowNonActive = isStatusPath || (isBasePath && isReadRequest) || allowLifecycleDelete;

            if (workspace.getStatus() != WorkspaceStatus.ACTIVE && !allowNonActive) {
                log.debug(
                        "Denied workspace access: reason=nonActiveStatus, workspaceSlug={}, status={}",
                        safeSlug,
                        workspace.getStatus());
                sendWorkspaceNotFoundError(httpResponse, slug);
                return;
            }

            // Fetch user roles across ALL of the account's linked identities (ADR 0017), so a member
            // signed in via one provider keeps access to workspaces they belong to under another.
            var currentUsers = currentAccountUsers.resolve();
            // Resolve membership ONCE here (single DB round-trip) and reuse the matched member ids both for the
            // role union and for pinning the workspace-provider identity below, instead of querying twice.
            MembershipResolution membership = fetchUserRoles(workspace, currentUsers);
            Set<WorkspaceRole> roles = membership.roles();

            // Instance super-admin elevation: an APP_ADMIN reaches ANY active workspace as ADMIN even
            // without an explicit membership or an SCM identity at all (the GitLab admin model), matching
            // WorkspaceAccessService's APP_ADMIN elevation. Deliberately ADMIN, never OWNER — ownership is
            // an explicit, member-granted role. Logged as elevated access.
            if (roles.isEmpty() && SecurityUtils.isSuperAdmin()) {
                log.info(
                        "Granted workspace access via instance-admin elevation: accountId={}, workspaceSlug={}",
                        SecurityUtils.getCurrentAccountId().orElse(null),
                        safeSlug);
                roles = Set.of(WorkspaceRole.ADMIN);
                // The single source of truth both audit trails read, so an action taken on this request
                // is tagged the same way whichever viewer an operator opens.
                WorkspaceElevationContext.set(workspace.getId());
                SecurityUtils.getCurrentAccountId()
                        .ifPresent(accountId -> elevationAudit.recordElevatedAccess(accountId, workspace.getId()));
            }

            boolean isPublicRead = Boolean.TRUE.equals(workspace.getIsPubliclyViewable()) && isReadRequest;

            if (roles.isEmpty() && !isPublicRead) {
                if (currentUsers.isEmpty()) {
                    sendWorkspaceUnauthorizedError(httpResponse, slug);
                } else {
                    log.debug("Denied workspace access: reason=notMember, workspaceSlug={}", safeSlug);
                    sendWorkspaceMembershipForbiddenError(httpResponse, slug);
                }
                return;
            }

            // installationId comes from the active GitHub App connection in the Connection registry.
            Long installationId = connectionService
                    .findActiveGitHubAppConfig(workspace.getId())
                    .map(ConnectionConfig.GitHubAppConfig::installationId)
                    .orElse(null);
            WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, roles, installationId);

            // Overwrite detection: warn if context already set
            if (WorkspaceContextHolder.getContext() != null) {
                log.warn("Detected context leak: reason=contextAlreadySet, workspaceSlug={}", safeSlug);
            }

            WorkspaceContextHolder.setContext(context);

            // Pin the request's active SCM identity to the account's user FOR THIS workspace's provider
            // (the linked identity that is a member here), so getCurrentUserLogin() and everything
            // downstream resolve the provider-correct user — not whichever provider the session logged in
            // with. Absent for a public workspace the account isn't a member of (falls back to the JWT).
            resolveWorkspaceIdentity(currentUsers, membership.memberUserIds())
                    .map(User::getLogin)
                    .ifPresent(CurrentScmIdentityHolder::set);

            log.debug(
                    "Set workspace context: workspaceSlug={}, workspaceId={}, roles={}",
                    safeSlug,
                    context.id(),
                    context.roles());

            // Continue filter chain
            chain.doFilter(request, response);
        } finally {
            // Always clear context to prevent leaks
            WorkspaceContextHolder.clearContext();
            WorkspaceElevationContext.clear();
            CurrentScmIdentityHolder.clear();
        }
    }

    /**
     * The resolved membership for the current request: the roles the account holds in this workspace
     * (unioned across linked identities) and the set of the account's user ids that actually hold a
     * membership row. Both are computed from a SINGLE membership query so the role union and the
     * provider-identity pin never re-query.
     */
    private record MembershipResolution(Set<WorkspaceRole> roles, Set<Long> memberUserIds) {
        static final MembershipResolution EMPTY = new MembershipResolution(Set.of(), Set.of());
    }

    /**
     * The account's SCM user that holds membership in this workspace — i.e. the identity for the
     * workspace's provider. Returns empty when none of the account's identities is a member (e.g. a
     * public workspace viewed by a non-member), leaving the JWT {@code preferred_username} authoritative.
     *
     * @param users the account's SCM users (one per linked identity)
     * @param memberUserIds the user ids that hold a membership row (from the single membership query)
     */
    private Optional<User> resolveWorkspaceIdentity(Collection<User> users, Set<Long> memberUserIds) {
        if (memberUserIds.isEmpty()) {
            return Optional.empty();
        }
        return users.stream()
                .filter(u -> u != null && memberUserIds.contains(u.getId()))
                .findFirst();
    }

    /**
     * Resolve workspace membership for the current authenticated user via a single query.
     *
     * @param workspace Workspace entity
     * @param users the account's SCM users (one per linked identity); roles are unioned across them
     * @return the roles and matched member user ids (empty if no membership or not authenticated)
     */
    private MembershipResolution fetchUserRoles(Workspace workspace, Collection<User> users) {
        try {
            Set<Long> userIds = users.stream()
                    .filter(u -> u != null && u.getId() != null)
                    .map(User::getId)
                    .collect(Collectors.toSet());
            if (userIds.isEmpty()) {
                log.debug("Skipped role fetch: reason=noAuthenticatedUser");
                return MembershipResolution.EMPTY;
            }

            // Single membership query, reused for both the role union and the member-id set below.
            // Union the roles the account holds across every linked identity (each identity mirrors a
            // distinct SCM user, but they belong to the SAME account, so unioning never widens access
            // beyond what the account already owns).
            var memberships = workspaceMembershipRepository.findByWorkspace_IdAndUser_IdIn(workspace.getId(), userIds);
            Set<WorkspaceRole> roles = memberships.stream()
                    .map(WorkspaceMembership::getRole)
                    .filter(role -> role != null)
                    .collect(Collectors.toSet());
            Set<Long> memberUserIds = memberships.stream()
                    .map(membership -> membership.getId().getUserId())
                    .collect(Collectors.toSet());

            if (!roles.isEmpty()) {
                log.debug("Resolved user roles: roles={}", roles);
                return new MembershipResolution(roles, memberUserIds);
            }

            try {
                Optional<WorkspaceMembership> seeded = membershipAutoSeeder.seedFirstUserWhenEmpty(workspace, users);
                if (seeded.isPresent()) {
                    WorkspaceMembership created = seeded.get();
                    log.info(
                            "Auto-added user to workspace: workspaceSlug={}, role={}",
                            LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()),
                            created.getRole());
                    return new MembershipResolution(
                            Set.of(created.getRole()), Set.of(created.getId().getUserId()));
                }
            } catch (IllegalArgumentException ex) {
                log.debug(
                        "Skipped membership auto-add: workspaceSlug={}",
                        LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()),
                        ex);
            }

            log.debug("Returning empty roles: reason=noMembership, workspaceId={}", workspace.getId());
            return MembershipResolution.EMPTY;
        } catch (Exception e) {
            log.warn("Failed to fetch user roles: workspaceId={}", workspace.getId(), e);
            return MembershipResolution.EMPTY;
        }
    }

    private boolean handleSlugRedirect(
            HttpServletRequest request, HttpServletResponse response, String oldSlug, String remainingPath)
            throws IOException {
        var historyOpt = workspaceSlugHistoryRepository.findFirstByOldSlugOrderByChangedAtDesc(oldSlug);
        if (historyOpt.isEmpty()) {
            return false;
        }

        var history = historyOpt.get();
        Instant now = Instant.now();
        if (history.getRedirectExpiresAt() != null
                && history.getRedirectExpiresAt().isBefore(now)) {
            log.debug(
                    "Denied slug redirect: reason=expired, oldSlug={}, expiredAt={}",
                    LoggingUtils.sanitizeForLog(oldSlug),
                    history.getRedirectExpiresAt());
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
            problem.setTitle("Workspace slug expired");
            problem.setDetail("Redirect for this workspace slug has expired");
            problem.setProperty("oldSlug", oldSlug);
            problem.setProperty("expiredAt", history.getRedirectExpiresAt());
            response.setStatus(HttpStatus.GONE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(problem));
            return true;
        }
        var workspace =
                workspaceRepository.findById(history.getWorkspace().getId()).orElse(null);

        if (workspace == null) {
            log.warn(
                    "Skipped slug redirect: reason=missingWorkspace, oldSlug={}", LoggingUtils.sanitizeForLog(oldSlug));
            return false;
        }

        // Avoid leaking workspace existence for private workspaces when the user lacks membership.
        // Checked across all of the account's linked identities (same union semantics as access control).
        boolean isPublic = Boolean.TRUE.equals(workspace.getIsPubliclyViewable());
        Set<Long> currentUserIds = currentAccountUsers.resolve().stream()
                .filter(u -> u != null && u.getId() != null)
                .map(User::getId)
                .collect(Collectors.toSet());
        boolean hasMembership = !currentUserIds.isEmpty()
                && !workspaceMembershipRepository
                        .findByWorkspace_IdAndUser_IdIn(workspace.getId(), currentUserIds)
                        .isEmpty();

        if (!isPublic && !hasMembership) {
            return false;
        }

        String newSlug = history.getNewSlug();
        String suffix = remainingPath == null ? "" : remainingPath;
        String queryString = request.getQueryString();
        String location = request.getContextPath() + "/workspaces/" + newSlug + suffix;
        if (queryString != null && !queryString.isBlank()) {
            location += '?' + queryString;
        }

        log.debug(
                "Redirecting workspace slug: oldSlug={}, newSlug={}",
                LoggingUtils.sanitizeForLog(oldSlug),
                LoggingUtils.sanitizeForLog(newSlug));

        response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());
        response.setHeader(HttpHeaders.LOCATION, location);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(0);
        response.flushBuffer();
        return true;
    }

    /**
     * Send a 404 JSON error response for workspace not found.
     *
     * @param response HTTP response
     * @param slug Workspace slug
     */
    private void sendWorkspaceNotFoundError(HttpServletResponse response, String slug) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Resource not found");
        problem.setDetail("Workspace not found: " + slug);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private void sendWorkspaceSlugValidationError(HttpServletResponse response, String slug) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("Invalid workspace slug: " + slug);
        problem.setProperty(
                "errors",
                Map.of(
                        "workspaceSlug",
                        "Slug must be 3-51 characters, start with a lowercase letter or digit, and contain only lowercase letters, digits, or hyphens"));
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private void sendWorkspaceMembershipForbiddenError(HttpServletResponse response, String slug) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Membership required");
        problem.setDetail("You must be a member of workspace " + slug + " to access this resource.");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private void sendWorkspaceUnauthorizedError(HttpServletResponse response, String slug) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentication required");
        problem.setDetail("You must sign in to access workspace " + slug + ".");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private String extractInvalidSlug(String path) {
        String remainder = path.substring("/workspaces/".length());
        int slashIndex = remainder.indexOf('/');
        if (slashIndex >= 0) {
            remainder = remainder.substring(0, slashIndex);
        }
        return remainder;
    }
}
