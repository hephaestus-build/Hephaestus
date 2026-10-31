package de.tum.cit.aet.hephaestus.integration.scm.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The connections of workspaces installed on the person's own provider account. Their login is the namespace of the
 * workspace's repositories, like a repository's {@code owner/name}, so it stays until the installation is removed or
 * the workspace is purged.
 */
final class InstallationAccountPersonDataStore extends JdbcPersonDataStore {
    InstallationAccountPersonDataStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        super(
                jdbc,
                mapper,
                "installation_account",
                "connection",
                """
                t.kind IN ('GITHUB','GITLAB') AND EXISTS (SELECT 1 FROM workspace w
                    JOIN identity_provider p ON p.type=t.kind AND p.server_url=COALESCE(t.config->>'serverUrl',
                        CASE t.kind WHEN 'GITHUB' THEN '%s' WHEN 'GITLAB' THEN '%s' END)
                    JOIN "user" u ON u.provider_id=p.id
                    WHERE w.id=t.workspace_id AND w.account_type='USER' AND u.id=ANY(:users)
                      AND lower(u.login)=lower(w.account_login))
                """.formatted(PersonSourceNamespace.GITHUB_DEFAULT_URL, PersonSourceNamespace.GITLAB_DEFAULT_URL),
                "id,workspace_id,kind,display_name",
                "id",
                "",
                195);
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        return 0;
    }
}
