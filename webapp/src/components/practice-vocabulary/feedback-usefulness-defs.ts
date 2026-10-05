import { ThumbsDownIcon, ThumbsUpIcon } from "lucide-react";

import type { FeedbackResponse } from "@/api/types.gen";

import type { StatusDef } from "@/components/common/status-def";

export type FeedbackUsefulness = NonNullable<FeedbackResponse["usefulness"]>;

export interface FeedbackUsefulnessDef extends StatusDef {
	/** The optional note a rating opens under its card. */
	note: { name: string; label: string; placeholder: string };
}

export const FEEDBACK_USEFULNESS_DEFS: Record<FeedbackUsefulness, FeedbackUsefulnessDef> = {
	HELPFUL: {
		label: "Helpful",
		icon: ThumbsUpIcon,
		badgeVariant: "success",
		description: "This told you something you could act on.",
		note: {
			name: "What was helpful",
			label: "What worked about this feedback?",
			placeholder: "Optional: what helped, or what you did",
		},
	},
	UNHELPFUL: {
		label: "Not helpful",
		icon: ThumbsDownIcon,
		badgeVariant: "destructive",
		description: "This did not tell you anything you could act on.",
		note: {
			name: "What was not helpful",
			label: "Why was this not helpful?",
			placeholder: "Optional: what was missing or off",
		},
	},
};
