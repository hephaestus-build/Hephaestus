import { CircleCheckIcon, WrenchIcon } from "lucide-react";
import type { StatusDefs } from "./status-def";

export type Outcome = "POSITIVE" | "NEGATIVE";
export const OUTCOME_DEFS: StatusDefs<Outcome> = {
	POSITIVE: {
		label: "Positive outcome",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description:
			"Desirable behaviour is present, or undesirable behaviour is absent from the applicable, fully searched evidence.",
	},
	NEGATIVE: {
		label: "Negative outcome",
		icon: WrenchIcon,
		badgeVariant: "destructive",
		description: "Undesirable behaviour is present, or required desirable behaviour is missing.",
	},
};

export function derivedOutcome(
	presence: "PRESENT" | "ABSENT",
	assessment: "GOOD" | "BAD",
): Outcome {
	return (presence === "PRESENT") === (assessment === "GOOD") ? "POSITIVE" : "NEGATIVE";
}
