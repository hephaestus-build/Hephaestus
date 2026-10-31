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
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KubernetesSandboxQuotaTest extends BaseUnitTest {

    /** The default review sandbox: one CPU. Its memory and disk come from {@link KubernetesPods#REVIEW}. */
    private static final ResourceLimits LIMITS = new ResourceLimits(1.0, Duration.ofMinutes(30));

    private static final Map<String, Quantity> REVIEW = KubernetesSandboxQuota.footprint(LIMITS, Map.of());

    /** The PriorityClass of review Pods, which the chart's reviews tier selects. */
    private static final String REVIEWS = "hephaestus-heph-reviews";

    @Test
    void shouldAdmitNoReviewWhenTheQuotaHoldsLessMemoryThanItsSandbox() {
        // 9 of 9.5 GiB requested by the Hephaestus host and the turn sandboxes: 512 MiB is free, a review requests 640.
        var quota = quota(
                Map.of("requests.memory", "9728Mi", "pods", "10"), Map.of("requests.memory", "9216Mi", "pods", "4"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW, REVIEWS)).isZero();
    }

    @Test
    void shouldAdmitAsManyReviewsAsTheTightestResourceHolds() {
        var quota = quota(
                Map.of("requests.memory", "16Gi", "limits.cpu", "12", "pods", "12"),
                Map.of("requests.memory", "4Gi", "limits.cpu", "10500m", "pods", "3"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW, REVIEWS))
                .as("1.5 free CPUs hold one review's CPU limit, though 12 GiB would hold nineteen")
                .isEqualTo(1);
    }

    @Test
    void shouldBoundByEveryQuotaWhenTheNamespaceHasSeveral() {
        var chart = quota(Map.of("requests.memory", "46Gi"), Map.of("requests.memory", "0"));
        var operator = quota(Map.of("pods", "5"), Map.of("pods", "3"));

        assertThat(KubernetesSandboxQuota.fits(List.of(chart, operator), REVIEW, REVIEWS))
                .isEqualTo(2);
    }

    @Test
    void shouldSetNoBoundWhenNoQuotaLimitsWhatAReviewUses() {
        var storageOnly = quota(Map.of("requests.storage", "6Gi"), Map.of("requests.storage", "6Gi"));

        assertThat(KubernetesSandboxQuota.fits(List.of(), REVIEW, REVIEWS)).isEqualTo(Integer.MAX_VALUE);
        assertThat(KubernetesSandboxQuota.fits(List.of(storageOnly), REVIEW, REVIEWS))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldWaitForAQuotaWhoseStatusIsNotObserved() {
        var unobserved = new ResourceQuotaBuilder().build();
        var missingHard = new ResourceQuotaBuilder().withNewStatus().endStatus().build();

        assertThat(KubernetesSandboxQuota.fits(List.of(unobserved), REVIEW, REVIEWS))
                .isEqualTo(-1);
        assertThat(KubernetesSandboxQuota.fits(List.of(missingHard), REVIEW, REVIEWS))
                .isEqualTo(-1);
        assertThat(KubernetesSandboxQuota.neverFits(List.of(unobserved, missingHard), REVIEW, REVIEWS))
                .isFalse();
    }

    @Test
    void shouldWaitForEveryRelevantUsageCounterInsteadOfAssumingZero() {
        var missingUsed = quota(Map.of("pods", "4"), Map.of());
        missingUsed.getStatus().setUsed(null);
        var incomplete = quota(Map.of("pods", "4", "requests.memory", "16Gi"), Map.of("pods", "0"));

        assertThat(KubernetesSandboxQuota.fits(List.of(missingUsed), REVIEW, REVIEWS))
                .isEqualTo(-1);
        assertThat(KubernetesSandboxQuota.fits(List.of(incomplete), REVIEW, REVIEWS))
                .isEqualTo(-1);
        incomplete.getStatus().getUsed().put("requests.memory", new Quantity("0"));
        assertThat(KubernetesSandboxQuota.fits(List.of(incomplete), REVIEW, REVIEWS))
                .isEqualTo(4);
    }

    @Test
    void shouldIgnoreUnobservedCountersFromAnotherTier() {
        var conversations = tierQuota("hephaestus-heph-conversations", Map.of("pods", "2"), Map.of());
        conversations.setStatus(null);

        assertThat(KubernetesSandboxQuota.fits(List.of(conversations), REVIEW, REVIEWS))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldTellAQuotaSmallerThanOneReviewFromAFullOne() {
        var full = quota(Map.of("requests.memory", "9728Mi"), Map.of("requests.memory", "9728Mi"));
        var tooSmall = quota(Map.of("requests.memory", "512Mi"), Map.of("requests.memory", "0"));

        assertThat(KubernetesSandboxQuota.neverFits(List.of(full), REVIEW, REVIEWS))
                .as("a full quota frees room when its sandboxes end")
                .isFalse();
        assertThat(KubernetesSandboxQuota.neverFits(List.of(full, tooSmall), REVIEW, REVIEWS))
                .as("512 MiB never hold a review that requests 640 MiB")
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

        assertThat(KubernetesSandboxQuota.neverFits(List.of(terminating, unobserved), REVIEW, REVIEWS))
                .isFalse();
    }

    @Test
    void shouldAdmitNothingWhenAQuotaIsOverspent() {
        var overspent = quota(Map.of("pods", "4"), Map.of("pods", "6"));

        assertThat(KubernetesSandboxQuota.fits(List.of(overspent), REVIEW, REVIEWS))
                .isZero();
    }

    @Test
    void shouldCountTheReviewsNodeDiskWhenAQuotaBoundsIt() {
        var quota = quota(Map.of("requests.ephemeral-storage", "3Gi"), Map.of("requests.ephemeral-storage", "512Mi"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW, REVIEWS))
                .as("a review requests 1 GiB of node disk for its scratch")
                .isEqualTo(2);
    }

    @Test
    void shouldCountThePodWhenAQuotaNamesItAsAnObjectCount() {
        var quota = quota(Map.of("count/pods", "5"), Map.of("count/pods", "4"));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW, REVIEWS)).isEqualTo(1);
    }

    @Test
    void shouldChargeTheRuntimeClassOverheadToRequestsAndLimits() {
        var quota = quota(
                Map.of("requests.memory", "2560Mi", "limits.memory", "2560Mi"),
                Map.of("requests.memory", "0", "limits.memory", "0"));
        var withOverhead = KubernetesSandboxQuota.footprint(LIMITS, Map.of("memory", new Quantity("512Mi")));

        assertThat(KubernetesSandboxQuota.fits(List.of(quota), REVIEW, REVIEWS)).isEqualTo(2);
        assertThat(KubernetesSandboxQuota.fits(List.of(quota), withOverhead, REVIEWS))
                .as("2.5 GiB of limits hold one Pod limited to 1.75 GiB")
                .isEqualTo(1);
    }

    @Test
    void shouldLeaveAScopedQuotaToTheAdmissionWhenItMayNotCountTheReview() {
        var terminating = quota(Map.of("pods", "2"), Map.of("pods", "2"));
        terminating.setSpec(
                new ResourceQuotaSpecBuilder().withScopes("Terminating").build());

        assertThat(KubernetesSandboxQuota.fits(List.of(terminating), REVIEW, REVIEWS))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldBoundReviewsByTheirTierWhenTheQuotaSelectsTheirPriorityClass() {
        var reviews = tierQuota(REVIEWS, Map.of("requests.memory", "2Gi"), Map.of("requests.memory", "640Mi"));

        assertThat(KubernetesSandboxQuota.fits(List.of(reviews), REVIEW, REVIEWS))
                .as("1360 MiB free hold two reviews of 640 MiB")
                .isEqualTo(2);
        assertThat(KubernetesSandboxQuota.neverFits(
                        List.of(tierQuota(REVIEWS, Map.of("requests.memory", "512Mi"), Map.of())), REVIEW, REVIEWS))
                .isTrue();
    }

    @Test
    void shouldIgnoreTheConversationsTierWhenItHasNoRoomForAReview() {
        var conversations = tierQuota(
                "hephaestus-heph-conversations", Map.of("requests.memory", "1Gi"), Map.of("requests.memory", "1Gi"));

        assertThat(KubernetesSandboxQuota.fits(List.of(conversations), REVIEW, REVIEWS))
                .as("Hephaestus's full tier takes no room from reviews")
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(KubernetesSandboxQuota.neverFits(List.of(conversations), REVIEW, REVIEWS))
                .isFalse();
    }

    @Test
    void shouldWaitUntilTheQuotasCanBeRead() {
        KubernetesSandboxCache cache = mock(KubernetesSandboxCache.class);
        when(cache.quotas())
                .thenThrow(new KubernetesClientException("not listed yet"))
                .thenReturn(List.of());
        var quota = new KubernetesSandboxQuota(cache, "sandboxes", "", REVIEWS);
        var limits = new ResourceLimits(1.0, Duration.ofMinutes(30));

        assertThat(quota.roomFor(limits)).isEqualTo(-1);
        assertThat(quota.roomFor(limits)).isEqualTo(Integer.MAX_VALUE);
    }

    private static ResourceQuota tierQuota(String priorityClass, Map<String, String> hard, Map<String, String> used) {
        var quota = quota(hard, used);
        quota.setSpec(new ResourceQuotaSpecBuilder()
                .withNewScopeSelector()
                .addNewMatchExpression()
                .withScopeName("PriorityClass")
                .withOperator("In")
                .withValues(priorityClass)
                .endMatchExpression()
                .endScopeSelector()
                .build());
        return quota;
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
