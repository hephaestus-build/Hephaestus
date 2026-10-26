package de.tum.cit.aet.hephaestus.core.tenancy;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
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
            "user",
            // Synced upstream identity (workspace linked separately via FK)
            "organization",
            "identity_provider",
            "issue_type",
            // Fleet-wide worker JWT revocation; worker JWTs are not workspace-scoped
            "worker_token_denylist",
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
            // Fleet-wide worker liveness/capacity registry; not workspace-scoped
            "worker_registry",
            // Instance-scoped OAuth login providers (sign-in options); shared across all
            // workspaces
            "login_provider",
            // Singleton instance-wide operator settings (silent-mode brake); no tenant
            // dimension exists
            "instance_settings",
            // The releases this instance started; no tenant dimension exists
            "release_start",
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
            // Liquibase machinery
            "databasechangelog",
            "databasechangeloglock");

    private volatile Set<String> scopedTables = Set.of();
    private volatile Set<String> manyToManyJoinTables = Set.of();
    private volatile Map<String, Set<String>> primaryKeyColumns = Map.of();
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
        metamodel.forEachCollectionDescriptor(collection -> {
            addIfScoped(tables, collection.getTableName());
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

    /** True iff the table is a {@code @ManyToMany} join table, per the mapping metamodel. */
    public boolean isManyToManyJoinTable(String tableName) {
        return tableName != null && manyToManyJoinTables.contains(tableName.toLowerCase(Locale.ROOT));
    }

    /** True iff the table requires a {@code workspace_id} predicate on every query. */
    public boolean isScoped(String tableName) {
        return tableName != null && scopedTables.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
