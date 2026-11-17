package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogController;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code update_practice}: changes some fields of a practice's definition, as {@code PracticeCatalogController.update}
 * saves a whole definition. The changed fields replace the current ones, and the service checks the result as it checks
 * the web app's save: the whole definition, the expected version, a new version and its audit row (ADR 0110 § Tools).
 */
@Component
@McpToolFor(controller = PracticeCatalogController.class, method = "update")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class UpdatePracticeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "expectedVersionId", "definition"], "additionalProperties": false,
             "properties": {
               "slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
               "expectedVersionId": {"type": "string", "pattern": "^[1-9][0-9]*$", "description": "The expectedVersionId that get_practice returned"},
               "definition": {"type": "object", "minProperties": 1,
                 "description": "Only the top-level definition fields to change, each with its whole new value, in the shape that get_practice returns"}}}
            """;

    private final PracticeService practices;
    private final CatalogOriginPresenter presenter;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final McpSchema.Tool definition;

    UpdatePracticeTool(
            PracticeService practices, CatalogOriginPresenter presenter, JsonMapper jsonMapper, Validator validator) {
        this.practices = practices;
        this.presenter = presenter;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "update_practice",
                        "Change a practice",
                        "Changes the named top-level fields of a practice's definition and saves it as a new version. Name"
                                + " the expectedVersionId from get_practice; if the practice changed since, nothing is saved."
                                + " Set reviewedBy.by to NOBODY, with humanReason null and the current limits, to stop"
                                + " reviewing a practice: it keeps its reads, script, scan and limits unused. Undo it by"
                                + " setting reviewedBy.by to HEPHAESTUS again, or with reset_practice for a catalog practice."),
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
                McpTools.Effect.CHANGES);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        String slug = McpTools.requiredString(arguments, "slug");
        if (!(arguments.get("definition") instanceof Map<?, ?> changes) || changes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name at least one definition field to change.");
        }
        var current = presenter
                .present(context.id(), practices.getPractice(context, slug))
                .currentVersion()
                .definition();
        ObjectNode merged = current == null ? jsonMapper.createObjectNode() : jsonMapper.valueToTree(current);
        changes.forEach((field, value) -> merged.set(String.valueOf(field), jsonMapper.valueToTree(value)));
        var input = McpTools.input(
                jsonMapper,
                validator,
                Map.of(
                        "definition",
                        merged,
                        "expectedVersionId",
                        McpTools.requiredString(arguments, "expectedVersionId")),
                UpdatePracticeRequestDTO.class);
        return McpTools.result(McpProjections.Practice.of(
                presenter.present(context.id(), practices.updatePractice(context, slug, input))));
    }
}
