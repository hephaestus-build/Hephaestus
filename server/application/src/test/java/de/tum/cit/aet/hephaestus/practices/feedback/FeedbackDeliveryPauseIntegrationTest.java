package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pausing and resuming feedback delivery through the workspace review settings. Each change starts a new rollout
 * revision, so work admitted before it stays withheld afterwards. Only work admitted after the resume is sent.
 */
class FeedbackDeliveryPauseIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    FeedbackDeliveryPolicy delivery;

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean(name = "scmItemService", enforceOverride = true)
    ScmItemService source;

    @MockitoBean
    EvidenceAuthorization authorization;

    private final Set<UUID> permitted = new HashSet<>();

    @BeforeEach
    void enableDelivery() {
        jdbc.update(
                "UPDATE workspace SET practices_enabled=true,practice_rollout_revision=0 WHERE id=?",
                workspace.getId());
        jdbc.update("""
            INSERT INTO instance_settings(id,silent_mode_engaged,version) VALUES(1,false,0)
            ON CONFLICT(id) DO UPDATE SET silent_mode_engaged=false
            """);
        jdbc.update("UPDATE account SET feedback_delivery_enabled=true WHERE id=?", developerId);
        var item = new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null);
        when(source.supports(item)).thenReturn(true);
        when(source.current(item))
                .thenReturn(Optional.of(ItemFixtures.snapshot(item, Map.of("state", "open"))
                        .version(new ItemVersion("v1", null))
                        .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, null))
                        .label(ItemFixtures.label()
                                .itemId(itemId)
                                .title("Change")
                                .noun("pull request")
                                .integration(IntegrationKind.GITHUB)
                                .build())
                        .build()));
        when(authorization.permitsForNewDelivery(
                        eq(workspace.getId()), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(call -> {
                    Collection<Result> asked = call.getArgument(1);
                    return asked.stream()
                            .map(Result::getId)
                            .filter(permitted::contains)
                            .collect(Collectors.toSet());
                });
    }

    @AfterEach
    void restoreInstanceDeliveryBrake() {
        jdbc.update("UPDATE instance_settings SET silent_mode_engaged=true WHERE id=1");
    }

    @Test
    void shouldWithholdReadyFeedbackAndSendOnlyWorkAdmittedAfterTheResumeWhenDeliveryIsPausedThenResumed() {
        Feedback beforePause = readyFeedbackAdmittedAt(0);

        setDeliveryStatus("PAUSED");
        assertThat(rolloutRevision()).isEqualTo(1);
        Feedback duringPause = readyFeedbackAdmittedAt(1);

        prepare(beforePause);
        prepare(duringPause);
        assertThat(delivery(beforePause)).containsExactly("WITHHELD", "STALE_ROLLOUT_REVISION");
        assertThat(delivery(duringPause)).containsExactly("WITHHELD", "WORKSPACE_DELIVERY_PAUSED");

        setDeliveryStatus("ACTIVE");
        assertThat(rolloutRevision()).isEqualTo(2);
        Feedback afterResume = readyFeedbackAdmittedAt(2);

        prepare(beforePause);
        prepare(duringPause);
        prepare(afterResume);
        assertThat(delivery(beforePause)).containsExactly("WITHHELD", "STALE_ROLLOUT_REVISION");
        assertThat(delivery(duringPause)).containsExactly("WITHHELD", "WORKSPACE_DELIVERY_PAUSED");
        assertThat(delivery(afterResume)).containsExactly("READY", null);
        assertThat(sends.findByFeedbackIdAndWorkspaceId(beforePause.getId(), workspace.getId()))
                .isEmpty();
        assertThat(sends.findByFeedbackIdAndWorkspaceId(duringPause.getId(), workspace.getId()))
                .isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT action || ':' || state FROM feedback_send WHERE workspace_id=? AND feedback_id=?",
                        String.class,
                        workspace.getId(),
                        afterResume.getId()))
                .containsExactly("POST:PENDING");
    }

    /** A ready on-work piece of a review that was admitted under the given rollout revision. */
    private Feedback readyFeedbackAdmittedAt(long rolloutRevision) {
        reviewId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at,"
                        + "rollout_revision) VALUES (?,?,?,'ITEM','v1','REQUEST','RUNNING',?,?,?)",
                reviewId,
                workspace.getId(),
                itemId,
                Timestamp.from(AT),
                Timestamp.from(AT),
                rolloutRevision);
        Result result = resultWithStateFilter("state", "open");
        permitted.add(result.getId());
        Feedback piece = piece(FeedbackDestination.ON_WORK, FeedbackState.READY, null);
        bind(piece, result);
        return piece;
    }

    private void prepare(Feedback piece) {
        delivery.prepared(feedback.findById(piece.getId()).orElseThrow());
    }

    private List<String> delivery(Feedback piece) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT state, stop_reason FROM feedback WHERE workspace_id=? AND id=?",
                (rs, row) -> Arrays.asList(rs.getString("state"), rs.getString("stop_reason")),
                workspace.getId(),
                piece.getId()));
    }

    private long rolloutRevision() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT practice_rollout_revision FROM workspace WHERE id=?", Long.class, workspace.getId()));
    }

    private void setDeliveryStatus(String status) {
        String token = "mock-jwt-sub-" + developerId;
        String etag = Objects.requireNonNull(webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/review-settings", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getETag());
        webTestClient
                .patch()
                .uri("/workspaces/{slug}/practices/review-settings", workspace.getWorkspaceSlug())
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setIfMatch(etag);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("deliveryStatus", status))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }
}
