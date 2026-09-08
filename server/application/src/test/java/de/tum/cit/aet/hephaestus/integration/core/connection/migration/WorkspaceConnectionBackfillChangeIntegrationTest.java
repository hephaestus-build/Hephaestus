package de.tum.cit.aet.hephaestus.integration.core.connection.migration;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives {@link WorkspaceConnectionBackfillChange#execute} against a real PostgreSQL
 * Testcontainer so the integration contract — per-mode dispatch, ON CONFLICT
 * idempotency, AAD-bound credential rewrap — is exercised end-to-end.
 *
 * <p>The production migration drops the legacy columns immediately after the backfill
 * step. Tests run after that drop has already happened, so we re-add the columns on a
 * temporary fixture row, seed legacy values, and only THEN invoke {@code execute(...)}.
 * The columns are dropped again in {@link #tearDown} so other integration tests are
 * unaffected.
 *
 * <p>Deliberately NOT {@code @Transactional}. The change-under-test opens its own
 * Liquibase JDBC connection; the seed/assert helpers use raw {@code dataSource}
 * connections in try-with-resources (auto-committing, so the change sees them). A
 * class-level {@code @Transactional} would hold a JPA connection across the DDL +
 * backfill and trip HikariCP leak detection, while also hiding the seeded rows from
 * the change's separate connection. Cleanup is explicit: {@code cleanDatabase()} in
 * {@link #setUp} and the column drop in {@link #tearDown}.
 */
class WorkspaceConnectionBackfillChangeIntegrationTest extends BaseIntegrationTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key-32-bytes-aes";
    private static final byte[] KEY_BYTES = ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
    private static final String SYSTEM_PROPERTY = "hephaestus.security.encryption-key";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CredentialBundleConverter converter;

    private String savedSystemProperty;

    @BeforeEach
    void setUp() throws Exception {
        databaseTestUtils.cleanDatabase();

        // The customChange reads the key via System.getProperty — capture and restore.
        savedSystemProperty = System.getProperty(SYSTEM_PROPERTY);
        System.setProperty(SYSTEM_PROPERTY, ENCRYPTION_KEY);

        // Re-add legacy columns that section 9 of the unified-framework changelog
        // drops. Required so we can drive the backfill path that production v1 saw.
        // NOTE: account_login is a live current column — do NOT add/drop it here.
        executeDdl("ALTER TABLE workspace " + "ADD COLUMN IF NOT EXISTS git_provider_mode varchar(64), "
                + "ADD COLUMN IF NOT EXISTS installation_id bigint, "
                + "ADD COLUMN IF NOT EXISTS server_url varchar(255), "
                + "ADD COLUMN IF NOT EXISTS personal_access_token text, "
                + "ADD COLUMN IF NOT EXISTS gitlab_group_id bigint, "
                + "ADD COLUMN IF NOT EXISTS gitlab_webhook_id bigint");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Column-DROP first, system-property restore in the finally: a DDL failure must not skip
        // the restore, and the legacy columns must not survive on the shared schema.
        try {
            // NOTE: account_login is a live current column — do NOT drop it here.
            executeDdl("ALTER TABLE workspace " + "DROP COLUMN IF EXISTS git_provider_mode, "
                    + "DROP COLUMN IF EXISTS installation_id, "
                    + "DROP COLUMN IF EXISTS server_url, "
                    + "DROP COLUMN IF EXISTS personal_access_token, "
                    + "DROP COLUMN IF EXISTS gitlab_group_id, "
                    + "DROP COLUMN IF EXISTS gitlab_webhook_id");
        } finally {
            restoreSystemProperty();
        }
    }

    private void restoreSystemProperty() {
        if (savedSystemProperty == null) {
            System.clearProperty(SYSTEM_PROPERTY);
        } else {
            System.setProperty(SYSTEM_PROPERTY, savedSystemProperty);
        }
    }

    @Test
    void backfillsGithubAppInstallationModeWithoutCredentialBlob() throws Exception {
        long workspaceId = insertWorkspaceWithLegacyColumns("GITHUB_APP_INSTALLATION", "acme-org", 42L, null, null);

        runBackfill();

        var rows = listConnectionRows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.workspaceId()).isEqualTo(workspaceId);
        assertThat(row.kind()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(row.instanceKey()).isEqualTo("42");
        assertThat(row.credentialBlob())
                .as("App mode mints installation tokens on demand — no credential blob")
                .isNull();

        var connection = connectionRepository.findById(row.id()).orElseThrow();
        assertThat(connection.getConfig()).isInstanceOf(ConnectionConfig.GitHubAppConfig.class);
        var cfg = (ConnectionConfig.GitHubAppConfig) connection.getConfig();
        assertThat(cfg.installationId()).isEqualTo(42L);
        assertThat(cfg.orgLogin()).isEqualTo("acme-org");
    }

    @Test
    void backfillsGithubPatModeAndRewrapsCredentialUnderV2Aad() throws Exception {
        String legacyEncryptedPat = encryptLegacy("ghp_xyz", KEY_BYTES);
        long workspaceId = insertWorkspaceWithLegacyColumns("PAT_ORG", "acme-org", null, legacyEncryptedPat, null);

        runBackfill();

        var rows = listConnectionRows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.workspaceId()).isEqualTo(workspaceId);
        assertThat(row.kind()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(row.instanceKey()).isEqualTo("pat");
        assertThat(row.credentialBlob()).isNotNull();
        assertThat(row.credentialBlob()[0])
                .as("v2 format version byte")
                .isEqualTo(CredentialBundleConverter.FORMAT_VERSION_V2);
        // Prove a real rewrap happened rather than a passthrough store of the legacy bytes: the
        // persisted v2 blob must NOT be byte-equal to the legacy v1 ciphertext. (Distinct formats —
        // v1 is the ENC:base64 string, v2 is the binary FORMAT_VERSION_V2 envelope under the row's
        // AAD — so equality here would mean the migration copied bytes blindly.)
        assertThat(row.credentialBlob())
                .as("v2 blob is a fresh AAD-bound rewrap, not a copy of the legacy v1 ciphertext")
                .isNotEqualTo(legacyEncryptedPat.getBytes(StandardCharsets.UTF_8));

        // Decrypt with the production converter under the row's AAD — proves the
        // customChange writes blobs the running app can read.
        var ctx = new EncryptionContext(workspaceId, IntegrationKind.GITHUB, "pat", "connection.credentials_encrypted");
        var bundle = converter.decrypt(row.credentialBlob(), ctx);
        assertThat(bundle).isInstanceOf(ApiCredentialProvider.BearerToken.class);
        assertThat(((ApiCredentialProvider.BearerToken) bundle).token()).isEqualTo("ghp_xyz");
    }

    @Test
    void backfillsGitlabPatModeWithComposedInstanceKey() throws Exception {
        String legacyEncryptedPat = encryptLegacy("glpat-xyz", KEY_BYTES);
        long workspaceId = insertWorkspaceWithLegacyColumns(
                "GITLAB_PAT", null, null, legacyEncryptedPat, 7777L, "https://gitlab.example.com");

        runBackfill();

        var rows = listConnectionRows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.workspaceId()).isEqualTo(workspaceId);
        assertThat(row.kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(row.instanceKey())
                .as("instance_key = '<serverUrl>:<groupId>' per backfill contract")
                .isEqualTo("https://gitlab.example.com:7777");
        assertThat(row.credentialBlob()).isNotNull();

        var connection = connectionRepository.findById(row.id()).orElseThrow();
        assertThat(connection.getConfig()).isInstanceOf(ConnectionConfig.GitLabConfig.class);
        var cfg = (ConnectionConfig.GitLabConfig) connection.getConfig();
        assertThat(cfg.serverUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(cfg.gitlabGroupId()).isEqualTo(7777L);
        assertThat(cfg.signingMode()).isEqualTo(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT);
    }

    @Test
    void reRunningTheChangeIsIdempotent() throws Exception {
        insertWorkspaceWithLegacyColumns("GITHUB_APP_INSTALLATION", "acme-org", 42L, null, null);

        runBackfill();
        long firstRunCount = countConnectionRows();
        assertThat(firstRunCount).isEqualTo(1);

        runBackfill();
        assertThat(countConnectionRows())
                .as("idempotency: ON CONFLICT must skip already-backfilled rows")
                .isEqualTo(firstRunCount);
    }

    @Test
    void skipsWorkspacesWithNullModeWithoutInsertingARow() throws Exception {
        insertWorkspaceWithLegacyColumns(null, null, null, null, null);

        runBackfill();

        assertThat(countConnectionRows())
                .as("workspaces with NULL git_provider_mode are skipped silently")
                .isZero();
    }

    @Test
    void skipsAppInstallationRowsWithNullInstallationId() throws Exception {
        insertWorkspaceWithLegacyColumns("GITHUB_APP_INSTALLATION", "acme-org", null, null, null);

        runBackfill();

        assertThat(countConnectionRows()).isZero();
    }

    private void runBackfill() throws Exception {
        WorkspaceConnectionBackfillChange change = new WorkspaceConnectionBackfillChange();
        try (Connection conn = dataSource.getConnection()) {
            PostgresDatabase database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(conn));
            change.execute(database);
            // Liquibase's JdbcConnection runs with autocommit off; outside the normal
            // changeset lifecycle we must commit explicitly or the backfilled rows are
            // rolled back when the connection closes.
            database.commit();
        }
        entityManager.clear();
    }

    private long insertWorkspaceWithLegacyColumns(
            @Nullable String mode,
            @Nullable String accountLogin,
            @Nullable Long installationId,
            @Nullable String encryptedPat,
            @Nullable Long gitlabGroupId)
            throws Exception {
        return insertWorkspaceWithLegacyColumns(mode, accountLogin, installationId, encryptedPat, gitlabGroupId, null);
    }

    private long insertWorkspaceWithLegacyColumns(
            @Nullable String mode,
            @Nullable String accountLogin,
            @Nullable Long installationId,
            @Nullable String encryptedPat,
            @Nullable Long gitlabGroupId,
            @Nullable String serverUrl)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "INSERT INTO workspace (" + "  slug, display_name, status, created_at, updated_at, "
                                + "  account_type, is_publicly_viewable, "
                                + "  practices_enabled, mentor_enabled, leaderboard_enabled, "
                                + "  progression_enabled, leagues_enabled, "
                                + "  practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled, "
                                + "  git_provider_mode, account_login, installation_id, personal_access_token, "
                                + "  gitlab_group_id, server_url"
                                + ") VALUES (?, ?, ?, NOW(), NOW(), "
                                + "  'ORG', false, "
                                + "  false, false, false, false, false, true, true, "
                                + "  ?, ?, ?, ?, ?, ?) RETURNING id")) {
            String slug = "ws-" + System.nanoTime();
            stmt.setString(1, slug);
            stmt.setString(2, "Backfill Fixture " + slug);
            stmt.setString(3, "ACTIVE");
            stmt.setString(4, mode);
            // account_login is NOT NULL in the current schema; some legacy test rows
            // have no login (e.g. GitLab-only, null-mode skip tests) — use a placeholder.
            stmt.setString(5, accountLogin != null ? accountLogin : "placeholder");
            if (installationId == null) {
                stmt.setNull(6, java.sql.Types.BIGINT);
            } else {
                stmt.setLong(6, installationId);
            }
            stmt.setString(7, encryptedPat);
            if (gitlabGroupId == null) {
                stmt.setNull(8, java.sql.Types.BIGINT);
            } else {
                stmt.setLong(8, gitlabGroupId);
            }
            stmt.setString(9, serverUrl);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<ConnectionRow> listConnectionRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT id, workspace_id, kind, instance_key, credentials_encrypted FROM connection ORDER BY id");
                var rs = stmt.executeQuery()) {
            var result = new ArrayList<ConnectionRow>();
            while (rs.next()) {
                result.add(new ConnectionRow(
                        rs.getLong("id"),
                        rs.getLong("workspace_id"),
                        IntegrationKind.valueOf(rs.getString("kind")),
                        rs.getString("instance_key"),
                        rs.getBytes("credentials_encrypted")));
            }
            return result;
        }
    }

    private long countConnectionRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("SELECT COUNT(*) FROM connection");
                var rs = stmt.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void executeDdl(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** Mirrors {@code EncryptedStringConverter#convertToDatabaseColumn}. */
    private static String encryptLegacy(String plaintext, byte[] keyBytes) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return "ENC:" + Base64.getEncoder().encodeToString(combined);
    }

    private record ConnectionRow(
            long id, long workspaceId, IntegrationKind kind, String instanceKey, byte[] credentialBlob) {}
}
