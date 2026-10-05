import {
	CircleCheckIcon,
	CircleMinusIcon,
	CircleSlashIcon,
	EyeOffIcon,
	SparkleIcon,
	UserCheckIcon,
} from "lucide-react";

import type { StatusDef } from "@/components/common/status-def";

/**
 * Where one piece of practice feedback stands. Only the work resolves a card: the reader's own
 * answer is a claim the next work confirms, so `marked` never reads as `resolved`.
 * `practice-feedback-cards.ts` maps the wire's timestamps onto these.
 */
export type FeedbackState = "new" | "open" | "resolved" | "marked" | "closed" | "withdrawn";

/** A card the reader can still act on: neither resolved nor closed. */
export const isOpenFeedback = (state: FeedbackState): boolean =>
	state === "new" || state === "open";

export interface FeedbackStateDef extends StatusDef {
	/**
	 * The badge's colour where the palette gives the state one beyond its variant: a new card is
	 * the one thing on the page the eye should land on, so its badge wears the mentor accent
	 * (`webapp/AGENTS.md` § Practice surfaces palette).
	 */
	className?: string;
	/** The word before the day in the card's footer, where the label does not fit there. */
	stamp?: string;
}

export const FEEDBACK_STATE_DEFS: Record<FeedbackState, FeedbackStateDef> = {
	new: {
		label: "New",
		icon: SparkleIcon,
		badgeVariant: "outline",
		className: "border-mentor/35 text-mentor",
		description: "Feedback you have not opened yet.",
	},
	open: {
		label: "Open",
		icon: CircleMinusIcon,
		badgeVariant: "outline",
		description: "Resolves once your work comes back clean, or when you mark it as addressed.",
	},
	resolved: {
		label: "Resolved",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Resolved by the work: enough of your work in a row came back clean.",
	},
	marked: {
		label: "Marked by you",
		stamp: "Marked",
		icon: UserCheckIcon,
		badgeVariant: "outline",
		description:
			"You marked this as addressed or not applicable. Your next work confirms it, as the clean work count shows.",
	},
	closed: {
		label: "Closed",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"The practice’s review rules changed after this was written, so it closed unresolved.",
	},
	withdrawn: {
		label: "Withdrawn",
		icon: EyeOffIcon,
		badgeVariant: "outline",
		description:
			"A workspace admin withdrew this feedback because what it said was wrong. It says nothing about how your work stands.",
	},
};
