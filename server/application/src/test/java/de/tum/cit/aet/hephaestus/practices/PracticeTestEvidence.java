package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.List;

public final class PracticeTestEvidence {

    private PracticeTestEvidence() {}

    public static PracticeAutomatedReviewPolicy pullRequest() {
        return forArtifact(ArtifactKinds.PULL_REQUEST);
    }

    public static PracticeAutomatedReviewPolicy conversationThread() {
        return forArtifact(ArtifactKinds.CONVERSATION_THREAD);
    }

    public static PracticeAutomatedReviewPolicy forArtifact(ArtifactKind artifactKind) {
        needsFor(artifactKind); // reject an unsupported kind here rather than at the binding
        return new PracticeAutomatedReviewPolicy(
                de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null);
    }

    public static List<SignalName> signals(ArtifactKind artifactKind) {
        return List.of(defaultSignal(artifactKind));
    }

    public static List<SignalName> signals(SignalName... signals) {
        return List.of(signals);
    }

    public static void configure(Practice practice, ArtifactKind artifactKind) {
        configure(practice, defaultSignal(artifactKind));
    }

    public static void configure(Practice practice, SignalName... signals) {
        practice.setSignals(List.of(signals));
        practice.setEvidenceRequirements(needsFor(signals[0].artifactKind()));
        practice.setReviewWhen(PracticeSignalOptionsFixture.real().defaultReviewWhenFor(signals[0].artifactKind()));
    }

    public static SignalName defaultSignal(ArtifactKind artifactKind) {
        if (ArtifactKinds.PULL_REQUEST.equals(artifactKind)) {
            return ScmSignals.PULL_REQUEST_OPENED;
        }
        if (ArtifactKinds.ISSUE.equals(artifactKind)) {
            return ScmSignals.ISSUE_OPENED;
        }
        if (ArtifactKinds.CONVERSATION_THREAD.equals(artifactKind)) {
            return ChatSignals.CONVERSATION_THREAD_SETTLED;
        }
        if (ArtifactKinds.DOCUMENT.equals(artifactKind)) {
            return SignalName.of("docs.document.published");
        }
        throw new IllegalArgumentException("Unsupported artifact kind: " + artifactKind);
    }

    public static List<PracticeEvidenceRequirement> needsFor(ArtifactKind artifactKind) {
        List<String> kinds;
        if (ArtifactKinds.PULL_REQUEST.equals(artifactKind)) {
            kinds = List.of("scm.pull-request.core", "scm.pull-request.diff");
        } else if (ArtifactKinds.ISSUE.equals(artifactKind)) {
            kinds = List.of("scm.issue.core");
        } else if (ArtifactKinds.CONVERSATION_THREAD.equals(artifactKind)) {
            kinds = List.of("slack.conversation.thread");
        } else if (ArtifactKinds.DOCUMENT.equals(artifactKind)) {
            kinds = List.of("docs.document.core");
        } else {
            throw new IllegalArgumentException("Unsupported artifact kind: " + artifactKind);
        }
        return kinds.stream()
                .map(kind -> new PracticeEvidenceRequirement(new SourceKind(kind), EvidenceStance.REQUIRED))
                .toList();
    }
}
