import { CircleStopIcon, SendIcon, UserRoundCheckIcon } from "lucide-react";

import type { PracticeAutonomy } from "@/lib/practice-autonomy";

import type { StatusDefs } from "@/components/common/status-def";

export const AUTONOMY_DEFS: StatusDefs<PracticeAutonomy> = {
	OFF: {
		label: "Off",
		icon: CircleStopIcon,
		badgeVariant: "secondary",
		description: "No review runs and no feedback is prepared.",
	},
	HUMAN_APPROVAL: {
		label: "Review before sending",
		icon: UserRoundCheckIcon,
		badgeVariant: "warning",
		description: "Feedback is prepared for an authorized person to approve or reject.",
	},
	AUTOMATIC: {
		label: "Send automatically",
		icon: SendIcon,
		badgeVariant: "success",
		description: "Eligible feedback is sent without waiting for approval.",
	},
};
