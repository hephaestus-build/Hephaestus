package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
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
    public PersonDataSelection select(PersonScope scope) {
        var selected = super.select(scope);
        // This fingerprint contains only delivery facts and locators. A new write on the same
        // primary key invalidates the operator's earlier provider inspection and confirmation.
        return selected.withExternalDeliveryFacts(inspectionRows(selected));
    }

    private List<JsonNode> inspectionRows(PersonDataSelection selection) {
        boolean placement = store().equals("feedback_placement");
        String projection = placement
                ? "jsonb_build_object('id',t.id,'workspaceId',f.workspace_id,'locator',COALESCE(NULLIF(t.posted_comment_url,''),t.posted_comment_ref))::text"
                : """
                  jsonb_build_object('id',t.id,'workspaceId',t.workspace_id,'state',t.state,
                    'writeStarted',t.write_started,'inlineWriteStarted',t.inline_write_started,
                    'writeStartedAt',t.write_started_at,'locator',
                    COALESCE(NULLIF(t.delivered_external_url,''),t.delivered_external_ref),
                    'placements',t.delivered_placements,
                    'inspectionRequired',t.state='UNCERTAIN' OR t.write_started
                      OR t.inline_write_started IS TRUE
                      OR (t.inline_write_started IS NULL
                        AND t.package_content->'diffNotes' IS DISTINCT FROM '[]'::jsonb),
                    'reviewedWorkLocator',COALESCE(NULLIF(j.metadata->>'pr_url',''),
                      NULLIF(j.metadata->>'issue_url','')))::text
                  """;
        String from = placement
                ? "feedback_placement t JOIN feedback f ON f.id=t.feedback_id"
                : "feedback_dispatch t JOIN agent_job j ON j.id=t.agent_job_id AND j.workspace_id=t.workspace_id";
        return querySelected(projection, from, selection);
    }

    @Override
    public List<ExternalDelivery> externalDeliveries(PersonDataSelection selection) {
        List<ExternalDelivery> deliveries = new ArrayList<>();
        for (JsonNode row : inspectionRows(selection)) {
            long workspaceId = row.path("workspaceId").asLong();
            int before = deliveries.size();
            add(deliveries, workspaceId, row.path("locator"));
            for (JsonNode inline : row.path("placements")) {
                if (!add(deliveries, workspaceId, inline.path("externalUrl")))
                    add(deliveries, workspaceId, inline.path("externalRef"));
            }
            if (row.path("inspectionRequired").asBoolean()) {
                // Unknown provider outcomes have no comment ID. The reviewed-work URL still
                // tells the operator exactly where to inspect summary and inline comments.
                if (!add(deliveries, workspaceId, row.path("reviewedWorkLocator")) && deliveries.size() == before)
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Provider feedback dispatch " + row.path("id").asString()
                                    + " needs inspection, but Hephaestus has no exact locator for the reviewed work.");
            }
        }
        return deliveries.stream().distinct().toList();
    }

    private static boolean add(List<ExternalDelivery> deliveries, long workspaceId, JsonNode locator) {
        if (!locator.isString() || locator.asString().isBlank()) return false;
        deliveries.add(new ExternalDelivery(workspaceId, locator.asString()));
        return true;
    }
}
