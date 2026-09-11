import {
	CircleAlertIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleHelpIcon,
	CircleXIcon,
	type LucideIcon,
	ShieldCheckIcon,
} from "lucide-react";

import type { Assessment } from "@/components/practice-vocabulary/assessment-defs";
import type { AssessmentStatus } from "@/components/practice-vocabulary/assessment-status-defs";
import type { Presence } from "@/components/practice-vocabulary/presence-defs";
export interface ObservationKindInput {
	assessmentStatus: AssessmentStatus;
	presence?: Presence | null;
	assessment?: Assessment | null;
}

export type ObservationKind =
	| "PRESENT_GOOD"
	| "ABSENT_BAD"
	| "PRESENT_BAD"
	| "ABSENT_GOOD"
	| "NOT_APPLICABLE"
	| "UNDETERMINED";

export const OBSERVATION_KIND_PRESENTATION = {
	PRESENT_GOOD: {
		label: "Strength shown",
		icon: CircleCheckIcon,
		className: "text-success",
	},
	ABSENT_BAD: {
		label: "Undesirable behaviour absent",
		icon: ShieldCheckIcon,
		className: "text-success",
	},
	PRESENT_BAD: {
		label: "Problem observed",
		icon: CircleAlertIcon,
		className: "text-destructive",
	},
	ABSENT_GOOD: {
		label: "Desirable behaviour missing",
		icon: CircleXIcon,
		className: "text-destructive",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleDashedIcon,
		className: "text-muted-foreground",
	},
	UNDETERMINED: {
		label: "Undetermined",
		icon: CircleHelpIcon,
		className: "text-muted-foreground",
	},
} as const satisfies Record<
	ObservationKind,
	{ label: string; icon: LucideIcon; className: string }
>;
export function observationKind(observation: ObservationKindInput): ObservationKind {
	if (observation.assessmentStatus !== "ASSESSED") return observation.assessmentStatus;
	if (!observation.presence || !observation.assessment)
		throw new Error("Assessed observations require presence and assessment");
	return `${observation.presence}_${observation.assessment}`;
}
