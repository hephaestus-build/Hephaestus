import type { CloseResearchDisclosure, ResearchRemovalObligation } from "@/api/types.gen";
import { definitionFor, UNKNOWN_STATUS_DEF } from "@/components/common/status-def";

/** Why the research team must remove a participant's rows from a copy. The one owner of these words. */
export const REMOVAL_REASON_LABELS = {
	CONSENT_WITHDRAWN: "Withdrew consent",
	CONSENT_SUPERSEDED: "Gave a newer consent answer",
	QUESTION_CHANGED: "Research question changed",
	ACCOUNT_ERASED: "Account erased",
} satisfies Record<string, string>;

/** Why a removal is owed, or "Unknown" for a reason that this build does not know. */
export const removalReasonLabel = (reason: ResearchRemovalObligation["reason"]) =>
	definitionFor(REMOVAL_REASON_LABELS, reason, UNKNOWN_STATUS_DEF.label);

/** The closures in the order the dialog offers them; each is a key of `CLOSURE_LABELS`. */
export const CLOSURE_OPTIONS = [
	"DESTROYED",
	"ANONYMIZED",
] as const satisfies readonly CloseResearchDisclosure["closure"][];

/** What the operator states happened to every retained copy when a dataset is closed. */
export const CLOSURE_LABELS = {
	DESTROYED: {
		label: "Every copy was deleted",
		description: "No copy of the file or of data taken from it remains with the research team.",
	},
	ANONYMIZED: {
		label: "Every copy was anonymized",
		description: "What remains can no longer be linked to any participant, even with other data.",
	},
} satisfies Record<CloseResearchDisclosure["closure"], { label: string; description: string }>;
