import { CircleDashedIcon, CircleXIcon, ShieldCheckIcon } from "lucide-react";

import type { InAppEvidence } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

import type { Assessment } from "./assessment-defs";
import { ASSESSMENT_STATUS_DEFS, type AssessmentStatus } from "./assessment-status-defs";
import { OUTCOME_DEFS } from "./outcome-defs";
import type { Presence } from "./presence-defs";

/**
 * What a review made of one piece of reviewed work, as the wire spells it: the four assessed
 * outcomes and the two statuses under which nothing was judged.
 */
export type ReviewedWorkKind = InAppEvidence["outcome"];

/** The three wire facts an outcome is read from; `presence` and `assessment` only when assessed. */
export interface ObservationOutcomeInput {
	assessmentStatus: AssessmentStatus;
	presence?: Presence | null;
	assessment?: Assessment | null;
}

/**
 * One cell of the outcome matrix, or the status that says why no cell was reached. `presence`
 * says whether the specified behaviour was found and `assessment` whether that behaviour is
 * desirable here, so the two absent cells read against the grain: a desirable behaviour absent is
 * a gap, an undesirable one absent is a risk avoided.
 */
export type ObservationOutcome =
	| "PRESENT_GOOD"
	| "ABSENT_BAD"
	| "PRESENT_BAD"
	| "ABSENT_GOOD"
	| "NOT_APPLICABLE"
	| "UNDETERMINED";

/**
 * Each outcome in the order a reader ranks them: the two positive cells, the two negative ones,
 * then the two statuses under which nothing was judged. The assessed cells take the outcome
 * registry's icon and tone, so a strength and a problem look the same here as on any surface that
 * only tells positive from negative; the two statuses are the status registry's.
 */
export const OBSERVATION_OUTCOME_PRESENTATION: StatusDefs<ObservationOutcome> = {
	PRESENT_GOOD: {
		label: "Strength shown",
		icon: OUTCOME_DEFS.POSITIVE.icon,
		badgeVariant: OUTCOME_DEFS.POSITIVE.badgeVariant,
		description: "The practice was applied in this work, and that is worth keeping.",
	},
	ABSENT_BAD: {
		label: "Risk avoided",
		icon: ShieldCheckIcon,
		badgeVariant: OUTCOME_DEFS.POSITIVE.badgeVariant,
		description: "A harmful behaviour could have appeared in the work and did not.",
	},
	PRESENT_BAD: {
		label: "Needs improvement",
		icon: OUTCOME_DEFS.NEGATIVE.icon,
		badgeVariant: OUTCOME_DEFS.NEGATIVE.badgeVariant,
		description: "Something in this work goes against the practice.",
	},
	ABSENT_GOOD: {
		label: "Expected practice missing",
		icon: CircleXIcon,
		badgeVariant: OUTCOME_DEFS.NEGATIVE.badgeVariant,
		description: "The practice should have been applied in this work and was not.",
	},
	NOT_APPLICABLE: {
		...ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE,
		icon: CircleDashedIcon,
	},
	UNDETERMINED: ASSESSMENT_STATUS_DEFS.UNDETERMINED,
};

/** The status explains an unassessed observation; an assessed one is the cell its two axes name. */
export function observationOutcome(observation: ObservationOutcomeInput): ObservationOutcome {
	if (observation.assessmentStatus !== "ASSESSED") {
		return observation.assessmentStatus;
	}
	if (!observation.presence || !observation.assessment) {
		throw new Error("Assessed observations require presence and assessment");
	}
	return `${observation.presence}_${observation.assessment}`;
}

/**
 * The outcome the wire records on a piece of reviewed work, mapped once, so a feedback card's strip
 * and an observation row say the same words for the same thing. The four assessed cells are what a
 * surface normally shows — a strength shown, a risk avoided, a problem seen, an expected practice
 * missing — and the two statuses are here because the wire's outcome is the whole enum: a piece of
 * work nothing was judged on says so rather than borrowing another cell's words.
 */
export const OBSERVATION_OUTCOME_OF_WORK: Record<ReviewedWorkKind, ObservationOutcome> = {
	DEMONSTRATED_STRENGTH: "PRESENT_GOOD",
	SAFE_AVOIDANCE: "ABSENT_BAD",
	COMMISSION_PROBLEM: "PRESENT_BAD",
	OMISSION_GAP: "ABSENT_GOOD",
	NOT_APPLICABLE: "NOT_APPLICABLE",
	UNDETERMINED: "UNDETERMINED",
};
