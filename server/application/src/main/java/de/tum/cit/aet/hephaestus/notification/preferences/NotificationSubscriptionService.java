package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Subscriptions belong to native accounts, not workspaces")
@Transactional(readOnly = true)
public class NotificationSubscriptionService {
    private final NotificationSubscriptionRepository subscriptions;
    private final AccountContactQuery contacts;
    private final EmailGateway gateway;
    private final java.time.Clock clock;

    public boolean isEnabled(long accountId, NotificationSubscriptionKind kind) {
        return subscriptions
                .findByAccountIdAndKind(accountId, kind)
                .map(NotificationSubscription::isEnabled)
                .orElse(false);
    }

    public List<Long> subscribedAccountIds(NotificationSubscriptionKind kind) {
        return subscriptions.subscribedAccountIds(kind);
    }

    public Optional<String> unsubscribeToken(long accountId, NotificationSubscriptionKind kind) {
        return subscriptions
                .findByAccountIdAndKind(accountId, kind)
                .filter(NotificationSubscription::isEnabled)
                .map(s -> s.getUnsubscribeToken().toString());
    }

    /** A later opt-in must not revive an older optional request. */
    public Optional<String> unsubscribeToken(
            long accountId, NotificationSubscriptionKind kind, java.time.Instant requestedAt) {
        return subscriptions
                .findByAccountIdAndKind(accountId, kind)
                .filter(NotificationSubscription::isEnabled)
                .filter(row ->
                        row.getEnabledSince() != null && !row.getEnabledSince().isAfter(requestedAt))
                .map(row -> row.getUnsubscribeToken().toString());
    }

    @Transactional
    public void unsubscribe(String token) {
        UUID id;
        try {
            id = UUID.fromString(token);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        subscriptions.findByUnsubscribeToken(id).ifPresent(s -> s.setEnabled(false, clock.instant()));
    }

    public NotificationPreferencesDTO get(long accountId) {
        return view(accountId, subscriptions.findAllByAccountIdOrderByKind(accountId));
    }

    @Transactional
    public NotificationPreferencesDTO update(
            long accountId,
            UpdateNotificationPreferencesDTO update,
            EntityTagPrecondition precondition,
            boolean admin) {
        List<NotificationSubscription> rows = new ArrayList<>(subscriptions.findForUpdate(accountId));
        boolean enablingSubscription = Arrays.stream(NotificationSubscriptionKind.values())
                .anyMatch(kind -> requested(update, kind) && !enabled(rows, kind));
        if (enablingSubscription
                && contacts.activeVerifiedPrimaryEmail(accountId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A verified email address on an active account is required");
        }
        if (update.productFeedback() && !admin && !enabled(rows, NotificationSubscriptionKind.PRODUCT_FEEDBACK)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Product feedback notifications require instance administration");
        }
        if (update.surveySummaries() && !admin && !enabled(rows, NotificationSubscriptionKind.SURVEY_SUMMARIES)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Survey summaries require instance administration");
        }
        if (!precondition.matches(version(rows))) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "Notification preferences changed; reload before saving");
        }
        for (NotificationSubscriptionKind kind : NotificationSubscriptionKind.values()) {
            NotificationSubscription row =
                    rows.stream().filter(s -> s.getKind() == kind).findFirst().orElse(null);
            if (row == null) {
                row = new NotificationSubscription(accountId, kind);
                rows.add(row);
            }
            row.setEnabled(requested(update, kind), clock.instant());
        }
        subscriptions.saveAllAndFlush(rows);
        return view(accountId, rows);
    }

    private NotificationPreferencesDTO view(long accountId, List<NotificationSubscription> rows) {
        return new NotificationPreferencesDTO(
                enabled(rows, NotificationSubscriptionKind.PRODUCT_FEEDBACK),
                enabled(rows, NotificationSubscriptionKind.PRODUCT_SURVEYS),
                enabled(rows, NotificationSubscriptionKind.RESEARCH_SURVEYS),
                contacts.activeVerifiedPrimaryEmail(accountId).isPresent(),
                gateway.configured(),
                enabled(rows, NotificationSubscriptionKind.SURVEY_SUMMARIES),
                enabled(rows, NotificationSubscriptionKind.WORKSPACE_ALERTS),
                EntityTagPrecondition.format(version(rows)));
    }

    private static boolean requested(UpdateNotificationPreferencesDTO update, NotificationSubscriptionKind kind) {
        return switch (kind) {
            case PRODUCT_FEEDBACK -> update.productFeedback();
            case PRODUCT_SURVEYS -> update.productSurveys();
            case RESEARCH_SURVEYS -> update.researchSurveys();
            case WORKSPACE_ALERTS -> update.workspaceAlerts();
            case SURVEY_SUMMARIES -> update.surveySummaries();
        };
    }

    private static boolean enabled(List<NotificationSubscription> rows, NotificationSubscriptionKind kind) {
        return rows.stream().anyMatch(s -> s.getKind() == kind && s.isEnabled());
    }

    private static String version(List<NotificationSubscription> rows) {
        return Arrays.stream(NotificationSubscriptionKind.values())
                .map(kind -> rows.stream()
                        .filter(s -> s.getKind() == kind)
                        .map(s -> Long.toString(s.getVersion()))
                        .findFirst()
                        .orElse("-1"))
                .collect(Collectors.joining("."));
    }
}
