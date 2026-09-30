package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;

class MentorTurnPersistenceCostTest extends BaseUnitTest {

    private MentorTurnPersistence persistence() {
        return new MentorTurnPersistence(
                mock(ChatThreadRepository.class),
                mock(ChatMessageRepository.class),
                mock(WorkspaceRepository.class),
                mock(ConversationalDeliveryReconciler.class),
                mock(LlmUsageRecorder.class),
                mock(PlatformTransactionManager.class));
    }

    private static TranslatorState state(
            long input, long output, long cacheRead, long cacheWrite, long reasoning, PricingState mode) {
        TranslatorState state = new TranslatorState(UUID.randomUUID());
        state.bindAdmission(
                "authoritative-model",
                new LlmPriceSnapshot(
                        FundingSource.INSTANCE,
                        mode,
                        12L,
                        null,
                        new BigDecimal("10"),
                        new BigDecimal("11.5"),
                        new BigDecimal("20"),
                        new BigDecimal("30")));
        var usage = JsonNodeFactory.instance.objectNode();
        usage.put("input", input)
                .put("output", output)
                .put("cacheRead", cacheRead)
                .put("cacheWrite", cacheWrite)
                .put("reasoning", reasoning);
        state.observeUsage(usage);
        return state;
    }

    private static UIMessageChunk.Finish finish() {
        return new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null);
    }

    /** The turn's account as the runner reported it. */
    private static MentorTurnPersistence.TurnUsage runner(long input, long output, long cacheRead, long cacheWrite) {
        return new MentorTurnPersistence.TurnUsage(
                new MentorTurnPersistence.UsageBreakdown("authoritative-model", input, output, cacheRead, cacheWrite),
                1,
                0,
                UsageProvenance.RUNNER);
    }

    @Test
    void streamedCostUsesTheSameFrozenCatalogPriceAsTheLedger() {
        UIMessageChunk.Finish out = persistence()
                .recordedFinish(finish(), state(1000, 200, 0, 0, 0, PricingState.PRICED), runner(1000, 200, 0, 0));

        var metadata = out.messageMetadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        assertThat(metadata.model()).isEqualTo("authoritative-model");
        assertThat(metadata.costUsd()).isEqualTo(0.0123);
    }

    @Test
    void cacheBucketsAreAdditiveAndReasoningIsNotDoubleCharged() {
        // The provider counts reasoning tokens INSIDE its output total, so the output bucket has
        // already paid for these 150: $0.0023 for 200 output at $11.50/1M either way. Billing the
        // reasoning tokens as a fifth bucket at the output rate would read $0.017025.
        UIMessageChunk.Finish out = persistence()
                .recordedFinish(finish(), state(0, 200, 500, 100, 150, PricingState.PRICED), runner(0, 200, 500, 100));

        var metadata = out.messageMetadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        assertThat(metadata.costUsd())
                .as("output $0.0023 + cacheRead $0.0100 + cacheWrite $0.0030, reasoning charged once")
                .isEqualTo(0.0153);
        var usage = metadata.usage();
        org.junit.jupiter.api.Assertions.assertNotNull(usage);
        assertThat(usage.totalTokens()).as("every bucket, as Pi counts a total").isEqualTo(800);
    }

    @Test
    void unpricedAdmissionReportsUsageWithoutACost() {
        UIMessageChunk.Finish out = persistence()
                .recordedFinish(finish(), state(1000, 200, 0, 0, 0, PricingState.UNPRICED), runner(1000, 200, 0, 0));

        var metadata = out.messageMetadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        assertThat(metadata.costUsd()).isNull();
        assertThat(metadata.usage()).isNotNull();
    }
}
