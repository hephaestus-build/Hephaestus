package de.tum.cit.aet.hephaestus.integration.core.connection.migration;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitHubAppConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitHubPatConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Base64;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liquibase {@link CustomTaskChange} that lifts the legacy per-workspace SCM
 * integration columns into the {@code connection} aggregate BEFORE the
 * section-9 column drops in the same changelog destroy them.
 *
 * <p><b>What it migrates.</b> For each {@code workspace} row, one {@code connection}
 * row is inserted per the value of {@code workspace.git_provider_mode}:
 *
 * <ul>
 *   <li>{@code GITHUB_APP_INSTALLATION} → {@code kind=GITHUB},
 *       {@code instance_key=installation_id}, {@link GitHubAppConfig} JSONB,
 *       <b>no</b> credential blob (the App mints installation tokens on demand).</li>
 *   <li>{@code PAT_ORG} → {@code kind=GITHUB}, {@code instance_key='pat'},
 *       {@link GitHubPatConfig} JSONB. PAT is decrypted with the legacy
 *       {@code EncryptedStringConverter} format ({@code ENC:<base64>}) and
 *       re-encrypted as a {@link BearerToken} {@link
 *       de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle}
 *       under {@link CredentialBundleConverter}'s v2 per-row AAD format.</li>
 *   <li>{@code GITLAB_PAT} → {@code kind=GITLAB},
 *       {@code instance_key='<serverUrl>:<groupId>'},
 *       {@link GitLabConfig} JSONB with {@code SigningMode=PLAINTEXT}.
 *       PAT re-encrypted as above.</li>
 * </ul>
 *
 * <p><b>Slack is intentionally not migrated.</b> Legacy {@code slack_token} was
 * dead at runtime (SlackMessageService read from a global property bean), and
 * the new {@link ConnectionConfig.SlackConfig} requires a {@code teamId} that
 * legacy rows do not carry. The new OAuth flow provisions SLACK connections
 * from scratch, so dropping the legacy columns in section 9 is a clean cut.
 *
 * <p><b>Idempotency.</b> Every insert is {@code ON CONFLICT (workspace_id, kind,
 * instance_key) DO NOTHING} against {@code uq_connection}. Re-runs of the
 * customChange skip rows already present — exits with the same observable state.
 *
 * <p><b>Encryption key.</b> Resolved lazily, on the first row that carries an
 * encrypted credential. Sources are the same two paths
 * {@link de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter} reads:
 * the {@code hephaestus.security.encryption-key} system property (Spring binds
 * JVM properties there) or the {@code HEPHAESTUS_SECURITY_ENCRYPTION_KEY} env var.
 * Workspaces without an encrypted credential to rewrap (App mode, or rows with
 * NULL PAT) never trigger the lookup — a local dev DB with no key still
 * migrates App-mode rows cleanly.
 */
public class WorkspaceConnectionBackfillChange implements CustomTaskChange {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConnectionBackfillChange.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String LEGACY_PREFIX = "ENC:";

    private static final SecureRandom IV_GENERATOR = new SecureRandom();
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    @Override
    public String getConfirmationMessage() {
        return "Backfilled connection rows from legacy workspace integration columns";
    }

    @Override
    public void setUp() throws SetupException {}

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        JdbcConnection conn = (JdbcConnection) database.getConnection();
        EncryptionKeyHolder crypto = new EncryptionKeyHolder();

        int inserted = 0;
        int skipped = 0;

        try (Statement st = conn.createStatement();
                ResultSet rs =
                        st.executeQuery("SELECT id, git_provider_mode, installation_id, account_login, server_url, "
                                + "personal_access_token, gitlab_group_id, gitlab_webhook_id "
                                + "FROM workspace")) {
            while (rs.next()) {
                long workspaceId = rs.getLong("id");
                String mode = rs.getString("git_provider_mode");
                if (mode == null) {
                    skipped++;
                    continue;
                }
                String accountLogin = rs.getString("account_login");
                String serverUrl = rs.getString("server_url");
                Long installationId = nullableLong(rs, "installation_id");
                String pat = rs.getString("personal_access_token");
                Long gitlabGroupId = nullableLong(rs, "gitlab_group_id");
                Long gitlabWebhookId = nullableLong(rs, "gitlab_webhook_id");

                int rows = backfillScm(
                        conn,
                        workspaceId,
                        mode,
                        installationId,
                        accountLogin,
                        serverUrl,
                        pat,
                        gitlabGroupId,
                        gitlabWebhookId,
                        crypto);
                if (rows > 0) {
                    inserted++;
                } else {
                    skipped++;
                }
            }
        } catch (Exception e) {
            throw new CustomChangeException("WorkspaceConnectionBackfillChange failed", e);
        }

