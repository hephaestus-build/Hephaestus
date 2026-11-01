package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.mentor.MentorSituation;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@Profile(ConversationAssignments.PROFILES)
@RequiredArgsConstructor
public class ConversationSituation {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ENGLISH);
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ENGLISH);
    private static final String CLOSE = "</situation>";

    private final MentorSituation situations;
    private final AccountDirectory accounts;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry profiles;
    private final IdentityProviderRepository providers;
    private final IntegrationManifestRegistry manifests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public String render(TurnAttempt turn, ZoneId zone) {
        String situation = situations.render(turn.workspaceId(), turn.turnId(), zone);
        int close = situation.lastIndexOf(CLOSE);
        return situation.substring(0, close) + identity(turn.accountId()) + calendar(zone) + situation.substring(close);
    }

    private String identity(long accountId) {
        Optional<AccountDirectory.AccountView> account = accounts.find(accountId);
        if (account.isEmpty()) {
            return "";
        }
        List<AccountIdentityQuery.IdentityView> links = identities.activeLinksForAccount(accountId);
        Map<GitProviderRegistry.IdentityKey, GitProviderRegistry.IdentityProfile> known =
                profiles.profiles(links.stream()
                        .map(link -> new GitProviderRegistry.IdentityKey(
                                link.providerId(), link.providerUserKey(), link.teamKey()))
                        .toList());
        Set<String> handles = new LinkedHashSet<>();
        for (AccountIdentityQuery.IdentityView link : links) {
            GitProviderRegistry.IdentityProfile profile = known.get(
                    new GitProviderRegistry.IdentityKey(link.providerId(), link.providerUserKey(), link.teamKey()));
            String handle = profile == null ? null : profile.login() != null ? profile.login() : profile.displayName();
            if (handle != null && !handle.isBlank()) {
                providers
                        .findById(link.providerId())
                        .ifPresent(provider -> handles.add(label(provider) + " `" + handle + "`"));
            }
        }
        return "You are talking to " + account.get().displayName() + "."
                + (handles.isEmpty() ? "" : " Their accounts: " + String.join(", ", handles) + ".") + " "
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
