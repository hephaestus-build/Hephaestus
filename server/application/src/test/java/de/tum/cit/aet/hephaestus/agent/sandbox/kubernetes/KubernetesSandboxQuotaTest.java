package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KubernetesSandboxQuotaTest extends BaseUnitTest {

    /** The default review sandbox: 3 GiB and one CPU. */
    private static final ResourceLimits LIMITS =
            new ResourceLimits(3L * 1024 * 1024 * 1024, 1.0, 256, Duration.ofMinutes(30));

    private static final Map<String, Quantity> REVIEW = KubernetesSandboxQuota.footprint(LIMITS, Map.of());

    @Test
    void shouldAdmitNoReviewWhenTheQuotaHoldsLessMemoryThanItsSandbox() {
        // Staging's refusal: 7.38 of 9.5 GiB requested by the Heph host and the turn sandboxes.
        var quota = quota(
                Map.of("requests.memory", "9728Mi", "pods", "10"),
                Map.of("requests.memory", "7381975040", "pods", "4"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW)).isZero();
    }

    @Test
    void shouldAdmitAsManyReviewsAsTheTightestResourceHolds() {
        var quota = quota(
                Map.of("requests.memory", "16Gi", "limits.cpu", "12", "pods", "12"),
                Map.of("requests.memory", "4Gi", "limits.cpu", "10500m", "pods", "3"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW))
                .as("1.5 free CPUs hold one review's CPU limit, though 12 GiB would hold four")
                .isEqualTo(1);
    }

    @Test
    void shouldBoundByEveryQuotaWhenTheNamespaceHasSeveral() {
        var chart = quota(Map.of("requests.memory", "46Gi"), Map.of("requests.memory", "0"));
        var operator = quota(Map.of("pods", "5"), Map.of("pods", "3"));

        assertThat(KubernetesSandboxQuota.fits(List.of(chart, operator), REVIEW))
                .isEqualTo(2);
    }

    @Test
    void shouldSetNoBoundWhenNoQuotaLimitsWhatAReviewUses() {
        var storageOnly = quota(Map.of("requests.storage", "6Gi"), Map.of("requests.storage", "6Gi"));
        var unobserved = new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("new")
                .endMetadata()
                .build();

        assertThat(KubernetesSandboxQuota.fits(List.of(), REVIEW)).isEqualTo(Integer.MAX_VALUE);
        assertThat(KubernetesSandboxQuota.fits(List.of(storageOnly, unobserved), REVIEW))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldTellAQuotaSmallerThanOneReviewFromAFullOne() {
        var full = quota(Map.of("requests.memory", "9728Mi"), Map.of("requests.memory", "9728Mi"));
        var tooSmall = quota(Map.of("requests.memory", "2Gi"), Map.of("requests.memory", "0"));

        assertThat(KubernetesSandboxQuota.neverFits(List.of(full), REVIEW))
                .as("a full quota frees room when its sandboxes end")
                .isFalse();
        assertThat(KubernetesSandboxQuota.neverFits(List.of(full, tooSmall), REVIEW))
                .as("2 GiB never hold a review of 3 GiB")
                .isTrue();
    }

    @Test
    void shouldNotJudgeAScopedOrUnobservedQuotaTooSmall() {
        var terminating = quota(Map.of("requests.memory", "1Gi"), Map.of("requests.memory", "0"));
        terminating.setSpec(
                new ResourceQuotaSpecBuilder().withScopes("Terminating").build());
        var unobserved = new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("new")
                .endMetadata()
                .build();

        assertThat(KubernetesSandboxQuota.neverFits(List.of(terminating, unobserved), REVIEW))
                .isFalse();
    }

    @Test
    void shouldAdmitNothingWhenAQuotaIsOverspent() {
        var overspent = quota(Map.of("pods", "4"), Map.of("pods", "6"));

        assertThat(KubernetesSandboxQuota.fits(List.of(overspent), REVIEW)).isZero();
    }

    @Test
    void shouldCountThePodWhenAQuotaNamesItAsAnObjectCount() {
        var quota = quota(Map.of("count/pods", "5"), Map.of("count/pods", "4"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW)).isEqualTo(1);
    }

    @Test
    void shouldChargeTheRuntimeClassOverheadToRequestsAndLimits() {
        var quota = quota(
                Map.of("requests.memory", "6656Mi", "limits.memory", "6656Mi"),
                Map.of("requests.memory", "0", "limits.memory", "0"));
        var withOverhead = KubernetesSandboxQuota.footprint(LIMITS, Map.of("memory", new Quantity("512Mi")));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW)).isEqualTo(2);
        assertThat(KubernetesSandboxQuota.fits(List.of(quota), withOverhead))
                .as("6.5 GiB hold one 3.5 GiB Pod")
                .isEqualTo(1);
    }

    @Test
    void shouldLeaveAScopedQuotaToTheAdmissionWhenItMayNotCountTheReview() {
        var terminating = quota(Map.of("pods", "2"), Map.of("pods", "2"));
        terminating.setSpec(
                new ResourceQuotaSpecBuilder().withScopes("Terminating").build());

        assertThat(KubernetesSandboxQuota.fits(List.of(terminating), REVIEW)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldClaimAsBeforeWhenTheQuotasCannotBeRead() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.resourceQuotas()).thenThrow(new KubernetesClientException("forbidden", 403, null));

        assertThat(new KubernetesSandboxQuota(client, "sandboxes", "")
                        .roomFor(new ResourceLimits(3L * 1024 * 1024 * 1024, 1.0, 256, Duration.ofMinutes(30))))
                .isEqualTo(Integer.MAX_VALUE);
    }

    private static ResourceQuota quota(Map<String, String> hard, Map<String, String> used) {
        return new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("hephaestus-sandbox-quota")
                .endMetadata()
                .withNewStatus()
                .withHard(quantities(hard))
                .withUsed(quantities(used))
                .endStatus()
                .build();
    }

    private static Map<String, Quantity> quantities(Map<String, String> amounts) {
        return amounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new Quantity(entry.getValue())));
    }
}
