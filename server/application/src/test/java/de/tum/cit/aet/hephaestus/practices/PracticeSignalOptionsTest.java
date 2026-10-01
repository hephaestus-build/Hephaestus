package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.PullRequestArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions.SignalOption;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an author may bind a practice to — an answer derived from the registered domains rather than
 * written down in this module.
 */
class PracticeSignalOptionsTest extends BaseUnitTest {

    private final PracticeSignalOptions options = PracticeSignalOptionsFixture.real();

    @Test
    @DisplayName("a kind offers exactly the lifecycle signals its descriptor declares")
    void offersWhatTheDomainDeclares() {
        assertThat(options.bindableOptionsFor(ArtifactKinds.PULL_REQUEST))
                .extracting(SignalOption::signal)
                .containsExactly(
                        ScmSignals.PULL_REQUEST_OPENED,
                        ScmSignals.PULL_REQUEST_READY,
                        ScmSignals.PULL_REQUEST_SYNCHRONIZED,
                        ScmSignals.PULL_REQUEST_EDITED,
                        ScmSignals.PULL_REQUEST_REVIEWED,
                        ScmSignals.PULL_REQUEST_MERGED,
                        ScmSignals.PULL_REQUEST_CLOSED);
        assertThat(options.bindableOptionsFor(ArtifactKinds.ISSUE))
                .extracting(SignalOption::signal)
                .containsExactly(ScmSignals.ISSUE_OPENED, ScmSignals.ISSUE_UPDATED, ScmSignals.ISSUE_CLOSED);
    }

    /**
     * A review somebody asks for reviews every practice on the kind whatever state the work is in, so
     * an author who ticked it would be deciding nothing while a practice holding only it would look
     * configured and never fire.
     */
    @Test
    @DisplayName("asking for a review by hand is not offered as an occasion, but is still named")
    void keepsTheHandRequestOutOfTheOccasions() {
        assertThat(options.bindableOptionsFor(ArtifactKinds.PULL_REQUEST))
                .extracting(SignalOption::signal)
                .doesNotContain(ScmSignals.PULL_REQUEST_MANUAL_REVIEW);
        assertThat(options.eligibleFor(ArtifactKinds.ISSUE)).doesNotContain(ScmSignals.ISSUE_MANUAL_REVIEW);

        assertThat(options.manualRequestOptionFor(ArtifactKinds.PULL_REQUEST).map(SignalOption::signal))
                .contains(ScmSignals.PULL_REQUEST_MANUAL_REVIEW);
        // A kind whose descriptor declares no such signal says so by absence, not by an invented name.
        assertThat(options.manualRequestOptionFor(ArtifactKinds.CONVERSATION_THREAD))
                .isEmpty();
    }

    @Test
    @DisplayName("a conversation thread is authorable now that a descriptor declares what happens to it")
    void offersTheConversationSignal() {
        assertThat(options.bindableOptionsFor(ArtifactKinds.CONVERSATION_THREAD))
                .extracting(SignalOption::signal)
                .containsExactly(ChatSignals.CONVERSATION_THREAD_SETTLED);
    }

    @Test
    @DisplayName("a kind no module declares offers nothing, by being absent rather than by a rule")
    void offersNothingForAnUndeclaredKind() {
        PracticeSignalOptions scmOnly =
                PracticeSignalOptionsFixture.with(new PullRequestArtifactDescriptor(), new IssueArtifactDescriptor());

        assertThat(scmOnly.bindableOptionsFor(ArtifactKinds.CONVERSATION_THREAD))
                .isEmpty();
        assertThat(scmOnly.eligibleFor(ArtifactKinds.CONVERSATION_THREAD)).isEmpty();
        assertThat(scmOnly.authorableKinds()).doesNotContain(ArtifactKinds.CONVERSATION_THREAD);
    }

    @Test
    @DisplayName("the domain's recommendation reaches the authoring surface")
    void carriesTheDomainsRecommendation() {
        List<SignalName> recommended = options.bindableOptionsFor(ArtifactKinds.PULL_REQUEST).stream()
                .filter(SignalOption::recommended)
                .map(SignalOption::signal)
                .toList();

        assertThat(recommended)
                .containsExactly(
                        ScmSignals.PULL_REQUEST_OPENED,
                        ScmSignals.PULL_REQUEST_READY,
                        ScmSignals.PULL_REQUEST_SYNCHRONIZED);
    }

    @Test
    @DisplayName("a signal raised from inside Hephaestus says so rather than looking like a broken producer")
    void separatesInternallyRaisedSignals() {
        // Both of these are real occasions with no ingested event behind them: somebody asked for a
        // review, or a scheduler decided a discussion had finished. Coverage must not read that as a
        // vendor failing to deliver.
        assertThat(options.producedByIngestion(ScmSignals.PULL_REQUEST_MANUAL_REVIEW))
                .isFalse();
        assertThat(options.producedByIngestion(ChatSignals.CONVERSATION_THREAD_SETTLED))
                .isFalse();
        assertThat(options.producedByIngestion(ScmSignals.PULL_REQUEST_MERGED)).isTrue();
    }

    @Test
    void keepsTheLinkedIssueRepairOccasionInternalWhileDeclaringItsProviderProvenance() {
        assertThat(options.isInternalRepair(ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED))
                .isTrue();
        assertThat(options.isInternalRepair(ScmSignals.PULL_REQUEST_MERGED)).isFalse();
        assertThat(options.isInternalRepair(ScmSignals.PULL_REQUEST_MANUAL_REVIEW))
                .isFalse();
        assertThat(options.isInternalRepair(ChatSignals.CONVERSATION_THREAD_SETTLED))
                .isFalse();
        assertThat(options.isInternalRepair(SignalName.of("scm.pull_request.undeclared")))
                .isFalse();
        assertThat(options.producedByIngestion(ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED))
                .isTrue();
        assertThat(options.eligibleFor(ArtifactKinds.PULL_REQUEST))
                .doesNotContain(ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED);
    }

    @Test
    @DisplayName("only reviewable kinds are authorable")
    void offersOnlyReviewableKinds() {
        assertThat(options.authorableKinds())
                .containsExactlyInAnyOrder(
                        ArtifactKinds.PULL_REQUEST,
                        ArtifactKinds.ISSUE,
                        ArtifactKinds.CONVERSATION_THREAD,
                        new ArtifactKind("docs.document"));
    }
}
