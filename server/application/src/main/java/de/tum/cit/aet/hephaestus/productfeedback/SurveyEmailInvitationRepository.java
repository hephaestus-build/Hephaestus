package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SurveyEmailInvitationRepository extends JpaRepository<SurveyEmailInvitation, UUID> {
    @Modifying
    @WorkspaceAgnostic("An instance administrator requests one survey invitation for one native account")
    @Query(value = """
        INSERT INTO product_survey_email_invitation
            (id, survey_id, account_id, workspace_id, requested_by_account_id, requested_at, expires_at, reminder_enabled, request_generation)
        VALUES (:#{#invitation.id}, :#{#invitation.surveyId}, :#{#invitation.accountId}, :#{#invitation.workspaceId},
            :#{#invitation.requestedByAccountId}, :#{#invitation.requestedAt}, :#{#invitation.expiresAt}, :#{#invitation.reminderEnabled}, 0)
        ON CONFLICT (survey_id, account_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("invitation") SurveyEmailInvitation invitation);

    @Modifying
    @WorkspaceAgnostic("An instance administrator explicitly retries a cancelled request for one survey and account")
    @Query(value = """
        UPDATE product_survey_email_invitation SET request_generation = request_generation + 1,
            requested_by_account_id = :#{#invitation.requestedByAccountId}, requested_at = :#{#invitation.requestedAt},
            expires_at = :#{#invitation.expiresAt}, reminder_enabled = :#{#invitation.reminderEnabled},
            cancelled_at = NULL, reminder_requested_at = NULL, reminder_accepted_at = NULL
        WHERE survey_id = :#{#invitation.surveyId} AND account_id = :#{#invitation.accountId}
            AND cancelled_at IS NOT NULL AND accepted_at IS NULL
        """, nativeQuery = true)
    int requeueCancelled(@Param("invitation") SurveyEmailInvitation invitation);

    @WorkspaceAgnostic("An instance administrator previews cancelled initial requests for one survey")
    @Query(
            "SELECT i.accountId FROM SurveyEmailInvitation i WHERE i.surveyId = :surveyId AND i.cancelledAt IS NOT NULL AND i.acceptedAt IS NULL")
    List<Long> requeueableAccountIds(@Param("surveyId") UUID surveyId);

    Optional<SurveyEmailInvitation> findBySurveyIdAndAccountId(UUID surveyId, long accountId);

    @Modifying
    @WorkspaceAgnostic("Survey lifecycle changes cancel only requests belonging to that survey")
    @Query(
            "UPDATE SurveyEmailInvitation i SET i.cancelledAt = :now WHERE i.surveyId = :surveyId AND (i.acceptedAt IS NULL OR (i.reminderEnabled = true AND i.reminderAcceptedAt IS NULL)) AND i.cancelledAt IS NULL")
    int cancelPendingForSurvey(@Param("surveyId") UUID surveyId, @Param("now") Instant now);

    @WorkspaceAgnostic("An instance administrator previews requests for one survey")
    @Query("SELECT i.accountId FROM SurveyEmailInvitation i WHERE i.surveyId = :surveyId")
    List<Long> requestedAccountIds(@Param("surveyId") UUID surveyId);

    long countBySurveyIdAndAcceptedAtIsNotNull(UUID surveyId);

    @Modifying
    @WorkspaceAgnostic("Relay acceptance is recorded for one survey, account and request generation")
    @Query(
            "UPDATE SurveyEmailInvitation i SET i.acceptedAt = :acceptedAt WHERE i.surveyId = :surveyId AND i.accountId = :accountId AND i.acceptedAt IS NULL AND i.requestGeneration = :generation")
    int markAccepted(
            @Param("surveyId") UUID surveyId,
            @Param("accountId") long accountId,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("generation") long generation);

    @WorkspaceAgnostic(
            "The bounded instance scheduler discovers reminders; delivery rechecks current workspace membership")
    @Query(
            "SELECT i FROM SurveyEmailInvitation i WHERE i.reminderEnabled = true AND i.reminderRequestedAt IS NULL AND i.cancelledAt IS NULL AND i.acceptedAt <= :cutoff AND i.expiresAt > :now ORDER BY i.acceptedAt")
    List<SurveyEmailInvitation> findDueReminders(
            @Param("cutoff") Instant cutoff, @Param("now") Instant now, Pageable pageable);

    @Modifying
    @WorkspaceAgnostic("The instance scheduler atomically claims one source-owned invitation")
    @Query(
            "UPDATE SurveyEmailInvitation i SET i.reminderRequestedAt = :now WHERE i.id = :id AND i.reminderRequestedAt IS NULL AND i.cancelledAt IS NULL AND i.reminderEnabled = true")
    int claimReminder(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @WorkspaceAgnostic("The instance scheduler disables one ineligible source-owned reminder")
    @Query("UPDATE SurveyEmailInvitation i SET i.reminderEnabled = false WHERE i.id = :id")
    int disableReminder(@Param("id") UUID id);

    @Modifying
    @WorkspaceAgnostic("Relay acceptance is recorded for one survey, account and reminder generation")
    @Query(
            "UPDATE SurveyEmailInvitation i SET i.reminderAcceptedAt = :acceptedAt WHERE i.surveyId = :surveyId AND i.accountId = :accountId AND i.reminderAcceptedAt IS NULL AND i.requestGeneration = :generation")
    int markReminderAccepted(
            @Param("surveyId") UUID surveyId,
            @Param("accountId") long accountId,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("generation") long generation);

    void deleteAllBySurveyId(UUID surveyId);

    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);

    @Modifying
    @WorkspaceAgnostic("Account erasure removes attribution across the erased account's survey requests")
    @Query("UPDATE SurveyEmailInvitation i SET i.requestedByAccountId = NULL WHERE i.requestedByAccountId = :accountId")
    int eraseRequester(@Param("accountId") long accountId);
}
