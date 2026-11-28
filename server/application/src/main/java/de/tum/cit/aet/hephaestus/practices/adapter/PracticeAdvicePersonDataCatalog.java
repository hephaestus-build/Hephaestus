package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.LiveJdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The developer's own advice (ADR 0160). The stores select only the developer's rows, so another person's export never
 * holds them.
 *
 * <p>The stores are live: advice written between the preview and the request goes too. They run after the result step
 * (order -150), which deletes the results that the advice cites.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"practice_advice_receipt", "practice_advice_source", "practice_advice_revision", "practice_advice"})
@ErasesColumns({
    "practice_advice_receipt.person_account_id",
    "practice_advice_source.person_account_id",
    "practice_advice_revision.person_account_id",
    "practice_advice.person_account_id"
})
public class PracticeAdvicePersonDataCatalog implements PersonDataCatalog {
    private static final String OWN = "t.person_account_id=:account";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                // The decision of each review, without its content digest: the digest only matches a retry.
                new LiveJdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "practice_advice_receipt",
                        "practice_advice_receipt",
                        OWN,
                        "advice_id,workspace_id,review_id,kind,version,written_at",
                        "advice_id,review_id,workspace_id",
                        "",
                        -140),
                new LiveJdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "practice_advice_source",
                        "practice_advice_source",
                        OWN,
                        "id,workspace_id,revision_id,result_id,problem_id,cited,ordinal",
                        "id,workspace_id",
                        "",
                        -139),
                // Only the developer reads advice, so the person-data records hold no text (ADR 0160).
                new LiveJdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "practice_advice_revision",
                        "practice_advice_revision",
                        OWN,
                        "id,workspace_id,advice_id,number,review_id,written_at,stale_at",
                        "id,workspace_id",
                        "",
                        -138),
                new LiveJdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "practice_advice",
                        "practice_advice",
                        OWN,
                        "id,workspace_id,practice_id,state,retire_reason,retired_at,dismissed_at,dismissed_items,version,latest_revision,created_at",
                        "id,workspace_id",
                        "",
                        -137));
    }
}
