package de.tum.cit.aet.hephaestus.core.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountLifecycleTest extends BaseUnitTest {

    @Test
    void shouldClaimBlockUnblockAndDeleteAnAccountWhenEachMoveIsLegal() {
        var account = Account.unclaimed("Roster person");
        assertThat(account.getStatus()).isEqualTo(Status.UNCLAIMED);

        account.claim();
        account.block();
        assertThat(account.getStatus()).isEqualTo(Status.BLOCKED);
        account.unblock();
        assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);

        account.startDeletion(Instant.EPOCH);
        assertThat(account.getStatus()).isEqualTo(Status.DELETING);
        assertThat(account.getDeletedAt()).isEqualTo(Instant.EPOCH);

        account.markDeleted();
        account.markDeleted();
        assertThat(account.getStatus()).isEqualTo(Status.DELETED);
    }

    @Test
    void shouldStartTheDeletionOfAnUnclaimedOrBlockedAccount() {
        var unclaimed = Account.unclaimed("Roster person");
        unclaimed.startDeletion(Instant.EPOCH);
        assertThat(unclaimed.getStatus()).isEqualTo(Status.DELETING);

        var blocked = new Account("Blocked person");
        blocked.block();
        blocked.startDeletion(Instant.EPOCH);
        assertThat(blocked.getStatus()).isEqualTo(Status.DELETING);
    }

    @Test
    void shouldRefuseToClaimUnblockOrDeleteAnActiveAccountOutOfTurn() {
        var account = new Account("Signed-in person");

        assertThatIllegalStateException().isThrownBy(account::claim);
        assertThatIllegalStateException().isThrownBy(account::unblock);
        assertThatIllegalStateException().isThrownBy(account::markDeleted);
        assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(account.getDeletedAt()).isNull();
    }

    @Test
    void shouldRefuseToBlockAnAccountThatNobodyHasClaimed() {
        var account = Account.unclaimed("Roster person");

        assertThatIllegalStateException().isThrownBy(account::block);
        assertThat(account.getStatus()).isEqualTo(Status.UNCLAIMED);
    }

    @Test
    void shouldRefuseEveryWayBackWhenTheDeletionHasStarted() {
        var account = new Account("Leaving person");
        account.startDeletion(Instant.EPOCH);

        assertThatIllegalStateException().isThrownBy(account::block);
        assertThatIllegalStateException().isThrownBy(account::unblock);
        assertThatIllegalStateException().isThrownBy(account::claim);
        assertThatIllegalStateException().isThrownBy(() -> account.startDeletion(Instant.now()));
        assertThat(account.getStatus()).isEqualTo(Status.DELETING);
        assertThat(account.getDeletedAt()).isEqualTo(Instant.EPOCH);

        account.markDeleted();
        assertThatIllegalStateException().isThrownBy(() -> account.startDeletion(Instant.now()));
        assertThatIllegalStateException().isThrownBy(account::unblock);
        assertThat(account.getStatus()).isEqualTo(Status.DELETED);
    }
}
