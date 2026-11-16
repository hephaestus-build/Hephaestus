package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims.PodEnd;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SandboxEndTest {

    @Test
    void shouldNameAnOomKillOfTheContainer() {
        var end = SandboxEnd.of(new PodEnd("Running", null, "OOMKilled", 137, 0, false));

        assertThat(end.reason()).isEqualTo(SandboxEnd.Reason.OOM_KILLED);
        assertThat(end.oom()).isTrue();
        assertThat(end.terminationReason()).isEqualTo("OOMKilled");
        assertThat(end.exitCode()).isEqualTo(137);
    }

    @Test
    void shouldNameAnEvictionByThePodsReasonOverItsContainersGenericEnd() {
        var end = SandboxEnd.of(new PodEnd("Failed", "Evicted", "Error", 137, 0, false));

        assertThat(end.reason()).isEqualTo(SandboxEnd.Reason.EVICTED);
        assertThat(end.terminationReason()).isEqualTo("Evicted");
        assertThat(end.oom()).isFalse();
    }

    @Test
    void shouldNameACompletedAndAFailedContainer() {
        assertThat(SandboxEnd.of(new PodEnd("Succeeded", null, "Completed", 0, 0, false))
                        .reason())
                .isEqualTo(SandboxEnd.Reason.COMPLETED);
        assertThat(SandboxEnd.of(new PodEnd("Failed", null, "Error", 1, 0, false))
                        .reason())
                .isEqualTo(SandboxEnd.Reason.ERROR);
    }

    @Test
    void shouldNameAContainerThatStillRanWhenTheServerEndedIt() {
        var end = SandboxEnd.of(new PodEnd("Running", null, null, null, 0, false));

        assertThat(end.reason()).isEqualTo(SandboxEnd.Reason.RUNNING);
        assertThat(end.terminationReason()).isNull();
        assertThat(end.exitCode()).isNull();
    }

    @Test
    void shouldNameAPodThatIsBeingDeletedRunningWhenItHasNoEarlierEnd() {
        // The mapping drops the stop signal's termination of a Pod that is being deleted.
        assertThat(SandboxEnd.of(new PodEnd("Failed", null, null, null, 0, true))
                        .reason())
                .isEqualTo(SandboxEnd.Reason.RUNNING);
        assertThat(SandboxEnd.of(new PodEnd("Running", null, "OOMKilled", 137, 1, true))
                        .reason())
                .as("an earlier end of a Pod that is being deleted still counts")
                .isEqualTo(SandboxEnd.Reason.OOM_KILLED);
    }

    @Test
    void shouldNameAGonePodDeleted() {
        assertThat(SandboxEnd.deleted().reason()).isEqualTo(SandboxEnd.Reason.DELETED);
    }

    @Test
    void shouldNameAFailedReadUnreadWithItsHttpStatusOnly() {
        var refused = SandboxEnd.unread(
                new SandboxClaims.PodReadException(403, "io.fabric8.kubernetes.client.KubernetesClientException"));
        var unanswered = SandboxEnd.unread(
                new SandboxClaims.PodReadException(0, "io.fabric8.kubernetes.client.KubernetesClientException"));
        var other = SandboxEnd.unread(new IllegalStateException("forbidden"));

        assertThat(refused.reason()).isEqualTo(SandboxEnd.Reason.UNREAD);
        assertThat(refused.httpStatus()).isEqualTo(403);
        assertThat(refused.errorType()).isEqualTo("io.fabric8.kubernetes.client.KubernetesClientException");
        assertThat(unanswered.httpStatus()).as("no answer came").isNull();
        assertThat(other.reason()).isEqualTo(SandboxEnd.Reason.UNREAD);
        assertThat(other.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(other.httpStatus()).isNull();
    }

    @Test
    void shouldNameAnUnknownReasonOther() {
        var unknown = SandboxEnd.of(new PodEnd("Failed", null, "SomethingNew with free text", 2, 0, false));
        var deadline = SandboxEnd.of(new PodEnd("Failed", "DeadlineExceeded", null, null, 0, false));
        var pending = SandboxEnd.of(new PodEnd("Pending", null, null, null, 0, false));

        assertThat(unknown.reason()).isEqualTo(SandboxEnd.Reason.OTHER);
        assertThat(unknown.terminationReason())
                .as("a reason that Kubernetes does not set is not logged as it is")
                .isEqualTo("other");
        assertThat(deadline.reason()).isEqualTo(SandboxEnd.Reason.OTHER);
        assertThat(deadline.terminationReason()).isEqualTo("DeadlineExceeded");
        assertThat(pending.reason()).isEqualTo(SandboxEnd.Reason.OTHER);
    }
}
