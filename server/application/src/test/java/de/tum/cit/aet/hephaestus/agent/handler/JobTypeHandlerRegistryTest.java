package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class JobTypeHandlerRegistryTest extends BaseUnitTest {

    @Mock
    private JobEvidenceFiles cas;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private WorkspaceContextBuilder workspaceContextBuilder;

    @Mock
    private PracticeDetectionDeliveryService deliveryService;

    @Mock
    private FeedbackDeliveryService feedbackService;

    @Mock
    private PullRequestCommentPoster commentPoster;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private JobTypeHandler prReviewHandler() {
        var parser = new PracticeDetectionResultParser(objectMapper);
        var envelopeWriter = new TaskEnvelopeWriter(objectMapper);
        return new PullRequestReviewHandler(
                objectMapper,
                cas,
                new PracticeCatalogInjector(
                        objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults()),
                workspaceContextBuilder,
                envelopeWriter,
                parser,
                new de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser(),
                deliveryService,
                feedbackService,
                org.mockito.Mockito.mock(SecretDiffScanner.class),
                org.mockito.Mockito.mock(FeedbackResponseSuppressionFilter.class),
                InContextDeliveryGateFixtures.gate(
                        practiceRepository,
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                        org.mockito.Mockito.mock(FeedbackLedgerRecorder.class)),
                org.mockito.Mockito.mock(de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class));
    }

    private JobTypeHandler issueReviewHandler() {
        var parser = new PracticeDetectionResultParser(objectMapper);
        var envelopeWriter = new TaskEnvelopeWriter(objectMapper);
        return new IssueReviewHandler(
                objectMapper,
                workspaceContextBuilder,
                envelopeWriter,
                new PracticeCatalogInjector(
                        objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults()),
                parser,
                new de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser(),
                deliveryService,
                InContextDeliveryGateFixtures.gate(
                        practiceRepository,
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                        org.mockito.Mockito.mock(FeedbackLedgerRecorder.class)),
                org.mockito.Mockito.mock(PullRequestCommentPoster.class),
                org.mockito.Mockito.mock(FeedbackLedgerRecorder.class),
                org.mockito.Mockito.mock(PracticeFeedbackDeliveryPolicy.class),
                org.mockito.Mockito.mock(PracticeFeedbackCommentFormatter.class),
                org.mockito.Mockito.mock(FeedbackResponseSuppressionFilter.class),
                org.mockito.Mockito.mock(de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                org.mockito.Mockito.mock(PracticeFeedbackDispatchService.class),
                org.mockito.Mockito.mock(FeedbackDeliveryService.class));
    }

    private JobTypeHandler conversationReviewHandler() {
        var parser = new PracticeDetectionResultParser(objectMapper);
        var envelopeWriter = new TaskEnvelopeWriter(objectMapper);
        return new ConversationReviewHandler(
                objectMapper,
                workspaceContextBuilder,
                envelopeWriter,
                new PracticeCatalogInjector(
                        objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults()),
                parser,
                deliveryService,
                org.mockito.Mockito.mock(ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class));
    }

    private JobTypeHandler documentReviewHandler() {
        var parser = new PracticeDetectionResultParser(objectMapper);
        var envelopeWriter = new TaskEnvelopeWriter(objectMapper);
        return new DocumentReviewHandler(
                objectMapper,
                workspaceContextBuilder,
                envelopeWriter,
                new PracticeCatalogInjector(
                        objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults()),
                parser,
                deliveryService);
    }

    /** A registry with the full handler set (every {@link AgentJobType} mapped). */
    private JobTypeHandlerRegistry fullRegistry() {
        return new JobTypeHandlerRegistry(
                List.of(prReviewHandler(), issueReviewHandler(), conversationReviewHandler(), documentReviewHandler()));
    }

    @Nested
    class Construction {

        @Test
        void shouldIndexHandlersByJobType() {
            var pr = prReviewHandler();
            var issue = issueReviewHandler();
            var conversation = conversationReviewHandler();
            var document = documentReviewHandler();
            var registry = new JobTypeHandlerRegistry(List.of(pr, issue, conversation, document));

            assertThat(registry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).isSameAs(pr);
            assertThat(registry.getHandler(AgentJobType.ISSUE_REVIEW)).isSameAs(issue);
            // Handler-registered contract: the conversation job type resolves to its handler, so a boot
            // with this bean set never trips the registry's "no handler registered" fail-fast.
            assertThat(registry.getHandler(AgentJobType.CONVERSATION_REVIEW)).isSameAs(conversation);
            // Same for the document job type. It is also what ReviewContractValidator's executability rule
            // reads: a reviewable kind with no handler here fails the boot rather than going quiet.
            assertThat(registry.getHandler(AgentJobType.DOCUMENT_REVIEW)).isSameAs(document);
        }

        @Test
        void shouldThrowOnDuplicateHandler() {
            var handler1 = prReviewHandler();
            var handler2 = prReviewHandler();

            assertThatThrownBy(() -> new JobTypeHandlerRegistry(List.of(handler1, handler2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate")
                    .hasMessageContaining("PULL_REQUEST_REVIEW");
        }

        @Test
        void shouldThrowOnMissingHandler() {
            assertThatThrownBy(() -> new JobTypeHandlerRegistry(List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No JobTypeHandler registered for")
                    .hasMessageContaining("PULL_REQUEST_REVIEW");
        }
    }

    @Nested
    class GetHandler {

        @Test
        void shouldRejectNullJobType() {
            var registry = fullRegistry();
            assertThatThrownBy(() -> registry.getHandler(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /** Resolves every workspace to the unset defaults — HUMAN_APPROVAL autonomy, reach on the work. */
}
