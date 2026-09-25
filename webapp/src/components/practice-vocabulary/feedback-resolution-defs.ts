import { CircleCheckIcon, CircleSlashIcon, MessageCircleQuestionMarkIcon } from "lucide-react";

import type { FeedbackResponse } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type FeedbackResolution = NonNullable<FeedbackResponse["resolution"]>;
export const FEEDBACK_RESOLUTION_DEFS: StatusDefs<FeedbackResolution> = {
	ADDRESSED: {
		label: "Addressed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "You changed the work in response to this.",
	},
	DISPUTED: {
		label: "Disputed",
		icon: MessageCircleQuestionMarkIcon,
		badgeVariant: "warning",
		description: "You disagree with this observation. Say why, so a human can weigh it.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "The observation does not apply to this work.",
	},
};
