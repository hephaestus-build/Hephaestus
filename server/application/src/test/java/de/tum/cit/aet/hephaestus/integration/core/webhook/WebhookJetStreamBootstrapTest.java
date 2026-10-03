package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture.GIBIBYTE;
import static de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture.gibibytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamState;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class WebhookJetStreamBootstrapTest extends BaseUnitTest {

    private final WebhookProperties properties = WebhookPropertiesFixture.properties();

    @Test
    void createsStreamWhenMissing() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        // One stream per NATS-publishing integration kind (gitlab/github/slack/outline).
        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).addStream(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(StreamConfiguration::getName)
                .containsExactlyInAnyOrder("gitlab", "github", "slack", "outline");
    }

    @Test
    void createsEveryStreamWithAStorageBound() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).addStream(captor.capture());
        assertThat(captor.getAllValues())
                .as("a stream with no byte bound grows until the broker's volume is full")
                .allSatisfy(config -> assertThat(config.getMaxBytes()).isPositive());
    }

    @Test
    void createsStreamsWithNoMessageCountBound() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).addStream(captor.capture());
        assertThat(captor.getAllValues())
                .as("a count bound describes neither disk nor time, and hid the absence of one that did")
                .allSatisfy(config -> assertThat(config.getMaxMsgs()).isEqualTo(-1L));
    }

    @Test
    void perStreamOverridesReachTheStreamConfiguration() throws Exception {
        WebhookProperties overridden = WebhookPropertiesFixture.with(new WebhookProperties.Stream(
                Duration.ofMinutes(10),
                Duration.ofDays(180),
                Map.of("slack", Duration.ofHours(72)),
                gibibytes(1),
                Map.of("github", gibibytes(8)),
                gibibytes(12),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);

        new WebhookJetStreamBootstrap(jsm, overridden).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).addStream(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(config -> "slack".equals(config.getName()))
                .singleElement()
                .satisfies(config -> {
                    assertThat(config.getMaxAge()).isEqualTo(Duration.ofHours(72));
                    assertThat(config.getMaxBytes()).isEqualTo(GIBIBYTE);
                });
        assertThat(captor.getAllValues())
                .filteredOn(config -> "github".equals(config.getName()))
                .singleElement()
                .satisfies(config -> {
                    assertThat(config.getMaxAge()).isEqualTo(Duration.ofDays(180));
                    assertThat(config.getMaxBytes()).isEqualTo(8 * GIBIBYTE);
                });
    }

    @Test
    void refusesToStartWhenStreamBoundsOvercommitTheBroker() {
        WebhookProperties overcommitted = WebhookPropertiesFixture.with(new WebhookProperties.Stream(
                Duration.ofMinutes(10),
                Duration.ofDays(7),
                Map.of(),
                gibibytes(4),
                Map.of(),
                // Four streams at 4 GiB need 16, and the broker is told it may hold 8.
                gibibytes(8),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);

        WebhookJetStreamBootstrap bootstrap = new WebhookJetStreamBootstrap(jsm, overcommitted);
        assertThatThrownBy(bootstrap::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over the 8589934592-byte broker storage budget");
    }

    @Test
    void startsWhenStreamBoundsExactlyFillTheBrokerBudget() throws Exception {
        WebhookProperties exact = WebhookPropertiesFixture.with(new WebhookProperties.Stream(
                Duration.ofMinutes(10),
                Duration.ofDays(7),
                Map.of(),
                gibibytes(2),
                Map.of(),
                gibibytes(8),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);

        new WebhookJetStreamBootstrap(jsm, exact).bootstrap();

        verify(jsm, times(4)).addStream(any());
    }

    @Test
    void appliesALimitThatCannotDeleteAnything() throws Exception {
        // Stream already inside the new bound: applying it costs nothing, so nobody has to opt in.
        StreamInfo info = existing(builder -> builder.maxBytes(4 * GIBIBYTE), state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(config -> assertThat(config.getMaxBytes()).isEqualTo(GIBIBYTE));
    }

    @Test
    void withholdsAByteBoundThatWouldDeleteStoredMessages(CapturedOutput output) throws Exception {
        StreamInfo info = existing(builder -> builder.maxBytes(-1), state(4 * GIBIBYTE, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
        assertThat(output.getAll()).contains("3221225472 would be deleted");
    }

    @Test
    void appliesADestructiveByteBoundOnceTheOperatorAllowsIt() throws Exception {
        StreamInfo info = existing(builder -> builder.maxBytes(-1), state(4 * GIBIBYTE, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, allowingDestructiveUpdates()).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(config -> assertThat(config.getMaxBytes()).isEqualTo(GIBIBYTE));
    }

    @Test
    void withholdsARetentionCutThatWouldExpireStoredMessages(CapturedOutput output) throws Exception {
        // Oldest message predates the configured ceiling, so applying it expires that message the
        // moment the update lands.
        StreamInfo info = existing(
                builder -> builder.maxAge(Duration.ofDays(365)),
                state(GIBIBYTE / 2, 10, 1, ZonedDateTime.now().minusDays(200)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
        assertThat(output.getAll()).contains("is already older");
    }

    @Test
    void appliesARetentionCutThatReachesNothingStored() throws Exception {
        StreamInfo info = existing(
                builder -> builder.maxAge(Duration.ofDays(365)),
                state(GIBIBYTE / 2, 10, 1, ZonedDateTime.now().minusHours(1)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(config -> assertThat(config.getMaxAge()).isEqualTo(Duration.ofDays(180)));
    }

    @Test
    void treatsAnEmptyStreamAsHavingNothingToExpire() throws Exception {
        StreamInfo info = existing(
                builder -> builder.maxAge(Duration.ofDays(365)),
                state(0, 0, 1, ZonedDateTime.now().minusDays(365)));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, times(4)).updateStream(any());
    }

    @Test
    void withholdsEverythingWhenStreamStateCannotBeRead(CapturedOutput output) throws Exception {
        StreamInfo info = existing(builder -> builder.maxBytes(-1).maxAge(Duration.ofDays(365)), null);
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
        assertThat(output.getAll()).contains("cannot prove the change is non-destructive");
    }

    @Test
    void neverWritesLimitsOntoAStreamThatDiscardsNewMessages(CapturedOutput output) throws Exception {
        // Under DiscardPolicy.New a byte bound makes the broker REJECT publishes instead of shedding
        // old messages — total ingestion loss for every workspace, which is what ADR 0008 forbids.
        StreamInfo info =
                existing(builder -> builder.maxBytes(-1).discardPolicy(DiscardPolicy.New), state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
        assertThat(output.getAll()).contains("discardPolicy=" + DiscardPolicy.New);
    }

    @Test
    void neverWritesLimitsOntoAStreamWhoseRetentionPolicyHasDrifted() throws Exception {
        StreamInfo info = existing(
                builder -> builder.maxBytes(-1).retentionPolicy(RetentionPolicy.WorkQueue), state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
    }

    @Test
    void keepsTheCountBoundWhenTheByteBoundReplacingItIsWithheld(CapturedOutput output) throws Exception {
        // The shape every upgrade starts from, at the size that filled the host: bounded only by a
        // count, and already so far past the new byte bound that bounding it would delete 31.2 GB.
        StreamInfo info = countBoundOnly(state(32_300_000_000L, 2_000_000, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).updateStream(any());
        assertThat(output.getAll()).contains("would leave the stream unbounded");
    }

    @Test
    void dropsTheCountBoundOnlyWhenAByteBoundLands() throws Exception {
        // Same starting shape, but the stream fits inside the new byte bound, so the replacement
        // applies in the same update and the count bound has something to hand over to.
        StreamInfo info = countBoundOnly(state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(config -> {
            assertThat(config.getMaxBytes()).isEqualTo(GIBIBYTE);
            assertThat(config.getMaxMsgs()).isEqualTo(-1L);
        });
    }

    @Test
    void dropsTheCountBoundWhenAByteBoundIsAlreadyInForce() throws Exception {
        // A byte bound that already matches configuration produces no update, so "did the write
        // land" is the wrong question — what decides it is whether a bound is in force afterwards.
        StreamInfo info = existing(builder -> builder.maxMessages(2_000_000), state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(config -> assertThat(config.getMaxMsgs()).isEqualTo(-1L));
    }

    @Test
    void releasesTheCountBoundOnceTheOperatorLetsTheByteBoundLand(CapturedOutput output) throws Exception {
        StreamInfo info = countBoundOnly(state(32_300_000_000L, 2_000_000, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        // The operator opts into the deletion, so the byte bound lands and the count bound may go
        // with it — the release is conditional on the replacement, never on the opt-in alone.
        new WebhookJetStreamBootstrap(jsm, allowingDestructiveUpdates()).bootstrap();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, times(4)).updateStream(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(config -> {
            assertThat(config.getMaxBytes()).isEqualTo(GIBIBYTE);
            assertThat(config.getMaxMsgs()).isEqualTo(-1L);
        });
        assertThat(output.getAll()).doesNotContain("would leave the stream unbounded");
    }

    @Test
    void namesTheCountCapWhenItIsAllThatIsLeft(CapturedOutput output) throws Exception {
        StreamInfo info = countBoundOnly(state(32_300_000_000L, 2_000_000, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        assertThat(output.getAll()).contains("a 2000000-message cap is all that limits its disk");
    }

    @Test
    void reportsAStreamLeftWithNoStorageBound(CapturedOutput output) throws Exception {
        StreamInfo info = existing(builder -> builder.maxBytes(-1), state(4 * GIBIBYTE, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        assertThat(output.getAll()).contains("has no storage bound");
    }

    @Test
    void usesExistingStreamWhenAlreadyAtTheConfiguredLimits() throws Exception {
        StreamInfo info = existing(builder -> builder, state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        verify(jsm, never()).addStream(any());
        verify(jsm, never()).updateStream(any());
    }

    @Test
    void staysUpWhenTheLimitUpdateItselfFails(CapturedOutput output) throws Exception {
        StreamInfo info = existing(builder -> builder.maxBytes(4 * GIBIBYTE), state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(info);
        when(jsm.updateStream(any())).thenThrow(new IOException("broker unreachable"));

        new WebhookJetStreamBootstrap(jsm, properties).bootstrap();

        assertThat(output.getAll()).contains("Failed to reconcile JetStream stream limits");
    }

    @Test
    void failsFastOnAddStreamError() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException notFound = apiException(404);
        when(jsm.getStreamInfo(anyString())).thenThrow(notFound);
        when(jsm.addStream(any())).thenThrow(new IOException("broker unreachable"));

        WebhookJetStreamBootstrap bootstrap = new WebhookJetStreamBootstrap(jsm, properties);
        assertThatThrownBy(bootstrap::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to create JetStream stream");
    }

    @Test
    void failsFastOnNonNotFoundInspectError() throws Exception {
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        JetStreamApiException serverError = apiException(503);
        when(jsm.getStreamInfo("gitlab")).thenThrow(serverError);

        WebhookJetStreamBootstrap bootstrap = new WebhookJetStreamBootstrap(jsm, properties);
        assertThatThrownBy(bootstrap::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to inspect JetStream stream");
    }

    @Test
    void aTimedOutUpdateReportsTheLimitsTheStreamActuallyHasRatherThanAssertingItChangedNothing(CapturedOutput output)
            throws Exception {
        // Shedding what a new bound deletes happens before the broker replies, so a client timeout
        // and a successful update look identical from here. Staging showed exactly that: the update
        // landed, the client gave up, and the old message told the operator nothing had happened.
        StreamInfo before = countBoundOnly(state(GIBIBYTE / 2, 10, 1));
        StreamInfo after = existing(builder -> builder.maxBytes(4_294_967_296L).maxMessages(-1), state(0, 0, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(jsm.getStreamInfo(anyString())).thenReturn(before, after);
        when(jsm.updateStream(any())).thenThrow(new IOException("Timeout or no response waiting for NATS"));

        new WebhookJetStreamBootstrap(jsm, allowingDestructiveUpdates()).bootstrap();

        assertThat(output.getAll())
                .as("the operator needs the broker's answer, not the client's assumption")
                .contains("live configuration is now")
                .contains("maxBytes=4294967296")
                .doesNotContain("stream left at its live configuration");
    }

    @Test
    void aTimedOutUpdateAgainstAnUnreadableBrokerSaysSoInsteadOfGuessing(CapturedOutput output) throws Exception {
        // The re-read runs on a path that is already failing; if it fails too, silence would be the
        // one outcome worse than either error.
        StreamInfo before = countBoundOnly(state(GIBIBYTE / 2, 10, 1));
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        // Bootstrap walks every stream: the first pair of calls is the one under test — read, then the
        // re-read that fails. Later streams read normally so the loop reaches its end.
        when(jsm.getStreamInfo(anyString()))
                .thenReturn(before)
                .thenThrow(new IOException("broker gone"))
                .thenReturn(before);
        when(jsm.updateStream(any())).thenThrow(new IOException("Timeout or no response waiting for NATS"));

        new WebhookJetStreamBootstrap(jsm, allowingDestructiveUpdates()).bootstrap();

        assertThat(output.getAll()).contains("unreadable").contains("check the broker directly");
    }

    private WebhookProperties allowingDestructiveUpdates() {
        WebhookProperties.Stream s = properties.stream();
        return WebhookPropertiesFixture.with(new WebhookProperties.Stream(
                s.duplicateWindow(),
                s.maxAge(),
                Map.of(),
                s.maxBytes(),
                Map.of(),
                s.storageBudget(),
                true,
                s.limitUpdateTimeout(),
                s.monitorInterval()));
    }

    /**
     * The shape `main` leaves on every existing deployment: a message-count cap and no byte bound.
     * Every reconciliation an upgrade actually performs starts here.
     */
    private StreamInfo countBoundOnly(StreamState state) {
        return existing(builder -> builder.maxMessages(2_000_000).maxBytes(WebhookJetStreamBootstrap.UNLIMITED), state);
    }

    /** A live stream at the configured shape and limits, which the argument then perturbs. */
    private StreamInfo existing(UnaryOperator<StreamConfiguration.Builder> perturb, @Nullable StreamState state) {
        StreamConfiguration config = perturb.apply(StreamConfiguration.builder()
                        .name("existing")
                        .subjects("existing.>")
                        .retentionPolicy(RetentionPolicy.Limits)
                        .discardPolicy(DiscardPolicy.Old)
                        .storageType(StorageType.File)
                        .duplicateWindow(properties.stream().duplicateWindow())
                        .maxAge(properties.stream().maxAge())
                        .maxMessages(-1)
                        .maxBytes(properties.stream().maxBytes().toBytes()))
                .build();
        StreamInfo info = mock(StreamInfo.class);
        // Lenient: a stream whose shape has drifted is refused before its state is ever read.
        lenient().when(info.getConfiguration()).thenReturn(config);
        lenient().when(info.getStreamState()).thenReturn(state);
        return info;
    }

    private static StreamState state(long bytes, long messages, long firstSequence) {
        return state(bytes, messages, firstSequence, ZonedDateTime.now());
    }

    private static StreamState state(long bytes, long messages, long firstSequence, ZonedDateTime firstTime) {
        StreamState state = mock(StreamState.class);
        lenient().when(state.getByteCount()).thenReturn(bytes);
        lenient().when(state.getMsgCount()).thenReturn(messages);
        lenient().when(state.getFirstSequence()).thenReturn(firstSequence);
        lenient().when(state.getFirstTime()).thenReturn(firstTime);
        return state;
    }

    private static JetStreamApiException apiException(int code) {
        JetStreamApiException e = mock(JetStreamApiException.class);
        when(e.getErrorCode()).thenReturn(code);
        return e;
    }
}
