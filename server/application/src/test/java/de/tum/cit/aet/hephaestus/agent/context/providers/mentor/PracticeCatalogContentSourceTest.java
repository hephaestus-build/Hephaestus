package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PracticeCatalogContentSourceTest extends BaseUnitTest {

    @Mock
    PracticeRepository practiceRepository;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    CacheManager cacheManager;

    @InjectMocks
    PracticeCatalogContentSource provider;

    @Test
    void handsTheMentorEveryPracticeThatAdmitsReviewAndNoneThatIsOff() throws Exception {
        Workspace ws = new Workspace();
        ws.setWorkspaceSlug("acme");
        when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));
        Practice awaiting = new Practice();
        awaiting.setSlug("awaiting-approval");
        awaiting.setName("Awaiting");
        awaiting.setCriteria("c");
        awaiting.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        Practice off = new Practice();
        off.setSlug("switched-off");
        off.setName("Off");
        off.setCriteria("c");
        off.setAutonomy(PracticeAutonomy.OFF);
        when(practiceRepository.findByWorkspaceId(eq(1L))).thenReturn(List.of(awaiting, off));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        // A chat turn is read on request: approval gates only what is pushed onto the work.
        JsonNode root = objectMapper.readTree(files.get("inputs/context/practice_catalog.json"));
        assertThat(root.get("practices").valueStream().map(p -> p.get("slug").asString()))
                .containsExactly("awaiting-approval");
    }

    @Test
    void writesCatalog() throws Exception {
        Workspace ws = new Workspace();
        ws.setWorkspaceSlug("acme");
        when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));

        Practice practice = new Practice();
        practice.setSlug("error-state-handling");
        practice.setName("Error State Handling");
        practice.setCriteria("Show an error view for failed network calls.");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        when(practiceRepository.findByWorkspaceId(eq(1L))).thenReturn(List.of(practice));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        byte[] bytes = files.get("inputs/context/practice_catalog.json");
        assertThat(bytes).isNotNull();
        JsonNode root = objectMapper.readTree(bytes);
        assertThat(root.get("workspace").get("slug").asString()).isEqualTo("acme");
        assertThat(root.get("practices").isArray()).isTrue();
        assertThat(root.get("practices")).hasSize(1);
        JsonNode entry = root.get("practices").get(0);
        assertThat(entry.get("slug").asString()).isEqualTo("error-state-handling");
        assertThat(entry.get("displayName").asString()).isEqualTo("Error State Handling");
        assertThat(entry.get("criteria").asString()).contains("Show an error view");
        assertThat(entry.has("description")).isFalse();
    }

    @Test
    @DisplayName("warm cache: a second turn with the same workspace reuses the payload without re-querying")
    void warmCacheReusesPayload() {
        // Stub a REAL cache so the compute-if-absent branch (cache.get(key, supplier)) is exercised — without
        // this, getCache returns null and only the uncached fallback path runs, leaving the race-closing line
        // (the one the javadoc calls load-bearing) uncovered.
        when(cacheManager.getCache(eq("mentor_practice_context")))
                .thenReturn(new ConcurrentMapCache("mentor_practice_context"));
        Workspace ws = new Workspace();
        ws.setWorkspaceSlug("acme");
        when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));
        when(practiceRepository.findByWorkspaceId(eq(1L))).thenReturn(List.of());

        var request = new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID());
        provider.contribute(request, new HashMap<>());
        provider.contribute(request, new HashMap<>());

        // buildPayload's query ran exactly once; the second turn served the warm cache node.
        verify(practiceRepository, times(1)).findByWorkspaceId(eq(1L));
    }

    @Test
    @DisplayName("unknown workspace: buildPayload fails loud with EntityNotFoundException")
    void unknownWorkspaceThrows() {
        when(workspaceRepository.findById(eq(99L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.buildPayload(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("no active practices: practices is an empty array but the workspace slug is still present")
    void emptyPracticeListStillCarriesWorkspace() throws Exception {
        Workspace ws = new Workspace();
        ws.setWorkspaceSlug("acme");
        when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));
        when(practiceRepository.findByWorkspaceId(eq(1L))).thenReturn(List.of());

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/practice_catalog.json"));
        assertThat(root.get("workspace").get("slug").asString()).isEqualTo("acme");
        assertThat(root.get("practices").isArray()).isTrue();
        assertThat(root.get("practices")).isEmpty();
    }
}
