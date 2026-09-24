import {
	CircleDashedIcon,
	CircleHelpIcon,
	CircleXIcon,
	type LucideIcon,
	ShieldCheckIcon,
} from "lucide-react";

import type { PracticeStandingObservation } from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";

import type { Assessment } from "./assessment-defs";
import { ASSESSMENT_STATUS_DEFS, type AssessmentStatus } from "./assessment-status-defs";
import { OUTCOME_DEFS } from "./outcome-defs";
import type { Presence } from "./presence-defs";

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
 * The words, icon, colour and one-line description of each outcome, in the order a reader ranks
 * them: the two positive cells, the two negative ones, then the two statuses under which nothing
 * was judged. The icons of the assessed cells are the outcome registry's own, so a strength and a
 * problem look the same here as on any surface that only tells positive from negative; the two
 * statuses describe themselves in the status registry's sentences.
 */
export const OBSERVATION_OUTCOME_PRESENTATION = {
	PRESENT_GOOD: {
		label: "Strength shown",
		icon: OUTCOME_DEFS.POSITIVE.icon,
		className: "text-success",
		description: "The practice was applied in this work, and that is worth keeping.",
	},
	ABSENT_BAD: {
		label: "Risk avoided",
		icon: ShieldCheckIcon,
		className: "text-success",
		description: "A harmful behaviour could have appeared in the work and did not.",
	},
	PRESENT_BAD: {
		label: "Needs improvement",
		icon: OUTCOME_DEFS.NEGATIVE.icon,
		className: "text-destructive",
		description: "Something in this work goes against the practice.",
	},
	ABSENT_GOOD: {
		label: "Expected practice missing",
		icon: CircleXIcon,
		className: "text-destructive",
		description: "The practice should have been applied in this work and was not.",
	},
	NOT_APPLICABLE: {
		label: "Not assessed",
		icon: CircleDashedIcon,
		className: "text-muted-foreground",
		description: ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE.description,
	},
	UNDETERMINED: {
		label: "Not certain enough to say",
		icon: CircleHelpIcon,
		className: "text-muted-foreground",
		description: ASSESSMENT_STATUS_DEFS.UNDETERMINED.description,
	},
} as const satisfies Record<
	ObservationOutcome,
	Pick<StatusDef, "label" | "description"> & { icon: LucideIcon; className: string }
>;

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
 * The kind the wire records on a piece of reviewed work is one of the four assessed cells: a
 * strength shown, a risk avoided, a problem seen, an expected practice missing. Mapped once, so a
 * feedback card's strip and an observation row say the same words for the same thing.
 */
export const OBSERVATION_OUTCOME_OF_WORK: Record<
	PracticeStandingObservation["kind"],
	ObservationOutcome
> = {
	DEMONSTRATED_STRENGTH: "PRESENT_GOOD",
	SAFE_AVOIDANCE: "ABSENT_BAD",
	COMMISSION_PROBLEM: "PRESENT_BAD",
	OMISSION_GAP: "ABSENT_GOOD",
};
