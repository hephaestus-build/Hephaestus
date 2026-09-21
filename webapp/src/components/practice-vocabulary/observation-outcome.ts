import {
	CircleCheckIcon,
	CircleDashedIcon,
	CircleHelpIcon,
	CircleXIcon,
	type LucideIcon,
	ShieldCheckIcon,
} from "lucide-react";

import type { PracticeStandingObservation } from "@/api/types.gen";

import { type Assessment, ASSESSMENT_DEFS } from "./assessment-defs";
import { type Presence, PRESENCE_DEFS } from "./presence-defs";
import type { StatusDef } from "./status-def";

interface ObservationOutcomeInput {
	presence: Presence;
	assessment?: Assessment;
}

export type ObservationOutcome =
	| "PRESENT_GOOD"
	| "ABSENT_GOOD"
	| "PRESENT_BAD"
	| "ABSENT_BAD"
	| "NOT_APPLICABLE"
	| "INCONCLUSIVE";

/**
 * The words, icon, colour and one-line description of each outcome. The descriptions are the
 * presence and assessment registries' own sentences; a risk avoided is the one outcome neither
 * registry has a word for, so its sentence is the outcome model's definition of it.
 */
export const OBSERVATION_OUTCOME_PRESENTATION = {
	PRESENT_GOOD: {
		label: "Strength shown",
		icon: CircleCheckIcon,
		className: "text-success",
		description: ASSESSMENT_DEFS.GOOD.description,
	},
	ABSENT_GOOD: {
		label: "Risk avoided",
		icon: ShieldCheckIcon,
		className: "text-success",
		description: "A harmful behaviour could have appeared in the work and did not.",
	},
	// The assessment registry's own words and wrench: a problem seen is the work needing improvement.
	PRESENT_BAD: {
		label: ASSESSMENT_DEFS.BAD.label,
		icon: ASSESSMENT_DEFS.BAD.icon,
		className: "text-destructive",
		description: ASSESSMENT_DEFS.BAD.description,
	},
	ABSENT_BAD: {
		label: "Expected practice missing",
		icon: CircleXIcon,
		className: "text-destructive",
		description: PRESENCE_DEFS.ABSENT.description,
	},
	NOT_APPLICABLE: {
		label: "Not assessed",
		icon: CircleDashedIcon,
		className: "text-muted-foreground",
		description: PRESENCE_DEFS.NOT_APPLICABLE.description,
	},
	INCONCLUSIVE: {
		label: "Not certain enough to say",
		icon: CircleHelpIcon,
		className: "text-muted-foreground",
		description: PRESENCE_DEFS.INCONCLUSIVE.description,
	},
} as const satisfies Record<
	ObservationOutcome,
	Pick<StatusDef, "label" | "description"> & { icon: LucideIcon; className: string }
>;
export function observationOutcome(observation: ObservationOutcomeInput): ObservationOutcome {
	if (observation.presence === "INCONCLUSIVE") {
		return "INCONCLUSIVE";
	}
	if (observation.presence === "NOT_APPLICABLE" || !observation.assessment) {
		return "NOT_APPLICABLE";
	}
	return `${observation.presence}_${observation.assessment}`;
}

/**
 * The outcome the wire records on a piece of reviewed work is one of the four assessed cells:
 * a strength shown, a risk avoided, a problem seen, an expected practice missing. Mapped once,
 * so a feedback card's strip and an observation row say the same words for the same thing.
 */
export const OBSERVATION_OUTCOME_OF_WORK: Record<
	PracticeStandingObservation["outcome"],
	ObservationOutcome
> = {
	DEMONSTRATED_STRENGTH: "PRESENT_GOOD",
	SAFE_AVOIDANCE: "ABSENT_GOOD",
	COMMISSION_PROBLEM: "PRESENT_BAD",
	OMISSION_GAP: "ABSENT_BAD",
};
