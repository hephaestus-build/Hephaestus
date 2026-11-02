package de.tum.cit.aet.hephaestus.productfeedback.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.CleanupRecords;
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
    "product_feedback_filing",
    "product_feedback_consent",
    "product_feedback_destination",
})
public class ProductfeedbackPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CleanupRecords cleanups;

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
                        400),
                // Before the issue locators go, erasure keeps what it needs to find and scrub the issues on GitHub.
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback_filing",
                        "product_feedback_filing",
                        "t.feedback_id IN (SELECT p.id FROM product_feedback p WHERE p.account_id = :account)",
                        "feedback_id,state,route,reason,allow_public,mention_login,repository,issue_number,issue_url,created_at",
                        "feedback_id",
                        "",
                        399) {
                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        Long account = frozenScope.accountId();
                        if (account != null) cleanups.keepForAccount(account);
                        return super.erase(selection, frozenScope);
                    }
                },
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback_consent",
                        "product_feedback_consent",
                        "t.account_id = :account",
                        "id,account_id,allow_public,mention,notice_version,created_at",
                        "id",
                        "",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "product_feedback_destination",
                        "product_feedback_destination",
                        "t.updated_by_account_id = :account",
                        "id,updated_at,updated_by_account_id",
                        "id",
                        "updated_by_account_id=NULL",
                        400));
    }
}
