package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class PersonDataServiceTest extends BaseUnitTest {
    @Mock
    private PersonIdentityResolver resolver;

    @Mock
    private PersonDataRegistry registry;

    @Mock
    private PersonDataRequestRepository requests;

    @Mock
    private PersonSuppressionService suppression;

    private PersonDataService service;

    @BeforeEach
    void createService() {
        service = new PersonDataService(
                resolver,
                registry,
                List.of(),
                requests,
                suppression,
                mock(PersonDataWriteFence.class),
                mock(PersonDataCopyFence.class),
                mock(IssuedJwtRepository.class),
                mock(PlatformTransactionManager.class),
                mock(ObjectMapper.class),
                mock(JdbcTemplate.class));
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
}
