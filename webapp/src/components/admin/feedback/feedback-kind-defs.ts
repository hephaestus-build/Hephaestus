import { Bug, Lightbulb, MessageSquare } from "lucide-react";

import type { FeedbackItem } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

/** What a member chose to send, as the inbox badges it. */
export const FEEDBACK_KIND_DEFS: StatusDefs<FeedbackItem["kind"]> = {
	IDEA: {
		label: "Idea",
		icon: Lightbulb,
		badgeVariant: "default",
		description: "A feature or change the sender would like; worth passing on to the project.",
	},
	BUG: {
		label: "Bug",
		icon: Bug,
		badgeVariant: "outline",
		description: "Something that did not work and, when the sender chose, the page it happened on.",
	},
	FEEDBACK: {
		label: "Feedback",
		icon: MessageSquare,
		badgeVariant: "secondary",
		description: "What works and what gets in the way, in the sender's words.",
	},
};
