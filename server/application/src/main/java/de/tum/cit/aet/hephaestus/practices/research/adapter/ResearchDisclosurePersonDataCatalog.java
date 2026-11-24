package de.tum.cit.aet.hephaestus.practices.research.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchRecoverySubjects;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureAccountErasureAdapter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The research disclosure stores in the person request. Erasure never deletes a subject row: it drops
 * the account link and records that the person's rows must be removed downstream, so the researchers
 * can still be told which key to remove. The grant reference stays, because erasure unlinks that
 * ledger row from the account too.
 */
@Component
@ConditionalOnServerRole
@PersonDataStores({"research_disclosure_subject", "research_disclosure"})
public class ResearchDisclosurePersonDataCatalog implements PersonDataCatalog, ResearchRecoverySubjects {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchDisclosureAccountErasureAdapter subjectErasure;

    public ResearchDisclosurePersonDataCatalog(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            ResearchDisclosureAccountErasureAdapter subjectErasure) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.subjectErasure = subjectErasure;
    }

    @Override
    public List<UUID> releasesFor(long accountId) {
        return jdbc.query(
                "SELECT DISTINCT disclosure_id FROM research_disclosure_subject WHERE account_id = :account",
                Map.of("account", accountId),
                (row, index) -> Objects.requireNonNull(row.getObject(1, UUID.class)));
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "research_disclosure_subject",
                        "research_disclosure_subject",
                        "t.account_id=:account",
                        "id,disclosure_id,workspace_id,participant_key,removal_required_at,removal_reason,removed_at",
                        "id",
                        "account_id=NULL",
                        -140) {
                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        var accountId = frozenScope.accountId();
                        if (selection.rows().isEmpty() || accountId == null) return 0L;
                        List<UUID> ids = selection.rows().stream()
                                .map(row -> UUID.fromString(
                                        Objects.requireNonNull(row.columns().get("id"))))
                                .toList();
                        return subjectErasure.unlink(accountId, ids);
                    }
                },
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "research_disclosure",
                        "research_disclosure",
                        "t.created_by_account_id=:account",
                        "id,workspace_id,purpose,schema_version,window_from,window_to,created_at,use_until,closed_at,closure",
                        "id",
                        "created_by_account_id=NULL",
                        -140));
    }
}
