package de.tum.cit.aet.hephaestus.integration.slack.identity;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipRoster;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called only after the provider adapter confirms a human sender and source consent. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackRosterIdentityResolver {
    private final GitProviderRegistry providers;
    private final PeopleRegistry people;
    private final MembershipRoster memberships;
    private final SlackMessageService slack;
    private final PersonProcessingSuppression suppression;

    public SlackRosterIdentityResolver(
            GitProviderRegistry providers,
            PeopleRegistry people,
            MembershipRoster memberships,
            SlackMessageService slack,
            PersonProcessingSuppression suppression) {
        this.providers = providers;
        this.people = people;
        this.memberships = memberships;
        this.slack = slack;
        this.suppression = suppression;
    }

    /**
     * The name Slack holds for a human sender, or empty for a suppressed or non-human one. It asks Slack, which can wait
     * out a rate limit, so it runs with no transaction open.
     */
    public Optional<String> displayName(long workspaceId, String teamId, String subject) {
        if (teamId.isBlank() || subject.isBlank()) return Optional.empty();
        long providerId = providers.resolveProviderId("SLACK", "https://slack.com");
        if (suppression.isSuppressed(providerId, subject, teamId)) return Optional.empty();
        return slack.humanDisplayName(workspaceId, teamId, subject);
    }

    /**
     * The workspace account of a human sender whose name Slack returned. It checks the suppression again, because it
     * can change while Slack answers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> humanAccountId(long workspaceId, String teamId, String subject, String name) {
        long providerId = providers.resolveProviderId("SLACK", "https://slack.com");
        if (suppression.isSuppressed(providerId, subject, teamId)) return Optional.empty();
        long accountId = people.ensureHuman(new PersonIdentity(providerId, subject, teamId), name);
        return memberships.ensureMember(workspaceId, accountId) ? Optional.of(accountId) : Optional.empty();
    }
}
