package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
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
                new SourceContractVersion("1.1.0"),
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null);
    }

    /** One binding on the kind's recommended signal, reading what a practice of that kind reads. */
    public static List<PracticeBinding> bindings(ArtifactKind artifactKind) {
        return List.of(PracticeBinding.on(defaultSignal(artifactKind), needsFor(artifactKind)));
    }

    public static List<PracticeBinding> bindings(SignalName... signals) {
        ArtifactKind kind = signals[0].artifactKind();
        return List.of(new PracticeBinding(List.of(signals), needsFor(kind), false));
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
