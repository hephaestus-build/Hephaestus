package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"activity_event"})
public class ActivityPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(new JdbcPersonDataStore(
                jdbc,
                mapper,
                "activity_event",
                "activity_event",
                """
                t.actor_id = ANY(:users) OR (t.actor_id IS NULL AND (
                    (t.target_type='commit' AND t.target_id IN (
                        SELECT id FROM git_commit WHERE author_id=ANY(:users)
                        UNION SELECT commit_id FROM commit_contributor WHERE user_id=ANY(:users) AND role IN ('AUTHOR','CO_AUTHOR')))
                    OR (t.target_type IN ('issue','pull_request') AND t.target_id IN (
                        SELECT id FROM issue WHERE author_id=ANY(:users)))
                    OR (t.target_type='issue_comment' AND t.target_id IN (
                        SELECT id FROM issue_comment WHERE author_id=ANY(:users)))
                    OR (t.target_type='review' AND t.target_id IN (
                        SELECT id FROM pull_request_review WHERE author_id=ANY(:users)))
                    OR (t.target_type='review_comment' AND t.target_id IN (
                        SELECT id FROM pull_request_review_comment WHERE author_id=ANY(:users)))
                ))
                """,
                "id,event_key,event_type,occurred_at,actor_id,workspace_id,repository_id,target_type,target_id,ingested_at",
                "id",
                "",
                -50));
    }
}
