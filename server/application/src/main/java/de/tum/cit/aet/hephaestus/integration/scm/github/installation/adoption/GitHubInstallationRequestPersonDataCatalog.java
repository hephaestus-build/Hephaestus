package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A personal account's installation that no admin adopted is the person's data alone, so erasure removes it. An
 * adopted one stays, because it stops a replayed event from creating another workspace, but erasure clears the
 * person's login and id from it, as the workspace purge does. Export lists both. The admin who decided keeps the
 * decision, without the reference to their account.
 */
@Component
@ConditionalOnServerRole
@PersonDataStores({
    "github_installation_request",
    "github_installation_request_adopted",
    "github_installation_request_decider"
})
public class GitHubInstallationRequestPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GitHubInstallationRequestPersonDataCatalog(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The person's own GitHub account's installations, matched by its exact native identity. */
    private static String ownInstallations(String status) {
        return status + """
                 AND t.account_kind = 'USER'
                 AND EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb))
                        AS i("providerId" bigint, subject text, "teamId" text)
                    JOIN identity_provider p ON p.id = i."providerId"
                    WHERE p.type = 'GITHUB' AND p.server_url = 'https://github.com'
                      AND i.subject = t.account_id::text)
                """;
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "github_installation_request",
                        "github_installation_request",
                        ownInstallations("t.status <> 'ADOPTED'"),
                        "installation_id,account_id,account_login,account_kind,status,received_at,decided_at",
                        "installation_id",
                        "",
                        196),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "github_installation_request_adopted",
                        "github_installation_request",
                        ownInstallations("t.status = 'ADOPTED'"),
                        "installation_id,account_id,account_login,account_kind,status,received_at,decided_at",
                        "installation_id",
                        "account_login=NULL,account_id=NULL",
                        196),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "github_installation_request_decider",
                        "github_installation_request",
                        "t.decided_by_account_id = :account",
                        "installation_id,status,decided_at,decided_by_account_id",
                        "installation_id",
                        "decided_by_account_id=NULL",
                        400));
    }
}
