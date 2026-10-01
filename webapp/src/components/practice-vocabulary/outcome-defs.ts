import { CircleCheckIcon, CircleDashedIcon, CircleHelpIcon, WrenchIcon } from "lucide-react";
import type { ReviewObservation } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type Outcome = ReviewObservation["outcome"];
export const OUTCOME_DEFS: StatusDefs<Outcome> = {
	MET: {
		label: "Met",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The applicable practice standard is met in the reviewed evidence.",
	},
	NOT_MET: {
		label: "Not met",
		icon: WrenchIcon,
		badgeVariant: "destructive",
		description: "The applicable practice standard is not met in the reviewed evidence.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleDashedIcon,
		badgeVariant: "secondary",
		description: "The work offers no occasion for this practice.",
	},
	UNDETERMINED: {
		label: "Undetermined",
		icon: CircleHelpIcon,
		badgeVariant: "secondary",
		description: "The captured evidence does not settle whether the practice standard is met.",
	},
};

export function outcomeCountNoun(outcome: Outcome, count: number): string {
	const label = OUTCOME_DEFS[outcome].label.toLowerCase();
	return `${label} ${count === 1 ? "observation" : "observations"}`;
}
