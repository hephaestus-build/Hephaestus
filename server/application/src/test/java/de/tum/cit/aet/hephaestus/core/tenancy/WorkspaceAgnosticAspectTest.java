package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.repository.Repository;

class WorkspaceAgnosticAspectTest extends BaseUnitTest {

    interface Reads {
        boolean marked();

        boolean unmarked();
    }

    @WorkspaceAgnostic("type-level marker on a service")
    static class TypeMarkedService implements Reads {

        @Override
        @WorkspaceAgnostic("method-level marker")
        public boolean marked() {
            return TenancyBypass.isActive();
        }

        @Override
        public boolean unmarked() {
            return TenancyBypass.isActive();
        }
    }

    @WorkspaceAgnostic("type-level marker on a repository")
    interface TypeMarkedRepository extends Repository<Object, Long> {
        boolean read();
    }

    private static <T> T advised(T target, Class<T> proxied) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(WorkspaceAgnosticAspect.class);
        return proxied.cast(factory.getProxy());
    }

    @Test
    void shouldOpenNoBypassForAMarkerOnTheType() {
        Reads service = advised(new TypeMarkedService(), Reads.class);

        assertThat(service.unmarked()).isFalse();
    }

    @Test
    void shouldOpenNoBypassForAMarkerOnARepositoryInterface() {
        TypeMarkedRepository repository = TenancyBypass::isActive;

        TypeMarkedRepository advised = advised(repository, TypeMarkedRepository.class);

        assertThat(advised.read()).isFalse();
    }

    @Test
    void shouldOpenTheBypassOnlyWhileAMarkedMethodRuns() {
        Reads service = advised(new TypeMarkedService(), Reads.class);

        assertThat(service.marked()).isTrue();
        assertThat(TenancyBypass.isActive()).isFalse();
    }
}
