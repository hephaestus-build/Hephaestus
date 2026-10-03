package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceScopedTables;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Asserts parity between the two tenancy allowlists:
 * <ul>
 *   <li>Production SSOT: {@link WorkspaceScopedTables#GLOBAL_TABLES} (snake_case table
 *       names; consumed by the runtime SQL inspector)</li>
 *   <li>Architecture allowlist: {@code DataIsolationArchitectureTest.GLOBAL_ENTITIES}
 *       (CamelCase entity class names; consumed by the arch tests)</li>
 * </ul>
 *
 * <p>Adding a global entity to one list but not the other causes silent drift: either the
 * runtime inspector flags a legitimately global query (false positive under THROW), or
 * the arch tests miss a workspace-scoped entity that ought to be checked. This test
 * fails fast when the two lists diverge.
 *
 * <p>The arch-test allowlist intentionally excludes Liquibase machinery
 * (databasechangelog, databasechangeloglock) — those aren't @Entity classes. We
 * subtract them when comparing.
 */
@Tag("architecture")
@DisplayName("Tenancy SSOT Parity")
class WorkspaceScopedTablesParityTest {

    @Test
    @DisplayName("GLOBAL_TABLES (prod) and GLOBAL_ENTITIES (arch) describe the same set")
    void productionAndArchAllowlistsMatch() {
        Set<String> archEntitiesSnakeCase = DataIsolationArchitectureTest.GLOBAL_ENTITIES.stream()
                .map(WorkspaceScopedTablesParityTest::toSnakeCase)
                .collect(Collectors.toUnmodifiableSet());

        Set<String> productionTablesExcludingLiquibase = WorkspaceScopedTables.GLOBAL_TABLES.stream()
                .filter(t -> !t.equals("databasechangelog"))
                .filter(t -> !t.equals("databasechangeloglock"))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(productionTablesExcludingLiquibase)
                .as("Production allowlist must match arch-test allowlist (Liquibase tables "
                        + "excluded — those are not @Entity classes). When this fails, update "
                        + "BOTH lists with the same entity in the same commit.")
                .containsExactlyInAnyOrderElementsOf(archEntitiesSnakeCase);
    }

    /** Converts CamelCase entity name to snake_case table name. Matches Hibernate's
     * {@code SpringPhysicalNamingStrategy} default. */
    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
