package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeFileController;
import de.tum.cit.aet.hephaestus.practices.PracticeFileService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code check_practice_files}: checks a practice folder as {@code PracticeFileController.check} does, without saving
 * it and without calling a model.
 */
@Component
@McpToolFor(controller = PracticeFileController.class, method = "check", savesNothing = true)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class CheckPracticeFilesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "files"], "additionalProperties": false,
             "properties": {
               "slug": {"type": "string", "description": "The folder name, which is the practice slug"},
               "files": {"type": "object", "minProperties": 1, "maxProperties": 16,
                         "description": "File contents by path in the folder: practice.md, and optionally guide.md, visual.svg, script.ts and figures/<name>.svg",
                         "additionalProperties": {"type": "string", "maxLength": 262144}}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "additionalProperties": false, "required": ["valid", "name", "kind", "groupSlug", "problems"],
             "properties": {
               "valid": {"type": "boolean"},
               "name": {"type": ["string", "null"]}, "kind": {"type": ["string", "null"]},
               "groupSlug": {"type": ["string", "null"]},
               "problems": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["file", "line", "column", "code", "message"],
                 "properties": {"file": {"type": "string"}, "line": {"type": ["integer", "null"]},
                   "column": {"type": ["integer", "null"]}, "code": {"type": "string"},
                   "message": {"type": "string"}}}}}}
            """;

    private final PracticeFileService files;
    private final McpSchema.Tool definition;

    CheckPracticeFilesTool(PracticeFileService files, JsonMapper jsonMapper) {
        this.files = files;
        this.definition = McpTools.readTool(
                jsonMapper,
                "check_practice_files",
                "Check a practice folder",
                "Checks the files of one practice folder, as committed under .hephaestus/practices/<slug>/, and"
                        + " returns each problem with its file, line and column. Saves nothing and calls no model.",
                INPUT_SCHEMA,
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "A check reads the files that the caller sends and saves nothing")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        var slug = McpTools.requiredString(arguments, "slug");
        if (!(arguments.get("files") instanceof Map<?, ?> given) || given.isEmpty() || given.size() > 16)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The argument files needs 1 to 16 files.");
        var folder = new LinkedHashMap<String, String>();
        for (var entry : given.entrySet()) {
            if (!(entry.getKey() instanceof String path) || !(entry.getValue() instanceof String content))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each file needs a path and text content.");
            folder.put(path, content);
        }
        return McpTools.result(files.check(slug, folder));
    }
}
