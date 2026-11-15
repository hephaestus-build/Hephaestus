import { CircleCheckIcon, CircleSlashIcon, MessageCircleQuestionMarkIcon } from "lucide-react";

import type { FeedbackResponse } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type FeedbackResolution = NonNullable<FeedbackResponse["resolution"]>;
/** A resolution that this build names, and the ones that it sends. The API can send others. */
export type KnownFeedbackResolution = keyof typeof FEEDBACK_RESOLUTION_DEFS;
export const FEEDBACK_RESOLUTION_DEFS = {
	ADDRESSED: {
		label: "Addressed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "You changed the work in response to this.",
	},
	DISAGREE: {
		label: "Disagree",
		icon: MessageCircleQuestionMarkIcon,
		badgeVariant: "warning",
		description: "Add your reason so a workspace admin can read it.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "The feedback does not apply to this work.",
	},
} satisfies StatusDefs<string>;
