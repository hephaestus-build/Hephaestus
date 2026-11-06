package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 0068: who in a review Pod holds the credential, and who can reach what. */
class ReviewPodSidecarsTest extends BaseUnitTest {

    private static final String SECRET = "attempt-credential";
    private static final ResourceLimits LIMITS = new ResourceLimits(1.0, Duration.ofMinutes(5));

    private final KubernetesPods pods = new KubernetesPods(
            mock(KubernetesClient.class), KubernetesFixtures.worker("sandboxes"), 10, ImagePullPolicy.IF_NOT_PRESENT);

    private Pod review(boolean programs) {
        return pods.workspacePod(
                new KubernetesPods.Workload(
                        "review",
                        KubernetesFixtures.worker("sandboxes").labels(KubernetesWorker.KIND_REVIEW),
                        "agent:test",
                        "agent"),
                Map.of("LLM_PROXY_TOKEN", SECRET),
                List.of("node", "run.ts"),
                LIMITS,
                KubernetesPods.REVIEW,
                new KubernetesPods.Sidecars("pod-nonce", programs));
    }

    private static Container container(Pod pod, String name) {
        return Stream.concat(pod.getSpec().getInitContainers().stream(), pod.getSpec().getContainers().stream())
                .filter(each -> name.equals(each.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> mounts(Container container) {
        return container.getVolumeMounts().stream()
                .map(VolumeMount::getMountPath)
                .toList();
    }

    @Test
    @DisplayName("only the loop holds the attempt's credential")
    void shouldKeepTheCredentialOutOfTheSidecars() {
        Pod pod = review(true);

        for (String side : List.of(KubernetesPods.TOOLS, KubernetesPods.PROGRAMS)) {
            assertThat(container(pod, side).getEnv())
                    .extracting(EnvVar::getValue)
                    .doesNotContain(SECRET);
        }
        assertThat(container(pod, KubernetesPods.LOOP).getEnv())
                .extracting(EnvVar::getValue)
                .contains(SECRET);
    }

    @Test
    @DisplayName("each side runs as its own user, and stays up beside the loop")
    void shouldRunEachSideAsItsOwnUser() {
        Pod pod = review(true);

        assertThat(List.of(
                        container(pod, KubernetesPods.LOOP).getSecurityContext().getRunAsUser(),
                        container(pod, KubernetesPods.TOOLS)
                                .getSecurityContext()
                                .getRunAsUser(),
                        container(pod, KubernetesPods.PROGRAMS)
                                .getSecurityContext()
                                .getRunAsUser()))
                .doesNotHaveDuplicates();
        assertThat(container(pod, KubernetesPods.TOOLS).getRestartPolicy()).isEqualTo("Always");
        assertThat(container(pod, KubernetesPods.PROGRAMS).getRestartPolicy()).isEqualTo("Always");
        assertThat(pod.getSpec().getShareProcessNamespace()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("the tools cannot reach the program socket: nothing mounts it for them")
    void shouldNotMountTheProgramSocketInTheTools() {
        Pod pod = review(true);

        assertThat(mounts(container(pod, KubernetesPods.TOOLS))).doesNotContain("/run/programs");
        assertThat(mounts(container(pod, KubernetesPods.PROGRAMS))).doesNotContain("/run/tools");
        assertThat(mounts(container(pod, KubernetesPods.LOOP))).contains("/run/tools", "/run/programs");
    }

    @Test
    @DisplayName("the sidecars read the workspace, and never mount what the loop owns")
    void shouldMountTheWorkspaceReadOnlyAndNotTheLoopsVolumes() {
        Pod pod = review(true);

        for (String side : List.of(KubernetesPods.TOOLS, KubernetesPods.PROGRAMS)) {
            Container container = container(pod, side);
            assertThat(mounts(container)).doesNotContain("/workspace/out", "/workspace/.pi", "/workspace/.sessions");
            assertThat(container.getVolumeMounts())
                    .filteredOn(mount -> mount.getMountPath().startsWith("/workspace"))
                    .allMatch(VolumeMount::getReadOnly);
        }
    }

    @Test
    @DisplayName("a review with no custom program pays for no program side")
    void shouldLeaveOutTheProgramSideWhenTheReviewRunsNoProgram() {
        Pod pod = review(false);

        assertThat(pod.getSpec().getInitContainers())
                .extracting(Container::getName)
                .doesNotContain(KubernetesPods.PROGRAMS)
                .contains(KubernetesPods.TOOLS);
        assertThat(mounts(container(pod, KubernetesPods.LOOP))).doesNotContain("/run/programs");
        assertThat(container(pod, KubernetesPods.LOOP).getEnv())
                .extracting(EnvVar::getName)
                .doesNotContain("PROGRAMS_SOCKET");
    }

    @Test
    @DisplayName("each side starts after the workspace is filled")
    void shouldStartTheSidecarsAfterTheWorkspaceInitializer() {
        assertThat(review(true).getSpec().getInitContainers())
                .extracting(Container::getName)
                .containsExactly(
                        KubernetesPods.ISOLATION,
                        KubernetesPods.INITIALIZER,
                        KubernetesPods.TOOLS,
                        KubernetesPods.PROGRAMS);
    }
}
