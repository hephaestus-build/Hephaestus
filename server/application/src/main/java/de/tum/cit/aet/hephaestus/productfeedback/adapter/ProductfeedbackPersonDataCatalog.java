package de.tum.cit.aet.hephaestus.productfeedback.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "product_feedback",
    "product_feedback_resolver",
})
public class ProductfeedbackPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback",
                        "product_feedback",
                        "t.account_id = :account",
                        "id,account_id,workspace_id,kind,message,page_path,created_at,submission_minute,app_version,user_agent,resolved_at,resolved_by_account_id",
                        "id",
                        "",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback_resolver",
                        "product_feedback",
                        "t.resolved_by_account_id = :account",
                        "id,kind,resolved_at,resolved_by_account_id",
                        "id",
                        "resolved_by_account_id=NULL",
                        400));
    }
}
