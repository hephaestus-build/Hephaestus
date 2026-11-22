package de.tum.cit.aet.hephaestus.practices.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositorySyncDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncResultDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncStatus;
import de.tum.cit.aet.hephaestus.practices.repository.PracticeRepositoryService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The practice repository tools return what their output schemas declare, as the SDK checks each result. */
@Tag("unit")
class PracticeRepositoryToolsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final PracticeRepositorySyncDTO REPORT = new PracticeRepositorySyncDTO(
            "0123456789abcdef0123456789abcdef01234567",
            Instant.parse("2026-11-22T09:00:00Z"),
            List.of(
                    new PracticeSyncResultDTO("explain", PracticeSyncStatus.CREATED, List.of()),
                    new PracticeSyncResultDTO(
                            "broken",
                            PracticeSyncStatus.INVALID,
                            List.of(new PracticeFileProblemDTO(
                                    "practice.md",
                                    null,
                                    null,
                                    PracticeFileProblemDTO.FRONT_MATTER_MISSING,
                                    "Start practice.md with a line ---.")))),
            List.of());

    private final PracticeRepositoryService repositories = mock(PracticeRepositoryService.class);

    @BeforeEach
    void enterTheWorkspace() {
        WorkspaceContextHolder.setContext(new WorkspaceContext(10L, "workspace", "Workspace", null, null, Set.of()));
    }

    @AfterEach
    void clearTheWorkspace() {
        WorkspaceContextHolder.clearContext();
    }

    @Test
    void shouldReturnTheReportAsTheOutputSchemaDeclaresWhenItSyncs() {
        when(repositories.sync(any())).thenReturn(REPORT);
        var tool = new SyncPracticeRepositoryTool(repositories, MAPPER);

        assertConforms(tool.definition(), tool.call(Map.of()));
    }

    @Test
    void shouldReturnTheLastReportAsTheOutputSchemaDeclaresWhenItReads() {
        var tool = new GetPracticeRepositoryTool(repositories, MAPPER);
        when(repositories.get(any()))
                .thenReturn(Optional.of(
                        new PracticeRepositoryDTO("42", "acme/practices", "main", ".hephaestus/practices", REPORT)));
        assertConforms(tool.definition(), tool.call(Map.of()));

        when(repositories.get(any()))
                .thenReturn(Optional.of(new PracticeRepositoryDTO("42", null, null, ".hephaestus/practices", null)));
        assertConforms(tool.definition(), tool.call(Map.of()));

        when(repositories.get(any())).thenReturn(Optional.empty());
        var none = tool.call(Map.of());
        assertConforms(tool.definition(), none);
        assertThat(MAPPER.writeValueAsString(none.structuredContent())).isEqualTo("{\"practiceRepository\":null}");
    }

    @SuppressWarnings("unchecked")
    private static void assertConforms(McpSchema.Tool tool, McpSchema.CallToolResult result) {
        var validation = new DefaultJsonSchemaValidator(MAPPER)
                .validate(MAPPER.convertValue(tool.outputSchema(), Map.class), result.structuredContent());
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }
}
