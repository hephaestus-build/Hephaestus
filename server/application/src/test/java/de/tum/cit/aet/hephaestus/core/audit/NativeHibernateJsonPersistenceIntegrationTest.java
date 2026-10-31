package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.Project;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectField;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectFieldRepository;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class NativeHibernateJsonPersistenceIntegrationTest extends BaseIntegrationTest {
    private static final String TEXT = " { \"hash\" : \"sha256:abc\", \"text\" : \"雪\\\\path\" } \n";

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private ProjectFieldRepository projectFields;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldPreserveAllFiveTextFieldsAfterFlushClearAndReload() {
        var currentEntityManager = Objects.requireNonNull(entityManager);
        var now = Instant.parse("2026-10-01T12:00:00Z");
        var workspace = WorkspaceTestFixtures.activeWorkspace("native-json-text");
        currentEntityManager.persist(workspace);
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "text-fixture",
                new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        currentEntityManager.persist(connection);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://text-fixture.example");
        currentEntityManager.persist(provider);
        var project = new Project();
        project.setNativeId(995501L);
        project.setProvider(provider);
        project.setOwnerType(Project.OwnerType.ORGANIZATION);
        project.setOwnerId(995501L);
        project.setNumber(1);
        currentEntityManager.persist(project);
        var field = new ProjectField();
        field.setId("native-json-text");
        field.setProject(project);
        field.setName("Status");
        field.setDataType(ProjectField.DataType.SINGLE_SELECT);
        field.setOptions(TEXT);
        currentEntityManager.persist(field);
        var auth = AuthEvent.create(
                new AuthEventData(
                        AuthEvent.EventType.LOGIN,
                        AuthEvent.Result.SUCCESS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        TEXT,
                        false),
                995501L,
                now,
                null,
                null);
        currentEntityManager.persist(auth);
        var config = ConfigAuditEvent.create(
                now,
                null,
                new ConfigAuditActor(ConfigAuditActorKind.SYSTEM, null, null, false),
                ConfigAuditEntityType.CATALOG_PRACTICE,
                "text-fixture",
                ConfigAuditAction.UPDATED,
                List.of("hash"),
                TEXT,
                "null");
        currentEntityManager.persist(config);
        var audit = new ConnectionAudit(
                connection, "SUSPEND", null, null, "SYSTEM", null, null, "Connection paused. 雪\\path");
        currentEntityManager.persist(audit);
        currentEntityManager.flush();
        var configId = config.getId();
        var auditId = Objects.requireNonNull(audit.getId());
        currentEntityManager.clear();
        assertThat(Objects.requireNonNull(currentEntityManager.find(AuthEvent.class, new AuthEvent.Id(995501L, now)))
                        .getDetails())
                .isEqualTo(TEXT);
        var loadedConfig = Objects.requireNonNull(currentEntityManager.find(ConfigAuditEvent.class, configId));
        assertThat(loadedConfig.getOldValue()).isEqualTo(TEXT);
        assertThat(loadedConfig.getNewValue()).isEqualTo("null");
        assertThat(Objects.requireNonNull(currentEntityManager.find(ConnectionAudit.class, auditId))
                        .getDetail())
                .isEqualTo("Connection paused. 雪\\path");
        assertThat(projectFields.findById(field.getId()).orElseThrow().getOptions())
                .isEqualTo(TEXT);
        // Prose is deliberately not valid JSON: the UPSERT must bind TEXT on insert and update.
        projectFields.upsertCore(
                "native-json-upsert", project.getId(), "Priority", "SINGLE_SELECT", "Plain options 雪\\path", now, now);
        assertThat(projectFields
                        .findByProjectIdAndName(project.getId(), "Priority")
                        .orElseThrow()
                        .getOptions())
                .isEqualTo("Plain options 雪\\path");
        projectFields.upsertCore("native-json-upsert", project.getId(), "Priority", "SINGLE_SELECT", null, now, now);
        assertThat(projectFields
                        .findByProjectIdAndName(project.getId(), "Priority")
                        .orElseThrow()
                        .getOptions())
                .isNull();
    }

    @Test
    void shouldPersistTypedJsonbWithDeclaredTypes() {
        var currentEntityManager = Objects.requireNonNull(entityManager);
        var workspace = WorkspaceTestFixtures.activeWorkspace("native-json-typed");
        currentEntityManager.persist(workspace);
        var metadata = new ConnectionConfig.GitLabTokenMetadata(
                LocalDate.of(2027, 1, 1), Instant.parse("2026-10-01T12:00:00Z"));
        var config = new ConnectionConfig.GitLabConfig(
                "https://typed.example", 42L, null, Set.of("merge_requests"), metadata);
        var connection = new Connection(workspace, IntegrationKind.GITLAB, "typed-fixture", config);
        currentEntityManager.persist(connection);
        var node = mapper.readTree("{\"hash\":\"sha256:abc\",\"nested\":[1,true,null,\"雪\"]}");
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        RuntimeJobTestData.persistParent(jdbc, job);
        job.setConfigSnapshot(node);
        currentEntityManager.persist(job);
        var definition = new GroupDefinition("Typed fixture", "Criteria.", null, null);
        var group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug("native-json-typed");
        group.setName(definition.name());
        group.setCatalogSlug("native-json-typed");
        group.setCatalogBase(definition);
        currentEntityManager.persist(group);
        currentEntityManager.flush();
        var connectionId = connection.getId();
        var jobId = job.getId();
        var groupId = group.getId();
        currentEntityManager.clear();
        assertThat(Objects.requireNonNull(currentEntityManager.find(Connection.class, connectionId))
                        .getConfig())
                .isEqualTo(config);
        assertThat(Objects.requireNonNull(currentEntityManager.find(ReviewJob.class, jobId))
                        .getConfigSnapshot())
                .isEqualTo(node);
        assertThat(Objects.requireNonNull(currentEntityManager.find(PracticeGroup.class, groupId))
                        .getCatalogBase())
                .isEqualTo(definition);
        String stored = (String) currentEntityManager
                .createNativeQuery("SELECT config::text FROM connection WHERE id=:id AND workspace_id=:workspace")
                .setParameter("id", connectionId)
                .setParameter("workspace", workspace.getId())
                .getSingleResult();
        var tree = mapper.readTree(stored);
        assertThat(tree.path("type").asString()).isEqualTo("GITLAB");
        assertThat(tree.path("tokenMetadata").path("checkedAt").asString()).isEqualTo("2026-10-01T12:00:00Z");
        assertThat(tree.path("tokenMetadata").path("expiresAt").asString()).isEqualTo("2027-01-01");
        assertThat(currentEntityManager
                        .createNativeQuery(
                                "SELECT pg_typeof(config)::text FROM connection WHERE id=:id AND workspace_id=:workspace")
                        .setParameter("id", connectionId)
                        .setParameter("workspace", workspace.getId())
                        .getSingleResult())
                .isEqualTo("jsonb");
    }
}
