package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Includes confirmed and uncertain provider writes, even when result projection never completed. */
final class ExternalFeedbackPersonDataStore extends JdbcPersonDataStore {
    ExternalFeedbackPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            String store,
            String table,
            String predicate,
            String columns,
            String keys,
            String redaction,
            int order) {
        super(jdbc, mapper, store, table, predicate, columns, keys, redaction, order);
    }

    @Override
    public List<ExternalDelivery> externalDeliveries(PersonDataSelection selection) {
        boolean placement = store().equals("feedback_placement");
        String projection = placement
                ? "jsonb_build_object('workspaceId',f.workspace_id,'locator',t.posted_comment_ref)::text"
                : "jsonb_build_object('workspaceId',t.workspace_id,'locator',t.delivered_external_ref,'placements',t.delivered_placements)::text";
        String from = placement ? "feedback_placement t JOIN feedback f ON f.id=t.feedback_id" : "feedback_dispatch t";
        List<ExternalDelivery> deliveries = new ArrayList<>();
        for (JsonNode row : querySelected(projection, from, selection)) {
            add(deliveries, row.path("workspaceId").asLong(), row.path("locator"));
            for (JsonNode inline : row.path("placements"))
                add(deliveries, row.path("workspaceId").asLong(), inline.path("externalRef"));
        }
        return deliveries.stream().distinct().toList();
    }

    private static void add(List<ExternalDelivery> deliveries, long workspaceId, JsonNode locator) {
        if (locator.isString() && !locator.asString().isBlank())
            deliveries.add(new ExternalDelivery(workspaceId, locator.asString()));
    }
}
