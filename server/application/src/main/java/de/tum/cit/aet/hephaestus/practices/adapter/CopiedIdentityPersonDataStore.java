package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The person's own copied identities on review items. They are selected by the person's exact provider identity,
 * never by the jobs that copied them, so an export holds no other person's identity ({@link CopiedIdentityMatch}).
 */
final class CopiedIdentityPersonDataStore extends JdbcPersonDataStore {
    private final ObjectMapper mapper;
    private final PersonIdentityResolver identities;

    CopiedIdentityPersonDataStore(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PersonIdentityResolver identities, int order) {
        super(
                jdbc,
                mapper,
                "review_item_copied_identity",
                "review_item_copied_identity",
                "EXISTS(SELECT 1 FROM review_item_copied_identity copy"
                        + " JOIN jsonb_array_elements(CAST(:copies AS jsonb)) person ON " + CopiedIdentityMatch.PERSON
                        + " WHERE copy.id = t.id)",
                "id,workspace_id,review_id,practice_id,item_id,provider_type,provider_origin,subject,team_id",
                "id,workspace_id",
                "",
                order);
        this.mapper = mapper;
        this.identities = identities;
    }

    @Override
    public PersonDataSelection select(PersonScope scope) {
        return selectUsing(Map.of(
                "copies",
                mapper.writeValueAsString(CapturedPersonDataContributor.copiedIdentities(scope, identities))));
    }
}
