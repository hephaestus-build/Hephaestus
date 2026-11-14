package de.tum.cit.aet.hephaestus.integration.slack.preferences;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackUserPreferencesService {

    static final String SLACK_SERVER_URL = "https://slack.com";

    private final AccountIdentityQuery accountIdentityQuery;
    private final AccountMembershipQuery membershipQuery;
    private final GitProviderRegistry gitProviderRegistry;
    private final ConnectionService connectionRepository;
    private final SlackParticipantConsentRepository participantConsentRepository;
    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackParticipantConsentService participantConsentService;
    private final SlackPersonErasureService erasureService;
    private final WorkspaceSummaryQuery workspaceSummaryQuery;

    public SlackUserPreferencesService(
            AccountIdentityQuery accountIdentityQuery,
            AccountMembershipQuery membershipQuery,
            GitProviderRegistry gitProviderRegistry,
            ConnectionService connectionRepository,
            SlackParticipantConsentRepository participantConsentRepository,
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackParticipantConsentService participantConsentService,
            SlackPersonErasureService erasureService,
            WorkspaceSummaryQuery workspaceSummaryQuery) {
        this.accountIdentityQuery = accountIdentityQuery;
        this.membershipQuery = membershipQuery;
        this.gitProviderRegistry = gitProviderRegistry;
        this.connectionRepository = connectionRepository;
        this.participantConsentRepository = participantConsentRepository;
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.participantConsentService = participantConsentService;
        this.erasureService = erasureService;
        this.workspaceSummaryQuery = workspaceSummaryQuery;
    }

    @Transactional(readOnly = true)
    public SlackUserPreferencesDTO listForAccount(long accountId) {
        long slackProviderId = slackProviderId();
        List<AccountIdentityQuery.IdentityView> links = accountIdentityQuery.activeLinksForAccount(accountId);
        Map<String, AccountIdentityQuery.IdentityView> slackLinksByTeam = slackLinksByTeam(links, slackProviderId);
        if (slackLinksByTeam.isEmpty()) {
            return new SlackUserPreferencesDTO(List.of());
        }

        Set<Long> workspaceIds = accessibleWorkspaceIds(accountId);
        if (workspaceIds.isEmpty()) {
            return new SlackUserPreferencesDTO(List.of());
        }

        List<SlackUserWorkspacePreferencesDTO> workspaces = workspaceIds.stream()
                .flatMap(workspaceId -> connectionRepository.findActive(workspaceId, IntegrationKind.SLACK).stream())
                .filter(connection -> hasText(connection.getInstanceKey()))
                .filter(connection -> slackLinksByTeam.containsKey(Objects.requireNonNull(connection.getInstanceKey())))
                .filter(connection -> workspaceIds.contains(connection.toRef().workspaceId()))
                .flatMap(connection -> {
                    String teamId = Objects.requireNonNull(connection.getInstanceKey());
                    return toDto(connection, Objects.requireNonNull(slackLinksByTeam.get(teamId)), null).stream();
                })
                .sorted(Comparator.comparing(SlackUserWorkspacePreferencesDTO::workspaceName))
                .toList();
        return new SlackUserPreferencesDTO(workspaces);
    }

    @Transactional
    public SlackUserWorkspacePreferencesDTO updateChannelMessagesAllowed(
            long workspaceId, long accountId, boolean channelMessagesAllowed) {
        Connection connection = connectionRepository
                .findActive(workspaceId, IntegrationKind.SLACK)
                .orElseThrow(() -> notFound("Slack is not connected for this workspace."));
        String teamId = connection.getInstanceKey();
        if (!hasText(teamId)) {
            throw notFound("Slack is not connected for this workspace.");
        }
        String requiredTeamId = Objects.requireNonNull(teamId);

        AccountIdentityQuery.IdentityView slackLink = accountIdentityQuery.activeLinksForAccount(accountId).stream()
                .filter(link -> Objects.equals(link.providerId(), slackProviderId()))
                .filter(link -> Objects.equals(link.teamKey(), requiredTeamId))
                .filter(link -> hasText(link.providerUserKey()))
                .findFirst()
                .orElseThrow(() -> notFound("Slack account is not linked for this workspace."));

        String slackUserId = slackLink.providerUserKey();
        Instant decidedAt = Instant.now();
        boolean recorded = channelMessagesAllowed
                ? participantConsentService.recordChannelMessageOptIn(workspaceId, slackUserId, decidedAt)
                : participantConsentService.recordChannelMessageOptOut(workspaceId, slackUserId, decidedAt);
        if (recorded && !channelMessagesAllowed) {
            erasureService.erasePerson(workspaceId, accountId, slackUserId);
        }
        // A choice made in Slack with a later time keeps its place, so the answer shows what is stored.
        boolean allowed = recorded
                ? channelMessagesAllowed
                : participantConsentService.channelMessagesAllowed(workspaceId, slackUserId);
        return toDto(connection, slackLink, allowed).orElseThrow(() -> notFound("Workspace is not available."));
    }

    private Map<String, AccountIdentityQuery.IdentityView> slackLinksByTeam(
            List<AccountIdentityQuery.IdentityView> links, long slackProviderId) {
        return links.stream()
                .filter(link -> Objects.equals(link.providerId(), slackProviderId))
                .filter(link -> hasText(link.teamKey()))
                .collect(Collectors.toMap(
                        link -> Objects.requireNonNull(link.teamKey()),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private Set<Long> accessibleWorkspaceIds(long accountId) {
        return membershipQuery.membershipsForAccount(accountId).stream()
                .map(AccountMembershipQuery.MembershipView::workspaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Optional<SlackUserWorkspacePreferencesDTO> toDto(
            Connection connection,
            AccountIdentityQuery.IdentityView slackLink,
            @Nullable Boolean channelMessagesAllowed) {
        long workspaceId = connection.toRef().workspaceId();
        return workspaceSummaryQuery
                .findById(workspaceId)
                .map(workspace -> toDto(connection, slackLink, channelMessagesAllowed, workspace));
    }

    private SlackUserWorkspacePreferencesDTO toDto(
            Connection connection,
            AccountIdentityQuery.IdentityView slackLink,
            @Nullable Boolean channelMessagesAllowed,
            WorkspaceSummaryQuery.WorkspaceSummary workspace) {
        long workspaceId = workspace.id();
        String slackUserId = slackLink.providerUserKey();
        boolean allowed = channelMessagesAllowed != null
                ? channelMessagesAllowed
                : !participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, slackUserId);
        return new SlackUserWorkspacePreferencesDTO(
                workspace.slug(),
                workspace.displayName(),
                Objects.requireNonNull(connection.getInstanceKey()),
                connection.getDisplayName(),
                allowed,
                monitoredChannelRepository.countByWorkspaceIdAndConsentState(workspaceId, ConsentState.ACTIVE));
    }

    private long slackProviderId() {
        return gitProviderRegistry.resolveProviderId("SLACK", SLACK_SERVER_URL);
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
