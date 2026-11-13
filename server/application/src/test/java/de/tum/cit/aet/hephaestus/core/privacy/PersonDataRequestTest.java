package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest.State;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PersonDataRequestTest extends BaseUnitTest {

    @Test
    void shouldAdmitFailResumeAndCompleteAnErasureWhenEachMoveIsLegal() {
        var request = new PersonDataRequest();
        request.setScopeJson("{\"accountId\":5}");
        request.setSelectionsJson("{}");

        request.startErasure(7L);
        request.fail("STORE_ERASURE_FAILED");
        assertThat(request.getState()).isEqualTo(State.FAILED);
        assertThat(request.getFailureCode()).isEqualTo("STORE_ERASURE_FAILED");

        request.startErasure(8L);
        assertThat(request.getState()).isEqualTo(State.ERASING);
        assertThat(request.getAdministratorAccountId()).isEqualTo(8L);
        assertThat(request.getFailureCode()).isNull();

        request.complete(Instant.EPOCH);
        assertThat(request.getState()).isEqualTo(State.COMPLETE);
        assertThat(request.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(request.getScopeJson()).isNull();
        assertThat(request.getSelectionsJson()).isNull();
    }

    @Test
    void shouldRefuseToCompleteOrFailAPreviewWhenNoErasureWasAdmitted() {
        var request = new PersonDataRequest();

        assertThatIllegalStateException().isThrownBy(() -> request.complete(Instant.EPOCH));
        assertThatIllegalStateException().isThrownBy(() -> request.fail("STORE_ERASURE_FAILED"));
        assertThat(request.getState()).isEqualTo(State.PREVIEW);
        assertThat(request.getCompletedAt()).isNull();
    }

    @Test
    void shouldRefuseEveryMoveWhenTheErasureIsComplete() {
        var request = new PersonDataRequest();
        request.startErasure(7L);
        request.complete(Instant.EPOCH);

        assertThatIllegalStateException().isThrownBy(() -> request.startErasure(8L));
        assertThatIllegalStateException().isThrownBy(() -> request.fail("STORE_ERASURE_FAILED"));
        assertThatIllegalStateException().isThrownBy(() -> request.complete(Instant.now()));
        assertThat(request.getState()).isEqualTo(State.COMPLETE);
        assertThat(request.getAdministratorAccountId()).isEqualTo(7L);
        assertThat(request.getCompletedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void shouldRefuseToCompleteAFailedErasureWhenItWasNotResumed() {
        var request = new PersonDataRequest();
        request.startErasure(7L);
        request.fail("EXTERNAL_COPIES_REQUIRE_REMOVAL");

        assertThatIllegalStateException().isThrownBy(() -> request.complete(Instant.EPOCH));
        assertThat(request.getState()).isEqualTo(State.FAILED);
        assertThat(request.getFailureCode()).isEqualTo("EXTERNAL_COPIES_REQUIRE_REMOVAL");
    }
}
