import {
	CircleCheckIcon,
	CircleMinusIcon,
	CircleSlashIcon,
	EyeOffIcon,
	SparkleIcon,
} from "lucide-react";

import type { StatusDef } from "@/components/common/status-def";

/**
 * Where one piece of practice feedback on the Practice profile stands: not yet read, open,
 * resolved — by the work or by the reader, which the card's condition line tells apart — or
 * closed without a resolution because the practice's review rules changed after it was written, or
 * withdrawn by a workspace admin because what it said was wrong. The server records when it was read,
 * when and by what it closed, and when it was withdrawn; `practice-feedback-cards.ts` reads that into
 * one of these five, and this registry is the words for it.
 */
export type FeedbackState = "new" | "open" | "resolved" | "closed" | "withdrawn";

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
		description: "Resolved by the work, or marked as addressed or not applicable.",
	},
	closed: {
		label: "Closed",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"The practice's review rules changed after this was written, so it closed unresolved.",
	},
	withdrawn: {
		label: "Withdrawn",
		icon: EyeOffIcon,
		badgeVariant: "outline",
		description:
			"A workspace admin withdrew this feedback because what it said was wrong. It says nothing about how your work stands.",
	},
};
