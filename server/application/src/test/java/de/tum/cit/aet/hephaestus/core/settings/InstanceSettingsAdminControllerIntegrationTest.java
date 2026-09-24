package de.tum.cit.aet.hephaestus.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsAdminController.InstanceSettingsDTO;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class InstanceSettingsAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SilentModeQuery silentModeQuery;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private InstanceSettingsRepository instanceSettingsRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @BeforeEach
    void shouldStartReleasedWhenExplicitSettingExists() {
        releaseDirectly();
    }

    @AfterEach
    void releaseSilentMode() {
        releaseDirectly();
    }

    @Test
    @WithUser
    void nonAdminCannotReadOrWriteSettings() {
        webTestClient
                .get()
                .uri("/admin/settings")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .patch()
                .uri("/admin/settings/silent-mode")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engaged", true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void anonymousIsRejected() {
        webTestClient
                .get()
                .uri("/admin/settings")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void engageAndReleaseRoundTrip() {
        InstanceSettingsDTO initial = getSettings();
        assertThat(initial.silentModeEngaged()).isFalse();

        String reason = "audit-proof-" + java.util.UUID.randomUUID();
        InstanceSettingsDTO engaged = patchSilentMode(Map.of("engaged", true, "reason", reason), null);
        assertThat(engaged.silentModeEngaged()).isTrue();
        assertThat(engaged.silentModeReason()).isEqualTo(reason);
        assertThat(authEventRepository.findAll())
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.SILENT_MODE_CHANGED
                        && event.getDetails() != null
                        && event.getDetails().contains(reason))
                .singleElement()
                .satisfies(event -> assertThat(event.getResult()).isEqualTo(AuthEvent.Result.SUCCESS));
        assertThat(engaged.silentModeChangedAt()).isNotNull();
        assertThat(engaged.silentModeChangedBy()).isNotBlank();

        assertThat(getSettings().silentModeEngaged()).isTrue();
        // API → DB → SPI: the port the delivery paths consult sees it too.
        assertThat(silentModeQuery.isSilentModeEngaged()).isTrue();

        InstanceSettingsDTO released = patchSilentMode(Map.of("engaged", false), engaged.etag());
        assertThat(released.silentModeEngaged()).isFalse();
        assertThat(released.silentModeReason())
                .as("reason is cleared on release")
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldRejectReleaseWhenEntityTagIsStale() {
        InstanceSettingsDTO initial = getSettings();
        InstanceSettingsDTO engaged = patchSilentMode(Map.of("engaged", true), null);

        webTestClient
                .patch()
                .uri("/admin/settings/silent-mode")
                .headers(TestAuthUtils.withCurrentUser())
                .header(HttpHeaders.IF_MATCH, initial.etag())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engaged", false))
                .exchange()
                .expectStatus()
                .isEqualTo(412)
                .expectBody(Void.class);

        assertThat(getSettings().silentModeEngaged()).isTrue();
        assertThat(getSettings().etag()).isEqualTo(engaged.etag());
    }

    @Test
    @WithAdminUser
    void shouldRejectReleaseWhenIfMatchIsMissing() {
        webTestClient
                .patch()
                .uri("/admin/settings/silent-mode")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engaged", false))
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody(Void.class);
    }

    @Test
    void shouldInitializeOneRowWhenMissingRowIsReadConcurrently() throws Exception {
        instanceSettingsRepository.deleteAll();
        instanceSettingsRepository.flush();
        assertThat(instanceSettingsService.get().isSilentModeEngaged()).isTrue();
        assertThat(instanceSettingsService.isSilentModeEngaged()).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> initializeAfter(ready, start));
            var second = executor.submit(() -> initializeAfter(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
        }

        assertThat(instanceSettingsRepository.count()).isEqualTo(1);
        assertThat(instanceSettingsService.isSilentModeEngaged()).isTrue();
    }

    @Test
    void shouldKeepEmergencyEngagedWhenReleaseIsConcurrent() throws Exception {
        InstanceSettings current = instanceSettingsService.get();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var engage = executor.submit(() -> updateAfter(ready, start, true, null));
            var release = executor.submit(() -> updateAfter(ready, start, false, current.getVersion()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(engage.get(10, TimeUnit.SECONDS)).isNull();
            Exception releaseFailure = release.get(10, TimeUnit.SECONDS);
            if (releaseFailure != null) {
                assertThat(releaseFailure).isInstanceOf(StaleInstanceSettingsException.class);
            }
        }

        assertThat(instanceSettingsService.isSilentModeEngaged()).isTrue();
    }

    @Test
    @WithAdminUser
    void missingEngagedFieldIsRejectedNotDefaultedToRelease() {
        webTestClient
                .patch()
                .uri("/admin/settings/silent-mode")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", "no engaged flag"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    private InstanceSettingsDTO getSettings() {
        var result = webTestClient
                .get()
                .uri("/admin/settings")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(InstanceSettingsDTO.class)
                .returnResult();
        InstanceSettingsDTO dto = result.getResponseBody();
        assertThat(dto).isNotNull();
        assertThat(result.getResponseHeaders().getETag()).isEqualTo(dto.etag());
        return dto;
    }

    private InstanceSettingsDTO patchSilentMode(Map<String, Object> body, @Nullable String currentEtag) {
        WebTestClient.RequestBodySpec request = webTestClient
                .patch()
                .uri("/admin/settings/silent-mode")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON);
        if (currentEtag != null) {
            request.header(HttpHeaders.IF_MATCH, currentEtag);
        }
        InstanceSettingsDTO dto = request.bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(InstanceSettingsDTO.class)
                .returnResult()
                .getResponseBody();
        org.junit.jupiter.api.Assertions.assertNotNull(dto);
        return dto;
    }

    private void releaseDirectly() {
        InstanceSettings current = instanceSettingsService.get();
        if (current.isSilentModeEngaged()) {
            instanceSettingsService.updateSilentMode(
                    false, null, null, EntityTagPrecondition.parse(etag(current.getVersion())));
        }
    }

    private @Nullable Exception initializeAfter(CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            await(start);
            instanceSettingsService.updateSilentMode(true, null, null, null);
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    private @Nullable Exception updateAfter(
            CountDownLatch ready, CountDownLatch start, boolean engaged, @Nullable Long version) {
        try {
            ready.countDown();
            await(start);
            instanceSettingsService.updateSilentMode(
                    engaged, null, null, version == null ? null : EntityTagPrecondition.parse(etag(version)));
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent initialization");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent initialization", exception);
        }
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}
