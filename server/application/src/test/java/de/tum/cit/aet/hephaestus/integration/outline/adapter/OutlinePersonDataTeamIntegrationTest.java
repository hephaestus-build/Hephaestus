package de.tum.cit.aet.hephaestus.integration.outline.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class OutlinePersonDataTeamIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IntegrationOutlinePersonDataCatalog catalog;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private OutlineDocumentRepository documents;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private EntityManager entities;

    @ParameterizedTest
    @ValueSource(strings = {"outline_document", "outline_document_editor", "outline_document_collaborator"})
    void exportAndErasureCannotAliasAnotherTeamsNativeSubject(String storeName) {
        String firstTeam = UUID.randomUUID().toString();
        String secondTeam = UUID.randomUUID().toString();
        var first = document(firstTeam);
        var second = document(secondTeam);
        long firstId = first.getId();
        long secondId = second.getId();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.OUTLINE, "https://wiki.example.com")
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.OUTLINE, "https://wiki.example.com")));
        var person = new PersonScope(
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "same-native-subject", firstTeam)),
                List.of());
        var store = catalog.contributors().stream()
                .filter(candidate -> candidate.store().equals(storeName))
                .findFirst()
                .orElseThrow();
        var selected = store.select(person);
        assertThat(selected.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.columns().get("id")).isEqualTo(Long.toString(firstId)));
        assertThat(store.export(selected, person)).hasSize(1);
        assertThat(store.erase(selected, person)).isEqualTo(1L);
        entities.clear();
        var untouched = documents.findById(secondId).orElseThrow();
        assertThat(untouched.getCreatedBySubject()).isEqualTo("same-native-subject");
        assertThat(untouched.getUpdatedBySubject()).isEqualTo("same-native-subject");
        assertThat(untouched.getCollaboratorSubjects()).containsExactly("same-native-subject");
        assertThat(untouched.getBodyMarkdown()).isEqualTo("Other team retains its source");
        var erased = documents.findById(firstId).orElseThrow();
        switch (storeName) {
            case "outline_document" -> {
                assertThat(erased.getCreatedBySubject()).isNull();
                assertThat(erased.getBodyMarkdown()).isNull();
            }
            case "outline_document_editor" ->
                assertThat(erased.getUpdatedBySubject()).isNull();
            case "outline_document_collaborator" ->
                assertThat(erased.getCollaboratorSubjects()).isNullOrEmpty();
            default -> throw new IllegalArgumentException(storeName);
        }
    }

    private OutlineDocument document(String team) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("outline-team-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                team,
                new ConnectionConfig.OutlineConfig("https://wiki.example.com", null, null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = connections.saveAndFlush(connection);
        var doc = new OutlineDocument();
        doc.setWorkspaceId(workspace.getId());
        doc.setConnectionId(connection.getId());
        doc.setDocumentId("document");
        doc.setCollectionId("collection");
        doc.setTitle("Shared native subject");
        doc.setCreatedBySubject("same-native-subject");
        doc.setUpdatedBySubject("same-native-subject");
        doc.setCollaboratorSubjects(List.of("same-native-subject"));
        doc.setBodyMarkdown("Other team retains its source");
        return documents.saveAndFlush(doc);
    }
}
