import {
	CircleDashedIcon,
	FileSearchIcon,
	HistoryIcon,
	type LucideIcon,
	NetworkIcon,
} from "lucide-react";

import type {
	PracticeAutomatedReviewPolicy,
	PracticeEvidenceOutcome,
	PracticeEvidenceSourceOption,
} from "@/api/types.gen";

type PracticeEvidenceReason = PracticeEvidenceOutcome["blockersObserved"][number]["reasonCode"];

export function evidenceSourceLabel(
	sourceKind: string,
	sources: readonly PracticeEvidenceSourceOption[],
) {
	return (
		sources.find((source) => source.sourceKind === sourceKind)?.displayName ?? "Unknown source"
	);
}

/**
 * How good a capture of this source has to be, or null where the answer is the norm. The source
 * contract answers this, not the practice: completeness is a property of the source.
 */
export function evidenceQualityRequirement(
	quality: PracticeEvidenceSourceOption["requiredQuality"] | undefined,
): string | null {
	switch (quality) {
		case "COMPLETE_AND_NON_EMPTY":
			return "Must be captured whole, and not be empty";
		case "COMPLETE":
			return "Must be captured whole";
		default:
			return null;
	}
}

/**
 * Which part of the picture a source belongs to: the change itself, the surroundings it has to be
 * read against, or what this workspace already said to the person who wrote it.
 */
export type EvidenceSourceFamily = "work" | "around" | "history" | "other";

export interface EvidenceSourceFamilyDef {
	label: string;
	icon: LucideIcon;
}

export const EVIDENCE_SOURCE_FAMILY: Record<EvidenceSourceFamily, EvidenceSourceFamilyDef> = {
	work: { label: "The work itself", icon: FileSearchIcon },
	around: { label: "Around the work", icon: NetworkIcon },
	history: { label: "This person's history", icon: HistoryIcon },
	other: { label: "Other sources", icon: CircleDashedIcon },
};

/**
 * Keys are wire ids and never reach an operator; every word on screen comes from the source's own
 * `displayName`. A source this build has not been taught falls to "Other sources" rather than being
 * filed under a heading that would be a guess about what it is.
 */
const SOURCE_FAMILIES: Record<string, EvidenceSourceFamily> = {
	"scm.pull-request.core": "work",
	"scm.pull-request.diff": "work",
	"scm.pull-request.comments": "work",
	"scm.review-threads": "work",
	"scm.general-review-comments": "work",
	"scm.pull-request.commits": "work",
	"scm.issue.core": "work",
	"scm.issue.comments": "work",
	"slack.conversation.thread": "work",
	"docs.document.core": "work",
	"scm.repository.tree": "around",
	"scm.linked-work-items": "around",
	"workspace.project-inventory": "around",
	"outline.documents": "around",
	"hephaestus.observation-history": "history",
	"hephaestus.feedback-history": "history",
};

export interface EvidenceSourceGroup {
	family: EvidenceSourceFamily;
	def: EvidenceSourceFamilyDef;
	sources: PracticeEvidenceSourceOption[];
}

const FAMILY_ORDER: EvidenceSourceFamily[] = ["work", "around", "history", "other"];

/**
 * The work type's sources in family order, keeping the catalogue's order within each family and
 * dropping families this work type has no source for.
 */
export function groupEvidenceSources(
	sources: readonly PracticeEvidenceSourceOption[],
): EvidenceSourceGroup[] {
	return FAMILY_ORDER.map((family) => ({
		family,
		def: EVIDENCE_SOURCE_FAMILY[family],
		sources: sources.filter((source) => (SOURCE_FAMILIES[source.sourceKind] ?? "other") === family),
	})).filter((group) => group.sources.length > 0);
}

export function canAttemptAutomatedReview(
	requirements: PracticeAutomatedReviewPolicy,
	supportedModes: readonly PracticeAutomatedReviewPolicy["automatedReview"]["mode"][],
) {
	return (
		supportedModes.includes(requirements.automatedReview.mode) &&
		requirements.automatedReview.evidenceSufficiency === "SUFFICIENT_WHEN_REQUIREMENTS_MET"
	);
}

export function automatedReviewUnavailableLabel(
	requirements: PracticeAutomatedReviewPolicy,
	supportedModes: readonly PracticeAutomatedReviewPolicy["automatedReview"]["mode"][],
) {
	if (requirements.automatedReview.mode === "NONE") return "Guidance only";
	if (requirements.automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT") {
		return "Human review needed";
	}
	if (!supportedModes.includes(requirements.automatedReview.mode)) {
		return "AI support unavailable";
	}
	return null;
}

/**
 * Null where the practice behaves the way every other one does: AI-supported mentoring is the norm,
 * so badging it says nothing and buries the answers that do carry information.
 */
export function automatedReviewLimitationLabel(
	automatedReview: PracticeAutomatedReviewPolicy["automatedReview"],
) {
	if (automatedReview.mode === "NONE") return "Guidance only";
	if (automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT") {
		return "Human review needed";
	}
	return null;
}

/** The detail-view label, where naming the norm is an answer rather than noise. */
export function mentoringSupportLabel(
	automatedReview: PracticeAutomatedReviewPolicy["automatedReview"],
) {
	return automatedReviewLimitationLabel(automatedReview) ?? "AI-supported mentoring";
}

/**
 * Keyed on the generated union, so a reason added server-side fails the build here rather than
 * reaching an admin as a raw constant name.
 */
export function readinessReasonLabel(reasonCode: PracticeEvidenceReason): string {
	return READINESS_REASON_LABELS[reasonCode];
}

const READINESS_REASON_LABELS: Record<PracticeEvidenceReason, string> = {
	SOURCE_NOT_AVAILABLE: "was not available",
	SOURCE_INCOMPLETE: "was not fully captured",
	SOURCE_EMPTY: "was empty",
	NO_AUTOMATED_REVIEW: "this practice is not set up for automated review",
	DECLARED_EVIDENCE_INSUFFICIENT: "this practice declares its evidence insufficient",
	SUBJECT_NOT_IN_THE_WORK: "the work did not contain this practice's subject",
};
