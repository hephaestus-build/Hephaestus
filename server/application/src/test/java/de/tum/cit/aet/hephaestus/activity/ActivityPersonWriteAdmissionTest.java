package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ActivityPersonWriteAdmissionTest extends BaseUnitTest {
    @Mock
    private ActivityScmFacts users;

    @Mock
    private PersonDataWriteFence fence;

    @Test
    void shouldAdmitOnlyTheExactNativeIdentityInAMixedSuppressedBatch() {
        var permitted = actor(91L, 8L, 4242L);
        var suppressed = actor(92L, 8L, 4243L);
        var first = new PersonIdentity(8L, "4242", null);
        var second = new PersonIdentity(8L, "4243", null);
        when(users.users(List.of(91L, 92L))).thenReturn(List.of(permitted, suppressed));
        when(fence.holdForWrite(List.of(first, second))).thenReturn(false);
        when(fence.holdForWrite(List.of(first))).thenReturn(true);
        when(fence.holdForWrite(List.of(second))).thenReturn(false);
        assertThat(new ActivityPersonWriteAdmission(users, fence).holdActors(List.of(91L, 92L)))
                .containsExactly(91L);
    }

    @Test
    void shouldRefuseAMissingActorWithoutInventingAnIdentity() {
        when(users.user(91L)).thenReturn(Optional.empty());
        assertThat(new ActivityPersonWriteAdmission(users, fence).holdActor(91L))
                .isFalse();
        verifyNoInteractions(fence);
    }

    @Test
    void shouldKeepEqualNativeKeysFromDifferentProvidersDistinct() {
        var first = actor(91L, 8L, 4242L);
        var second = actor(92L, 9L, 4242L);
        when(users.users(List.of(91L, 92L))).thenReturn(List.of(first, second));
        when(fence.holdForWrite(List.of(new PersonIdentity(8L, "4242", null), new PersonIdentity(9L, "4242", null))))
                .thenReturn(true);
        assertThat(new ActivityPersonWriteAdmission(users, fence).holdActors(List.of(91L, 92L)))
                .containsExactly(91L, 92L);
    }

    private static User actor(long id, long providerId, long nativeId) {
        var provider = new IdentityProvider();
        provider.setId(providerId);
        var actor = new User();
        actor.setId(id);
        actor.setNativeId(nativeId);
        actor.setProvider(provider);
        return actor;
    }
}