        log.info("WorkspaceConnectionBackfillChange complete: inserted={}, skipped={}", inserted, skipped);
    }

    private int backfillScm(
            JdbcConnection conn,
            long workspaceId,
            String mode,
            @Nullable Long installationId,
            @Nullable String accountLogin,
            @Nullable String serverUrl,
            @Nullable String encryptedPat,
            @Nullable Long gitlabGroupId,
            @Nullable Long gitlabWebhookId,
            EncryptionKeyHolder crypto)
            throws Exception {
        IntegrationKind kind;
        String instanceKey;
        ConnectionConfig config;
        byte[] credentialBlob;

        switch (mode) {
            case "GITHUB_APP_INSTALLATION" -> {
                if (installationId == null) {
                    log.warn(
                            "Skipping workspace {}: mode=GITHUB_APP_INSTALLATION but installation_id is NULL",
                            workspaceId);
                    return 0;
                }
                kind = IntegrationKind.GITHUB;
                instanceKey = Long.toString(installationId);
                config = new GitHubAppConfig(installationId, accountLogin, serverUrl, Collections.emptySet());
                credentialBlob = null;
            }
            case "PAT_ORG" -> {
                kind = IntegrationKind.GITHUB;
                instanceKey = "pat";
                config = new GitHubPatConfig(accountLogin, serverUrl, Collections.emptySet());
                credentialBlob = rewrapPat(encryptedPat, workspaceId, kind, instanceKey, crypto);
            }
            case "GITLAB_PAT" -> {
                kind = IntegrationKind.GITLAB;
                String url = serverUrl != null ? serverUrl : "https://gitlab.com";
                instanceKey = url + ":" + (gitlabGroupId != null ? gitlabGroupId : "");
                config = new GitLabConfig(
                        url,
                        gitlabGroupId,
                        gitlabWebhookId,
                        GitLabConfig.SigningMode.PLAINTEXT,
                        Collections.emptySet());
                credentialBlob = rewrapPat(encryptedPat, workspaceId, kind, instanceKey, crypto);
            }
            default -> {
                log.warn("Skipping workspace {}: unknown git_provider_mode={}", workspaceId, mode);
                return 0;
            }
        }

        int rows = insertConnection(conn, workspaceId, kind, instanceKey, config, credentialBlob);
        if (rows > 0) {
            log.info(
                    "Backfilled connection: workspace_id={}, kind={}, instance_key={}, has_credentials={}",
                    workspaceId,
                    kind,
                    instanceKey,
                    credentialBlob != null);
        } else {
            log.debug(
                    "Workspace {} already has a {} connection at instance_key={}; skipped by ON CONFLICT",
                    workspaceId,
                    kind,
                    instanceKey);
        }
        return rows;
    }

    /**
     * Atomically idempotent insert via {@code ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING}
     * against the {@code uq_connection} unique constraint. Returns {@code 1} when a new row was
     * inserted, {@code 0} when the row already existed.
     */
    private int insertConnection(
            JdbcConnection conn,
            long workspaceId,
            IntegrationKind kind,
            String instanceKey,
            ConnectionConfig config,
            byte @Nullable [] credentialBlob)
            throws Exception {
        String configJson = MAPPER.writeValueAsString(config);
        String sql = "INSERT INTO connection (" + "workspace_id, kind, instance_key, state, config, "
                + "credentials_encrypted, credentials_alg, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NOW(), NOW(), 0) "
                + "ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, workspaceId);
            ps.setString(2, kind.name());
            ps.setString(3, instanceKey);
            ps.setString(4, IntegrationState.ACTIVE.name());
            ps.setString(5, configJson);
            if (credentialBlob == null) {
                ps.setNull(6, Types.BINARY);
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setBytes(6, credentialBlob);
                ps.setString(7, CredentialBundleConverter.ALGORITHM_TAG);
            }
            return ps.executeUpdate();
        }
    }

    @Nullable
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * Decrypts a legacy {@code EncryptedStringConverter} blob ({@code ENC:<base64(iv|cipher)>})
     * and re-encrypts it as a {@link BearerToken} {@link
     * de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle}
     * under {@link CredentialBundleConverter}'s v2 per-row AAD format. Returns {@code null}
     * when the input is null or blank.
     */
    private static byte @Nullable [] rewrapPat(
            @Nullable String encryptedValue,
            long workspaceId,
            IntegrationKind kind,
            String instanceKey,
            EncryptionKeyHolder crypto)
            throws Exception {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        byte[] key = crypto.requireKey();
        String plaintext = decryptLegacy(encryptedValue, key);
        BearerToken bundle = new BearerToken(plaintext, null);
        byte[] bundleJson = MAPPER.writeValueAsBytes(bundle);
        EncryptionContext ctx =
                new EncryptionContext(workspaceId, kind, instanceKey, "connection.credentials_encrypted");
        return encryptV2(bundleJson, key, ctx.toAad());
    }

    /**
     * Mirrors {@link de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter}'s
     * read path. The legacy format is the literal prefix {@code "ENC:"} followed by
     * base64({@code iv || ciphertext}), AES-256-GCM with a static (no-AAD) tag. Values
     * without the prefix are pre-encryption plaintext and pass through unchanged.
     */
    static String decryptLegacy(String dbValue, byte[] keyBytes) throws Exception {
        if (!dbValue.startsWith(LEGACY_PREFIX)) {
            return dbValue;
        }
        byte[] combined = Base64.getDecoder().decode(dbValue.substring(LEGACY_PREFIX.length()));
        if (combined.length < GCM_IV_LENGTH + 1) {
            throw new IllegalStateException("Legacy ciphertext too short: " + combined.length);
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /**
     * Encrypts {@code plaintext} under {@link CredentialBundleConverter}'s v2 format:
     * a single {@code FORMAT_VERSION_V2} version byte followed by the 12-byte IV and
     * the GCM-authenticated ciphertext (128-bit tag). The {@code aad} is the row's
     * {@link EncryptionContext#toAad()} output, binding the blob to its target
     * {@code (workspaceId, kind, instanceKey, columnFqn)} tuple.
     */
    static byte[] encryptV2(byte[] plaintext, byte[] keyBytes, byte[] aad) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        IV_GENERATOR.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        cipher.updateAAD(aad);
        byte[] cipherText = cipher.doFinal(plaintext);

        byte[] combined = new byte[1 + GCM_IV_LENGTH + cipherText.length];
        combined[0] = CredentialBundleConverter.FORMAT_VERSION_V2;
        System.arraycopy(iv, 0, combined, 1, GCM_IV_LENGTH);
        System.arraycopy(cipherText, 0, combined, 1 + GCM_IV_LENGTH, cipherText.length);
        return combined;
    }

    /**
     * Lazy holder for the encryption key. Resolves once on first use so a workspace
     * inventory with zero PATs never has to read the key — fresh DBs and App-only
     * inventories migrate cleanly without the key being present.
     */
    private static final class EncryptionKeyHolder {

        private byte @Nullable [] cachedKey;

        private boolean resolved;

        byte[] requireKey() {
            if (!resolved) {
                cachedKey = resolveKey();
                resolved = true;
            }
            if (cachedKey == null) {
                throw new IllegalStateException(
                        "WorkspaceConnectionBackfillChange found an encrypted credential to rewrap, "
                                + "but hephaestus.security.encryption-key / HEPHAESTUS_SECURITY_ENCRYPTION_KEY is not set. "
                                + "Re-run Liquibase with the same 32-character key the running application uses.");
            }
            return cachedKey;
        }

        private static byte @Nullable [] resolveKey() {
            String key = System.getProperty("hephaestus.security.encryption-key");
            if (key == null || key.isBlank()) {
                key = System.getenv("HEPHAESTUS_SECURITY_ENCRYPTION_KEY");
            }
            if (key == null || key.isBlank()) {
                return null;
            }
            if (key.length() != 32) {
                throw new IllegalArgumentException(
                        "Encryption key must be exactly 32 characters (256 bits). Got: " + key.length());
            }
            return key.getBytes(StandardCharsets.UTF_8);
        }
    }
}
