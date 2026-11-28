package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredMessageDeletions;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredRecordDeletions;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredTeamWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("erasure-recovery")
@ConditionalOnServerRole
final class RestoredOwnerContent {
    private static final int PAGE_SIZE = 256;

    private final JdbcTemplate jdbc;
    private final RestoredMessageDeletions messages;
    private final RestoredRecordDeletions records;
    private final RestoredTeamWork teamWork;

    RestoredOwnerContent(
            JdbcTemplate jdbc,
            RestoredMessageDeletions messages,
            RestoredRecordDeletions records,
            RestoredTeamWork teamWork) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.records = records;
        this.teamWork = teamWork;
    }

    void withdrawAgain(
            List<PersonDataRecovery.TeamVersionWithdrawal> versions,
            List<PersonDataRecovery.WorkspaceDeparture> departures) {
        for (var version : versions) teamWork.withdrawAgain(version.workspaceId(), version.versionId());
        for (var departure : departures)
            teamWork.withdrawDepartedAgain(departure.workspaceId(), departure.accountId());
    }

    void deleteAgain(List<PersonDataRecovery.MessageDeletion> deletedMessages, Set<UUID> recordIds) {
        for (var message : deletedMessages) messages.deleteAgain(message.workspaceId(), message.entryId());
        if (recordIds.isEmpty()) return;

        // The manifest has goal IDs only. The owning adapter checks them under each account's row policy.
        long afterId = 0L;
        while (true) {
            var page = jdbc.query(
                    "SELECT id FROM account WHERE id > ? ORDER BY id LIMIT ?",
                    (rs, row) -> rs.getLong(1),
                    afterId,
                    PAGE_SIZE);
            if (page.isEmpty()) return;
            for (long accountId : page) records.deleteAgain(accountId, recordIds);
            afterId = page.getLast();
        }
    }
}
