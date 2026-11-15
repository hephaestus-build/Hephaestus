package de.tum.cit.aet.hephaestus.workspace.mcp;

import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipController;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code list_members}: the members and their provider logins, as {@code MembershipController.listMembers}
 * reads them. The review scope names people by account id; this maps them to logins. No display name, email
 * or avatar goes out (ADR 0110 § Data contract).
 */
@Component
@McpToolFor(controller = MembershipController.class, method = "listMembers")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ListMembersTool implements McpTool {

    private static final int PAGE_SIZE = 100;

    private static final String INPUT_SCHEMA = """
            {"type": "object", "additionalProperties": false,
             "properties": {"page": {"type": "integer", "minimum": 0, "description": "Zero-based page of 100 members"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["members", "page", "hasMore"], "additionalProperties": false,
             "properties": {"page": {"type": "integer"}, "hasMore": {"type": "boolean"},
               "members": {"type": "array", "items": {"type": "object", "required": ["accountId", "role", "reviewSelected", "logins"],
                 "additionalProperties": false,
                 "properties": {"accountId": {"type": "string"}, "role": {"type": "string"}, "reviewSelected": {"type": "boolean"},
                   "logins": {"type": "array", "items": {"type": "object", "required": ["provider", "login"], "additionalProperties": false,
                     "properties": {"provider": {"type": "string"}, "login": {"type": "string"}}}}}}}}}
            """;

    private final MembershipService memberships;
    private final AccountReferenceService accounts;
    private final McpSchema.Tool definition;

    ListMembersTool(MembershipService memberships, AccountReferenceService accounts, JsonMapper jsonMapper) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.definition = McpTools.readTool(
                jsonMapper,
                "list_members",
                "List members",
                "Lists the members of this workspace by account id, with their role, whether Hephaestus reviews their"
                        + " work, and their provider logins.",
                INPUT_SCHEMA,
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        long workspaceId = WorkspaceContextHolder.getContext().id();
        int page = Math.max(0, McpTools.optionalInt(arguments, "page", 0));
        Page<Membership> found =
                memberships.listMembers(workspaceId, null, null, PageRequest.of(page, PAGE_SIZE, Sort.by("joinedAt")));
        List<Long> accountIds = found.getContent().stream()
                .map(member -> Objects.requireNonNull(member.getAccountId()))
                .toList();
        Map<Long, AccountRefDTO> references = accounts.references(workspaceId, accountIds);
        List<Member> rows = found.getContent().stream()
                .map(member -> {
                    Long accountId = Objects.requireNonNull(member.getAccountId());
                    AccountRefDTO reference = references.get(accountId);
                    List<Login> logins = reference == null
                            ? List.of()
                            : reference.identities().stream()
                                    .filter(identity -> identity.login() != null)
                                    .map(identity -> new Login(identity.provider(), Objects.requireNonNull(identity.login())))
                                    .toList();
                    return new Member(String.valueOf(accountId), member.getRole().name(), member.isReviewSelected(), logins);
                })
                .toList();
        return McpTools.result(new Result(rows, page, found.hasNext()));
    }

    record Result(List<Member> members, int page, boolean hasMore) {}

    record Member(String accountId, String role, boolean reviewSelected, List<Login> logins) {}

    record Login(String provider, String login) {}
}
