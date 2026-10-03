package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Raw Git history is an upstream cache, not a person-editable application record. */
final class GitRepositoryHistoryPersonDataStore extends JdbcPersonDataStore {
    GitRepositoryHistoryPersonDataStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        super(
                jdbc,
                mapper,
                "git_repository_history",
                "repository",
                """
                t.id IN (SELECT repository_id FROM git_commit
                    WHERE author_id=ANY(:users) OR committer_id=ANY(:users)
                    OR id IN (SELECT commit_id FROM commit_contributor WHERE user_id=ANY(:users)))
                """,
                "id,provider_id,native_id,name_with_owner,html_url",
                "id",
                "",
                190);
    }

    /** The person job cancels affected attempts and erases their folders; upstream history stays intact. */
    @Override
    public long erase(PersonDataSelection selection) {
        return 0;
    }
}
