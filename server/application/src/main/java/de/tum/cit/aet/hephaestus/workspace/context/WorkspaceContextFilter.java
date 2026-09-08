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
 * Scopes slugged workspace routes after authentication. Inactive workspaces are hidden except
 * on lifecycle routes that must inspect or change their status.
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
            var workspaceOpt = workspaceRepository.findByWorkspaceSlug(slug);

            if (workspaceOpt.isEmpty()) {
                if (handleSlugRedirect(httpRequest, httpResponse, slug, remainingPath)) {
                    return;
                }
                sendWorkspaceNotFoundError(httpResponse, slug);
                return;
            }

            var workspace = workspaceOpt.get();

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

            var currentUsers = currentAccountUsers.resolve();
            MembershipResolution membership = fetchUserRoles(workspace, currentUsers);
            Set<WorkspaceRole> roles = membership.roles();

            // Instance admins may enter without membership, but elevation never grants ownership.
            if (roles.isEmpty() && SecurityUtils.isSuperAdmin()) {
                log.info(
                        "Granted workspace access via instance-admin elevation: accountId={}, workspaceSlug={}",
                        SecurityUtils.getCurrentAccountId().orElse(null),
                        safeSlug);
                roles = Set.of(WorkspaceRole.ADMIN);
                // Both audit trails read this elevation marker.
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

            Long installationId = connectionService
                    .findActiveGitHubAppConfig(workspace.getId())
                    .map(ConnectionConfig.GitHubAppConfig::installationId)
                    .orElse(null);
            WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, roles, installationId);

            if (WorkspaceContextHolder.getContext() != null) {
                log.warn("Detected context leak: reason=contextAlreadySet, workspaceSlug={}", safeSlug);
            }

            WorkspaceContextHolder.setContext(context);

            // Pin the verified actor id; a session's display login may belong to a different provider.
            resolveWorkspaceIdentity(currentUsers, membership.memberUserIds())
                    .ifPresent(user -> CurrentScmIdentityHolder.set(
                            java.util.Objects.requireNonNull(user.getId()), user.getLogin()));

            log.debug(
                    "Set workspace context: workspaceSlug={}, workspaceId={}, roles={}",
                    safeSlug,
                    context.id(),
                    context.roles());

            chain.doFilter(request, response);
        } finally {
            WorkspaceContextHolder.clearContext();
            WorkspaceElevationContext.clear();
            CurrentScmIdentityHolder.clear();
        }
    }

    /** Roles and eligible actor ids from one membership read, shared by authorization and identity pinning. */
    private record MembershipResolution(Set<WorkspaceRole> roles, Set<Long> memberUserIds) {
        static final MembershipResolution EMPTY = new MembershipResolution(Set.of(), Set.of());
    }

    /** Chooses the first-linked actor that belongs to this workspace, independently of role strength. */
    private Optional<User> resolveWorkspaceIdentity(Collection<User> users, Set<Long> memberUserIds) {
        if (memberUserIds.isEmpty()) {
            return Optional.empty();
        }
        return users.stream()
                .filter(u -> u != null && memberUserIds.contains(u.getId()))
                .findFirst();
    }

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
