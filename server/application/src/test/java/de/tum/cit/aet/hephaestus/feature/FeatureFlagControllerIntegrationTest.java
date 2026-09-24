package de.tum.cit.aet.hephaestus.feature;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeatureFlagControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Nested
    class GetUserFeatures {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void requiresAuthentication() {
            webTestClient
                    .get()
                    .uri("/user/features")
                    .headers(TestAuthUtils.withCurrentUserOrNone())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void adminUserHasAdminFlags() {
            webTestClient
                    .get()
                    .uri("/user/features")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.ADMIN")
                    .isEqualTo(true)
                    .jsonPath("$.MENTOR_ACCESS")
                    .isEqualTo(false) // admin annotation only sets "admin" authority
                    .jsonPath("$.GITLAB_WORKSPACE_CREATION")
                    .isEqualTo(true);
        }

        @Test
        @WithMentorUser
        void mentorUserHasMentorFlags() {
            webTestClient
                    .get()
                    .uri("/user/features")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.MENTOR_ACCESS")
                    .isEqualTo(true)
                    .jsonPath("$.ADMIN")
                    .isEqualTo(false)
                    .jsonPath("$.NOTIFICATION_ACCESS")
                    .isEqualTo(false);
        }

        @Test
        @WithUser
        void regularUserHasNoRoleFlags() {
            webTestClient
                    .get()
                    .uri("/user/features")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.MENTOR_ACCESS")
                    .isEqualTo(false)
                    .jsonPath("$.ADMIN")
                    .isEqualTo(false)
                    .jsonPath("$.NOTIFICATION_ACCESS")
                    .isEqualTo(false);
        }

        @Test
        @WithUser
        void responseContainsAllFlags() {
            webTestClient
                    .get()
                    .uri("/user/features")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(FeatureFlag.values().length)
                    .jsonPath("$.MENTOR_ACCESS")
                    .exists()
                    .jsonPath("$.NOTIFICATION_ACCESS")
                    .exists()
                    .jsonPath("$.ADMIN")
                    .exists()
                    .jsonPath("$.GITLAB_WORKSPACE_CREATION")
                    .exists();
        }
    }
}
