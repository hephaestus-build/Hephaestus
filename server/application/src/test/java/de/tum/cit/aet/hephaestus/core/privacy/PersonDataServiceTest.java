package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    private de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository issuedTokens;

    @Mock
    private PersonSuppressionService suppression;

    @Mock
    private de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence writeFence;

    @Mock
    private PlatformTransactionManager transactions;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private PersonDataService service;

    @Test
    void shouldStopBeforeSelectingStoresWhenExactIdentityResolutionConflicts() {
        var identity = new PersonIdentity(7L, "42", null);
        when(resolver.resolve(null, List.of(identity)))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Identity is linked to another account"));

        assertThatThrownBy(() -> service.preview(1L, null, List.of(identity)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        org.mockito.Mockito.verifyNoInteractions(registry, requests, suppression);
    }
}
