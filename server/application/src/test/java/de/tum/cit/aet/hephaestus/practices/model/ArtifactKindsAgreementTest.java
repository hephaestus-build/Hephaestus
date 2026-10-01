package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.DocsSignals;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.DocumentArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.PullRequestArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The practices module restates kind literals the owning domains declare, because it may not import
 * them. A restated literal is a fork waiting to happen — two spellings of one kind, discovered only when
 * a row written under one stops matching the other — so both sides are held together here, in test code,
 * which may see them.
 */
class ArtifactKindsAgreementTest extends BaseUnitTest {

    @Test
    @DisplayName("the practices module's kind literals are the ones the owning domains declare")
    void agreesWithTheOwningDomain() {
        assertThat(ArtifactKinds.PULL_REQUEST).isEqualTo(ScmSignals.PULL_REQUEST);
        assertThat(ArtifactKinds.ISSUE).isEqualTo(ScmSignals.ISSUE);
        assertThat(ArtifactKinds.CONVERSATION_THREAD).isEqualTo(ChatSignals.CONVERSATION_THREAD);
        assertThat(ArtifactKinds.DOCUMENT).isEqualTo(DocsSignals.DOCUMENT);
    }

    @Test
    @DisplayName("the persisted spellings are pinned: changing one is a data migration, not an edit")
    void spellingsArePinned() {
        // These strings are persisted in observation, feedback and agent_job rows and in every bundled
        // practice's signals. Re-spelling one without migrating orphans every row already written under
        // the old spelling.
        assertThat(Stream.of(
                                ArtifactKinds.PULL_REQUEST,
                                ArtifactKinds.ISSUE,
                                ArtifactKinds.CONVERSATION_THREAD,
                                ArtifactKinds.DOCUMENT)
                        .map(ArtifactKind::value))
                .containsExactly("scm.pull_request", "scm.issue", "chat.conversation_thread", "docs.document");
    }

    @Test
    @DisplayName("only a pull request carries a diff, so only a pull request has an inline lane")
    void onlyPullRequestsHaveAnInlineLane() {
        assertThat(ArtifactKinds.hasInlineLane(ArtifactKinds.PULL_REQUEST)).isTrue();
        assertThat(ArtifactKinds.hasInlineLane(ArtifactKinds.ISSUE)).isFalse();
        assertThat(ArtifactKinds.hasInlineLane(ArtifactKinds.CONVERSATION_THREAD))
                .isFalse();
        assertThat(ArtifactKinds.hasInlineLane(ArtifactKinds.DOCUMENT)).isFalse();
    }

    @Test
    @DisplayName("the inline-lane shortcut says what the descriptors say")
    void inlineLaneAgreesWithTheDescriptors() {
        // The authority is the descriptor's FeedbackLane.IN_CONTEXT_INLINE; ArtifactKinds restates it
        // because DeliveryComposer is static and has no registry to ask. Held together here, against
        // every shipped descriptor, rather than by a comment promising they agree.
        for (ArtifactDescriptor descriptor : List.of(
                new PullRequestArtifactDescriptor(),
                new IssueArtifactDescriptor(),
                new ConversationThreadArtifactDescriptor(),
                new DocumentArtifactDescriptor())) {
            assertThat(ArtifactKinds.hasInlineLane(descriptor.kind()))
                    .as("ArtifactKinds and the descriptor disagree about the inline lane of '%s'", descriptor.kind())
                    .isEqualTo(descriptor.lanes().contains(FeedbackLane.IN_CONTEXT_INLINE));
        }
    }

    @Test
    @DisplayName("a kind is equal by value, so a freshly parsed one matches the constant")
    void equalByValue() {
        // A kind read back out of a column is a different instance, so any comparison by reference is
        // wrong even where it happens to pass on the constants.
        assertThat(ArtifactKind.of("scm.pull_request")).isEqualTo(ArtifactKinds.PULL_REQUEST);
        assertThat(List.of(ArtifactKinds.PULL_REQUEST)).contains(ArtifactKind.of("scm.pull_request"));
    }
}
