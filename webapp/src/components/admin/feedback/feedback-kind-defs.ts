import { Bug, MessageSquare } from "lucide-react";

import type { FeedbackItem } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

/** What a member chose to send, as the inbox badges it. */
export const FEEDBACK_KIND_DEFS: StatusDefs<FeedbackItem["kind"]> = {
	FEEDBACK: {
		label: "Feedback",
		icon: MessageSquare,
		badgeVariant: "secondary",
		description: "A thought about the product, sent from the header.",
	},
	BUG: {
		label: "Bug report",
		icon: Bug,
		badgeVariant: "outline",
		description: "Something that did not work, with the page it happened on.",
	},
};
