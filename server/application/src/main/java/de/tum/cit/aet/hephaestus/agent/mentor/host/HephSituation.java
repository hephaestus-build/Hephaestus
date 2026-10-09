package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorSituation;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The situation of a Heph turn ({@link MentorSituation}) with two facts Heph must not work out itself: who the
 * developer is in every system it reads, so their own messages and work are never taken for someone else's, and the
 * calendar days of today, this week and last week in their time zone, so no answer depends on date arithmetic.
 */
@Component
@ConditionalOnWorkerRole
@Profile(HephHostConfiguration.PROFILES)
@RequiredArgsConstructor
public class HephSituation {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ENGLISH);
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ENGLISH);
    private static final String CLOSE = "</situation>";

    private final MentorSituation situations;
    private final UserRepository users;
    private final AccountIdentityQuery identities;
    private final IdentityProviderRepository providers;
    private final IntegrationManifestRegistry manifests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public String render(TurnJwt turn, ZoneId zone) {
        String situation = situations.render(turn.workspaceId(), turn.turnId(), zone);
        int close = situation.lastIndexOf(CLOSE);
        return situation.substring(0, close) + identity(turn.userId()) + calendar(zone) + situation.substring(close);
    }

    private String identity(long developerId) {
        Optional<User> found = users.findById(developerId);
        if (found.isEmpty()) {
            return "";
        }
        User developer = found.get();
        String name = developer.getName() == null || developer.getName().isBlank()
                ? developer.getLogin()
                : developer.getName();
        Set<String> accounts = new LinkedHashSet<>();
        accounts.add(label(developer.getProvider()) + " `" + developer.getLogin() + "`");
        Long providerId = developer.getProvider().getId();
        if (providerId != null) {
            identities
                    .resolveActiveAccountId(providerId, developer.getNativeId().toString(), null)
                    .ifPresent(accountId -> identities
                            .activeLinksForAccount(accountId)
                            .forEach(link -> {
                                String handle =
                                        link.usernameAtSignup() != null ? link.usernameAtSignup() : link.displayName();
                                if (handle != null && !handle.isBlank()) {
                                    providers
                                            .findById(link.gitProviderId())
                                            .ifPresent(provider -> accounts.add(label(provider) + " `" + handle + "`"));
                                }
                            }));
        }
        return "You are talking to " + name + ". Their accounts: " + String.join(", ", accounts) + ". "
                + "Context files mark their own work and words: Slack messages with `byYou: true`, comments with "
                + "`authorRelation: WORK_AUTHOR` on their own work, and every list of their work. Never speak of them "
                + "in the third person.\n";
    }

    private String calendar(ZoneId zone) {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String offset = OFFSET.format(clock.instant().atZone(zone));
        return "Calendar in " + zone + " (" + offset + "), the zone of every time in the context files: today is "
                + DAY.format(today) + ", yesterday was "
                + DAY.format(today.minusDays(1)) + ". This week runs " + DAY.format(monday) + " to "
                + DAY.format(monday.plusDays(6)) + "; last week ran " + DAY.format(monday.minusWeeks(1)) + " to "
                + DAY.format(monday.minusDays(1)) + ". Use these days; do not compute others.\n";
    }

    private String label(@Nullable IdentityProvider provider) {
        return provider == null ? "Account" : manifests.displayName(provider.kind());
    }
}
