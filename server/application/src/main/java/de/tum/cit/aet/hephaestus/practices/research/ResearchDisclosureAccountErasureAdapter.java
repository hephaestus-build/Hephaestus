package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.research.adapter.ResearchDisclosurePersonDataCatalog;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account purge reaches disclosures released after a person request froze its selection. Same policy
 * as {@link ResearchDisclosurePersonDataCatalog}: the link goes, the removal obligation and the key stay.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResearchDisclosureAccountErasureAdapter implements AccountErasureContributor {

    private final ResearchDisclosureRepository disclosures;
    private final NamedParameterJdbcTemplate jdbc;
    private final PrivacyJournal journal;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseAccount(long accountId) {
        unlinkRows(accountId, null);
        disclosures.unlinkCreator(accountId);
    }

    /** Drops the account link and records a removal fact only when an obligation starts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long unlink(long accountId, List<UUID> selected) {
        return unlinkRows(accountId, selected);
    }

    private long unlinkRows(long accountId, @Nullable List<UUID> selected) {
        List<UUID> selectedIds = selected == null ? List.of() : selected;
        var rows = jdbc.query(
                """
                WITH targets AS (
                    SELECT s.id, s.removal_reason IS NULL AS became_owed
                    FROM research_disclosure_subject s
                    JOIN research_disclosure d ON d.id = s.disclosure_id
                    WHERE s.account_id = :account AND (:all OR s.id = ANY(:selected))
                    ORDER BY d.created_at, d.id FOR UPDATE OF s
                )
                UPDATE research_disclosure_subject s
                SET account_id = NULL,
                    removal_required_at = COALESCE(s.removal_required_at, now()),
                    removal_reason = COALESCE(s.removal_reason, 'ACCOUNT_ERASED')
                FROM targets WHERE s.id = targets.id
                RETURNING s.id, s.disclosure_id, s.participant_key, targets.became_owed
                """,
                Map.of(
                        "account",
                        accountId,
                        "all",
                        selected == null,
                        "selected",
                        new SqlArrayValue("uuid", selectedIds.toArray())),
                (rs, row) -> new UnlinkedSubject(
                        Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        Objects.requireNonNull(rs.getObject(2, UUID.class)),
                        Objects.requireNonNull(rs.getString(3)),
                        rs.getBoolean(4)));
        for (var subject : rows) {
            if (subject.becameOwed()) {
                journal.record(
                        subject.id(),
                        PrivacyJournalKind.RESEARCH_REMOVAL_OWED,
                        Map.of(
                                "datasetId",
                                subject.datasetId(),
                                "rowKey",
                                subject.rowKey(),
                                "reason",
                                "ACCOUNT_ERASED"));
            }
        }
        return rows.size();
    }

    private record UnlinkedSubject(UUID id, UUID datasetId, String rowKey, boolean becameOwed) {}
}
