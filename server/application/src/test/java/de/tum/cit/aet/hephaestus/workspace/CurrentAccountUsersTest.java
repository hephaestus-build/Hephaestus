package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentAccountUsersTest extends BaseUnitTest {
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final CurrentAccountUsers resolver = new CurrentAccountUsers(identities, providers);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepLinkOrderAndDeduplicateRepeatedActors() {
        when(providers.accountActors(42L)).thenReturn(List.of(actor(1L, 7L), actor(2L, 8L), actor(1L, 7L)));
        assertThat(resolver.resolve(42L)).extracting(ActorProfile::actorId).containsExactly(7L, 8L);
    }

    @Test
    void shouldResolveAuthenticatedAccountWithoutUsingTheLoginClaim() {
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext("42", new String[0], "token"));
        when(providers.accountActors(42L)).thenReturn(List.of(actor(1L, 7L)));
        assertThat(resolver.resolve()).extracting(ActorProfile::actorId).containsExactly(7L);
    }

    @Test
    void shouldReturnNoActorsWhenNoAccountIsAuthenticated() {
        assertThat(resolver.resolve()).isEmpty();
        verifyNoInteractions(identities, providers);
    }

    private static ActorProfile actor(long providerId, long actorId) {
        return new ActorProfile(actorId, providerId, "GITHUB", "https://github.com", "123", "alice", "alice", true);
    }
}
