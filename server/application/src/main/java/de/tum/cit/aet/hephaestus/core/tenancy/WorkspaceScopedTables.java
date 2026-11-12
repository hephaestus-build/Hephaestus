package de.tum.cit.aet.hephaestus.core.tenancy;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.TableDetails;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Use physical Hibernate table names, including join tables: joins share their parent tenancy boundary.
 * Adding a table to {@link #GLOBAL_TABLES} is a security decision.
 */
@Component
public class WorkspaceScopedTables {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopedTables.class);

    public static final Set<String> GLOBAL_TABLES = Set.of(
            // Tenant root + identity
            "workspace",
            "workspace_slug_history",
            "workspace_slug_reservation",
            // GitHub App installations before an instance admin decides whether they get a workspace
            "github_installation_request",
            "user",
            // Synced upstream identity (workspace linked separately via FK)
            "organization",
            "identity_provider",
            "issue_type",
            // core.auth (ADR 0017) — identity is user/system-scoped, not workspace-scoped.
            // Account ↔ Workspace association lives on membership, not these tables.
            "account",
            "identity",
            "account_feature",
            "auth_event",
            "issued_jwt",
            "jwt_signing_key",
            // Installed-client sessions and their single-use sign-in handoffs;
            // account-scoped like issued_jwt
            "client_session",
            "client_sign_in_handoff",
            // Passkey credentials, challenges, and recovery hashes belong to an account across workspaces.
            "passkey_credential",
            "passkey_challenge",
            "passkey_recovery_code",
            // GDPR Art. 20 self-service export — account-scoped, spans a principal's data
            // across workspaces.
            "account_export",
            // Instance-wide rights requests and exact provider-identity controls.
            "person_data_request",
            "identity_restriction",
            // Account-scoped consent evidence; spans workspaces
            "consent_decision",
            // Fleet-wide public keys that verify attempt tokens; a token's workspace is a signed claim
            "attempt_public_key",
            // Installation-wide tools Pods; a bound row names its turn's workspace and is fenced on the turn's lease
            "conversation_tools_pod",
            // Instance-scoped OAuth login providers (sign-in options); shared across all
            // workspaces
            "login_provider",
            // Singleton instance-wide operator settings (silent-mode brake); no tenant
            // dimension exists
            "instance_settings",
            // The releases this instance started; no tenant dimension exists
            "release_start",
            // Installation deliveries have no workspace until their handler creates one.
            "installation_inbox_step",
            // Native-account email subscriptions are independent of workspace membership.
            "notification_subscription",
            // Instance LLM-config catalog — app_admin-owned, curated, shared across all
            // workspaces
            "llm_connection",
            // Instance-curated models behind a catalog connection; global, not
            // tenant-scoped
            "llm_model",
            // Instance model price history; global pricing authority, not tenant-scoped
            "llm_model_price",
            // Shared provider capacity and the calls in flight in it; the instance admin owns it, and every workspace
            // whose models are in a pool shares it
            "llm_call_pool",
            "llm_call_permit",
            // Product feedback goes to the instance's own repositories (ADR 0073): the destination singleton, each
            // report's filing (deleted with its product_feedback row), account-owned sharing choices, and locators
            // left after erasure
            "product_feedback_destination",
            "product_feedback_filing",
            "product_feedback_consent",
            "product_feedback_cleanup",
            // llm_model_workspace_grant is deliberately absent: it carries a workspace_id,
            // so the
            // inspector watches it and its cross-tenant admin reads opt out per method
            // instead.
            // Instance LLM settings singleton (egress allowlist + BYO enable); global
            "instance_llm_settings",
            // Instance-wide practice catalog overrides; no workspace owner
            "catalog_entry",
            // ECB daily reference rates for display-only currency conversion; not a
            // tenant's property
            "fx_rate",
            // numeric record of corrected commit links; it spans workspaces like the shared rows it corrects
            "commit_identity_change",
            // Liquibase machinery
            "databasechangelog",
            "databasechangeloglock");

    /**
     * The provider mirror: one row per provider object, shared by every workspace that monitors it, so no row
     * carries a {@code workspace_id} (ADR 0077). A read reaches the workspace through the monitored scope, and
     * {@link WorkspaceStatementInspector} requires that read to bind the workspace.
     */
    public static final Set<String> MIRROR_TABLES = Set.of(
            "commit_contributor",
            "commit_file_change",
            "commit_identity_resolution",
            "commit_pull_request",
            "git_commit",
            "issue",
            "issue_assignee",
            "issue_blocking",
            "issue_comment",
            "issue_label",
            "label",
            "milestone",
            "organization_membership",
            "pull_request_closing_issue",
            "pull_request_requested_reviewers",
            "pull_request_requested_team",
            "pull_request_review",
            "pull_request_review_comment",
            "pull_request_review_thread",
            "repository",
            "repository_collaborator",
            "team",
            "team_membership",
            "team_repository_permission");

    private volatile Set<String> scopedTables = Set.of();
    private volatile Set<String> manyToManyJoinTables = Set.of();
    private volatile Map<String, Set<String>> primaryKeyColumns = Map.of();
    private volatile Map<String, Set<String>> collectionKeyColumns = Map.of();
    private volatile Map<String, Set<String>> ownedCollectionKeyColumns = Map.of();
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    // Hibernate customizers consume this bean while the factory is being built.
    // Resolve the factory after ApplicationReadyEvent to avoid that startup cycle.
    public WorkspaceScopedTables(ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider) {
        this.entityManagerFactoryProvider = entityManagerFactoryProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    void populateFromMetamodel() {
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getObject();
        SessionFactoryImplementor sf =
                entityManagerFactory.unwrap(SessionFactory.class).unwrap(SessionFactoryImplementor.class);
        MappingMetamodel metamodel = sf.getMappingMetamodel();
        Set<EntityType<?>> entities = entityManagerFactory.getMetamodel().getEntities();

        Set<String> tables = new TreeSet<>();
        Map<String, Set<String>> keys = new HashMap<>();
        for (EntityType<?> entity : entities) {
            Class<?> javaType = entity.getJavaType();
            if (javaType == null) continue;
            EntityPersister persister = metamodel.getEntityDescriptor(javaType);
            var tableDetails = persister.getMappedTableDetails();
            addIfScoped(tables, tableDetails.getTableName());
            recordPrimaryKey(keys, tableDetails);
        }
        // Many-to-many join tables + element-collection tables: physical tables with no
        // workspace_id column of their own. They inherit the parent's tenancy boundary,
        // and a bare SELECT against them leaks cross-workspace if not joined to a scoped
        // parent. Collections owned via a foreign-key column on the child entity resolve
        // to the child's own table — already added in the entity walk above.
        Set<String> joinTables = new TreeSet<>();
        Map<String, Set<String>> collectionKeys = new HashMap<>();
        Map<String, Set<String>> ownedCollectionKeys = new HashMap<>();
        metamodel.forEachCollectionDescriptor(collection -> {
            addIfScoped(tables, collection.getTableName());
            // Only a workspace-owned parent pins its collection to one workspace: a user or a provider mirror row is
            // shared, so its collections span workspaces.
            String owner = normalize(
                    collection.getOwnerEntityPersister().getMappedTableDetails().getTableName());
            if (!GLOBAL_TABLES.contains(owner) && !MIRROR_TABLES.contains(owner)) {
                var key = collection.getAttributeMapping().getKeyDescriptor();
                recordCollectionKey(collectionKeys, key);
                // Hibernate clears a collection by its key only from the owning side.
                if (!collection.isInverse()) recordCollectionKey(ownedCollectionKeys, key);
            }
            // A many-to-many join table is the one shape whose rows Hibernate deletes by
            // both
            // foreign keys; the inspector allows exactly that statement, for exactly these
            // tables.
            if (collection.isManyToMany()) addIfScoped(joinTables, collection.getTableName());
        });

        // Fail-fast: zero scoped tables from a non-empty entity set means the Hibernate
        // mapping metamodel call chain regressed. A silently-empty set would turn the
        // inspector into a no-op — worst failure mode for a security control.
        if (tables.isEmpty() && !entities.isEmpty()) {
            throw new IllegalStateException("WorkspaceScopedTables populated zero scoped tables from "
                    + entities.size()
                    + " entities. Tenancy enforcement would silently disable. "
                    + "Check Hibernate MappingMetamodel API compatibility.");
        }
        this.scopedTables = Set.copyOf(tables);
        this.manyToManyJoinTables = Set.copyOf(joinTables);
        this.primaryKeyColumns = Map.copyOf(keys);
        this.collectionKeyColumns = Map.copyOf(collectionKeys);
        this.ownedCollectionKeyColumns = Map.copyOf(ownedCollectionKeys);
        log.info(
                "WorkspaceScopedTables populated: {} workspace-scoped tables (incl. join tables)," + " {} global",
                scopedTables.size(),
                GLOBAL_TABLES.size());
    }

    /**
     * The table's complete primary key, as the physical column names the inspector will read out of
     * a WHERE clause. An entity whose key columns cannot all be named contributes nothing, so the
     * inspector's lookup comes back empty and the statement falls through to the normal check.
     */
    private static void recordPrimaryKey(Map<String, Set<String>> keys, TableDetails tableDetails) {
        String table = normalize(tableDetails.getTableName());
        if (table.isEmpty()) return;
        Set<String> columns = new HashSet<>();
        for (TableDetails.KeyColumn column : tableDetails.getKeyDetails().getKeyColumns()) {
            String name = normalize(column.getColumnName());
            if (name.isEmpty()) return;
            columns.add(name);
        }
        if (!columns.isEmpty()) keys.put(table, Set.copyOf(columns));
    }

    /**
     * The column by which Hibernate loads or clears a mapped collection: the parent's foreign key in the child or join
     * table. A composite key contributes nothing, because the single-key forms that consult this cannot name it.
     */
    private static void recordCollectionKey(Map<String, Set<String>> keys, ForeignKeyDescriptor key) {
        String table = normalize(key.getKeyTable());
        if (table.isEmpty()) return;
        List<String> columns = new ArrayList<>();
        key.getKeyPart()
                .forEachSelectable((index, selectable) -> columns.add(normalize(selectable.getSelectionExpression())));
        if (columns.size() != 1 || columns.getFirst().isEmpty()) return;
        keys.computeIfAbsent(table, unused -> new HashSet<>()).add(columns.getFirst());
    }

    /** Bare, unquoted, lowercase — the form the inspector compares against. */
    private static String normalize(String rawName) {
        if (rawName == null) return "";
        return rawName.substring(rawName.lastIndexOf('.') + 1).replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static void addIfScoped(Set<String> tables, String rawName) {
        if (rawName == null || rawName.isBlank()) return;
        // The inspector compares bare, lowercase names; a schema-qualified or quoted metamodel
        // name must land in the same form or it never matches.
        String name = normalize(rawName);
        if (!GLOBAL_TABLES.contains(name)) {
            tables.add(name);
        }
    }

    /** Workspace-scoped physical table names (lowercase). Empty until ApplicationReady fires. */
    public Set<String> scopedTables() {
        return scopedTables;
    }

    /**
     * The table's complete primary-key columns per the mapping metamodel, or empty when the table
     * is unknown — before {@link ApplicationReadyEvent}, or for a table no entity maps. Empty is
     * the fail-closed answer: a caller comparing a WHERE clause against it can never conclude the
     * clause covers the whole key.
     */
    public Set<String> primaryKeyColumns(String tableName) {
        if (tableName == null) return Set.of();
        return primaryKeyColumns.getOrDefault(tableName.toLowerCase(Locale.ROOT), Set.of());
    }

    /**
     * True iff a single {@code column = ?} pins {@code tableName} to rows its caller already held: the column is the
     * table's whole primary key, or the key by which a workspace-owned parent loads ({@code write} false) or clears
     * ({@code write} true) a mapped collection. Any other foreign key, such as the one a derived
     * {@code findByDeveloperId} reads, is not a key here.
     */
    public boolean isSingleKeyColumn(String tableName, String column, boolean write) {
        if (tableName == null || column == null) return false;
        String table = tableName.toLowerCase(Locale.ROOT);
        String name = column.replace("\"", "").toLowerCase(Locale.ROOT);
        var collectionKeys = write ? ownedCollectionKeyColumns : collectionKeyColumns;
        return primaryKeyColumns.getOrDefault(table, Set.of()).equals(Set.of(name))
                || collectionKeys.getOrDefault(table, Set.of()).contains(name);
    }

    /** True iff the table is a {@code @ManyToMany} join table, per the mapping metamodel. */
    public boolean isManyToManyJoinTable(String tableName) {
        return tableName != null && manyToManyJoinTables.contains(tableName.toLowerCase(Locale.ROOT));
    }

    /** True iff the table requires a {@code workspace_id} predicate on every query. */
    public boolean isScoped(String tableName) {
        return tableName != null && scopedTables.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
