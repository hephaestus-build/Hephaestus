package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkspaceScopedTablesTest extends BaseUnitTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EntityManagerFactory> providerOf(EntityManagerFactory emf) {
        ObjectProvider<EntityManagerFactory> provider = mock(ObjectProvider.class);
        lenient().when(provider.getObject()).thenReturn(emf);
        return provider;
    }

    @Test
    void globalTablesContainsTheKnownAllowlist() {
        // Sanity check that the constant is non-empty and includes the tenant root.
        // The full allowlist is asserted as identical to DataIsolationArchitectureTest's
        // GLOBAL_ENTITIES (case-converted) — see WorkspaceScopedTablesParityTest.
        assertThat(WorkspaceScopedTables.GLOBAL_TABLES)
                .contains("workspace", "user", "identity_provider", "databasechangelog");
    }

    @Test
    void scopedTablesIsEmptyBeforeApplicationReady() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        WorkspaceScopedTables tables = new WorkspaceScopedTables(providerOf(emf));
        assertThat(tables.scopedTables()).isEmpty();
        assertThat(tables.isScoped("pull_request")).isFalse();
    }

    @Test
    void failFastWhenHibernateUnwrapFails() {
        // Simulate Hibernate API regression: EntityManagerFactory.unwrap(SessionFactory)
        // throws. The populate routine should propagate the failure instead of leaving
        // scopedTables silently empty — that would turn the inspector into a no-op.
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        Metamodel metamodel = mock(Metamodel.class);
        lenient().when(emf.getMetamodel()).thenReturn(metamodel);
        EntityType<?> entity = mock(EntityType.class);
        lenient().when(metamodel.getEntities()).thenReturn(Set.of(entity));
        lenient()
                .when(emf.unwrap(SessionFactory.class))
                .thenThrow(new IllegalStateException("simulated Hibernate API regression"));

        WorkspaceScopedTables tables = new WorkspaceScopedTables(providerOf(emf));
        assertThatThrownBy(tables::populateFromMetamodel).isInstanceOf(IllegalStateException.class);
    }
}
