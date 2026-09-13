package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.notification.SurveyEmailDelivery;
import de.tum.cit.aet.hephaestus.productfeedback.notification.SurveyEmailInvitations;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic(
        "Instance administrators request invitations for surveys; eligibility resolves each recipient's current workspace")
class SurveyEmailInvitationService implements SurveyEmailInvitations {
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final SurveyRepository surveys;
    private final SurveyService surveyService;
    private final SurveyEmailInvitationRepository invitations;
    private final SurveyEmailDelivery delivery;
    private final AccountWorkspaceMembershipQuery memberships;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SurveyEmailInvitationSummaryDTO preview(UUID surveyId) {
        Survey survey = require(surveyId);
        var eligible = eligibleAccountIds(survey);
        var requested = new HashSet<>(invitations.requestedAccountIds(surveyId));
        var requeueable = new HashSet<>(invitations.requeueableAccountIds(surveyId));
        int remaining = (int) eligible.stream()
                .filter(id -> !requested.contains(id) || requeueable.contains(id))
                .count();
        return summary(surveyId, eligible.size(), requested.size(), 0, remaining);
    }

    @Transactional
    public SurveyEmailInvitationSummaryDTO invite(UUID surveyId, long actorId, boolean sendReminder) {
        Survey survey = require(surveyId);
        var eligible = eligibleAccountIds(survey);
        var requested = new HashSet<>(invitations.requestedAccountIds(surveyId));
        var requeueable = new HashSet<>(invitations.requeueableAccountIds(surveyId));
        Instant now = clock.instant();
        Instant expiresAt = now.plus(MAX_AGE);
        if (survey.getEndsAt() != null && survey.getEndsAt().isBefore(expiresAt)) {
            expiresAt = survey.getEndsAt();
        }
        int queued = 0;
        for (long accountId : eligible) {
            if ((requested.contains(accountId) && !requeueable.contains(accountId)) || queued == MAX_BATCH_SIZE)
                continue;
            var request = new SurveyEmailInvitation(survey, accountId, actorId, now, expiresAt, sendReminder);
            int changed = requested.contains(accountId)
                    ? invitations.requeueCancelled(request)
                    : invitations.insertIfAbsent(request);
            if (changed == 1) {
                long generation = invitations
                        .findBySurveyIdAndAccountId(surveyId, accountId)
                        .orElseThrow()
                        .getRequestGeneration();
                delivery.request(surveyId, accountId, expiresAt, false, generation);
                queued++;
            }
            requeueable.remove(accountId);
            requested.add(accountId);
        }
        int remaining = (int) eligible.stream()
                .filter(id -> !requested.contains(id) || requeueable.contains(id))
                .count();
        return summary(surveyId, eligible.size(), requested.size(), queued, remaining);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> eligibleInvitation(UUID surveyId, long accountId, boolean reminder, long generation) {
        return invitations
                .findBySurveyIdAndAccountId(surveyId, accountId)
                .filter(invitation ->
                        invitation.getRequestGeneration() == generation && invitation.getCancelledAt() == null)
                .filter(invitation -> reminder
                        ? invitation.isReminderEnabled()
                                && invitation.getReminderRequestedAt() != null
                                && invitation.getReminderAcceptedAt() == null
                        : invitation.getAcceptedAt() == null)
                .filter(invitation -> clock.instant().isBefore(invitation.getExpiresAt()))
                .flatMap(invitation -> eligibleForSurvey(surveyId, accountId));
    }

    private Optional<Invitation> eligibleForSurvey(UUID surveyId, long accountId) {
        Survey survey = surveys.findById(surveyId).orElse(null);
        if (survey == null) return Optional.empty();
        return memberships.membershipsForAccount(accountId).stream()
                .sorted(Comparator.comparing(AccountWorkspaceMembershipQuery.WorkspaceMembershipView::workspaceId))
                .filter(workspace -> surveyService.isEligibleForEmail(surveyId, workspace.workspaceId(), accountId))
                .findFirst()
                .map(workspace ->
                        new Invitation(survey.getPurpose() == Survey.Purpose.RESEARCH, workspace.workspaceSlug()));
    }

    @Override
    @Transactional
    public void markAccepted(UUID surveyId, long accountId, Instant acceptedAt, boolean reminder, long generation) {
        if (reminder) invitations.markReminderAccepted(surveyId, accountId, acceptedAt, generation);
        else invitations.markAccepted(surveyId, accountId, acceptedAt, generation);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Summary> endedSummary(UUID surveyId) {
        return surveys.findById(surveyId).flatMap(survey -> {
            Instant endedAt = survey.getEndsAt();
            if (endedAt == null || endedAt.isAfter(clock.instant())) return Optional.empty();
            var counts = surveyService.get(surveyId).participation();
            return Optional.of(new Summary(endedAt, counts.invited(), counts.responded(), counts.declined()));
        });
    }

    @Transactional
    public void scheduleReminders() {
        Instant now = clock.instant();
        for (var invitation :
                invitations.findDueReminders(now.minus(Duration.ofHours(72)), now, PageRequest.of(0, MAX_BATCH_SIZE))) {
            var eligible = eligibleForSurvey(invitation.getSurveyId(), invitation.getAccountId());
            if (eligible.isEmpty()
                    || !delivery.isSubscribed(
                            invitation.getAccountId(), eligible.get().research())) {
                invitations.disableReminder(invitation.getId());
            } else if (invitations.claimReminder(invitation.getId(), now) == 1) {
                delivery.request(
                        invitation.getSurveyId(),
                        invitation.getAccountId(),
                        invitation.getExpiresAt(),
                        true,
                        invitation.getRequestGeneration());
            }
        }
    }

    @Transactional
    public void scheduleSummaries() {
        Instant now = clock.instant();
        for (Survey survey :
                surveys.findEndedWithoutSummary(now, now.minus(MAX_AGE), PageRequest.of(0, MAX_BATCH_SIZE))) {
            if (surveys.claimSummary(survey.getId(), java.util.Objects.requireNonNull(survey.getEndsAt()), now) == 1) {
                delivery.requestSummary(
                        survey.getId(),
                        java.util.Objects.requireNonNull(survey.getEndsAt()),
                        survey.getEndsAt().plus(MAX_AGE));
            }
        }
    }

    private List<Long> eligibleAccountIds(Survey survey) {
        return delivery.subscribedAccountIds(survey.getPurpose() == Survey.Purpose.RESEARCH).stream()
                .distinct()
                .sorted()
                .filter(id -> eligibleForSurvey(survey.getId(), id).isPresent())
                .toList();
    }

    private Survey require(UUID surveyId) {
        return surveys.findById(surveyId).orElseThrow(() -> new EntityNotFoundException("Survey", surveyId.toString()));
    }

    private SurveyEmailInvitationSummaryDTO summary(
            UUID surveyId, int eligible, int requested, int queued, int remaining) {
        return new SurveyEmailInvitationSummaryDTO(
                eligible,
                requested,
                Math.toIntExact(invitations.countBySurveyIdAndAcceptedAtIsNotNull(surveyId)),
                queued,
                remaining);
    }
}
