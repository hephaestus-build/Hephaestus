import { CircleCheckIcon, WrenchIcon } from "lucide-react";
import type { StatusDefs } from "@/components/common/status-def";

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

/**
 * The outcomes as a count names them: "1 positive outcome", "27 negative outcomes". The label with
 * a number in front, so a count and the badge on the rows it opens read as the same word.
 */
export const OUTCOME_COUNT_NOUNS = {
	POSITIVE: { one: "positive outcome", other: "positive outcomes" },
	NEGATIVE: { one: "negative outcome", other: "negative outcomes" },
} as const satisfies Record<Outcome, { one: string; other: string }>;

export function outcomeCountNoun(outcome: Outcome, count: number): string {
	const noun = OUTCOME_COUNT_NOUNS[outcome];
	return count === 1 ? noun.one : noun.other;
}

export function derivedOutcome(
	presence: "PRESENT" | "ABSENT",
	assessment: "GOOD" | "BAD",
): Outcome {
	return (presence === "PRESENT") === (assessment === "GOOD") ? "POSITIVE" : "NEGATIVE";
}
