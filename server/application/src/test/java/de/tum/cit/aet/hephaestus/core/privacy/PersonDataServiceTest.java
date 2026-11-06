package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class PersonDataServiceTest extends BaseUnitTest {
    @Mock
    private PersonIdentityResolver resolver;

    @Mock
    private PersonDataRegistry registry;

    @Mock
    private PersonDataRequestRepository requests;

    @Mock
    private IdentityRestrictionService suppression;

    @Mock
    private PersonEvidenceErasure evidence;

    private final JsonMapper mapper = new JsonMapper();

    private PersonDataService service;

    @BeforeEach
    void createService() {
        service = new PersonDataService(
                resolver,
                registry,
                List.of(evidence),
                requests,
                suppression,
                mock(PersonDataWriteFence.class),
                mock(PersonDataCopyFence.class),
                mock(IssuedJwtRepository.class),
                mock(PlatformTransactionManager.class),
                mapper,
                mock(JdbcTemplate.class),
                new SimpleMeterRegistry());
    }

    @Test
    void shouldStopBeforeSelectingStoresWhenExactIdentityResolutionConflicts() {
        var identity = new PersonIdentity(7L, "42", null);
        when(resolver.resolve(null, List.of(identity)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Identity is linked to another account"));

        assertThatThrownBy(() -> service.preview(1L, null, List.of(identity)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        Mockito.verifyNoInteractions(registry, requests, suppression);
    }

    @Test
    void shouldPreserveRetainedProofJobsWhenEvidenceReceiptsExpandExactIdentityScope() {
        var identity = new PersonIdentity(7L, "42", null);
        UUID retainedJob = new UUID(0, 1);
        UUID copiedJob = new UUID(0, 2);
        var resolved = new PersonScope(
                null, List.of(identity), List.of(), List.of(), List.of(), List.of(), List.of(retainedJob));
        var frozen = new PersonScope(
                null, List.of(identity), List.of(), List.of(), List.of(), List.of(), List.of(retainedJob, copiedJob));
        when(resolver.resolve(null, List.of(identity))).thenReturn(resolved);
        when(evidence.jobsContaining(resolved)).thenReturn(Set.of(retainedJob, copiedJob));
        when(registry.select(frozen)).thenReturn(Map.of());
        when(registry.counts(Map.of())).thenReturn(Map.of());

        var preview = service.preview(1L, null, List.of(identity));

        var scope = mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        assertThat(scope.accountId()).isNull();
        assertThat(scope.derivedJobIds()).containsExactly(retainedJob, copiedJob);
    }
}
