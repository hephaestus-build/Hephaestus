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
		description:
			"Feedback on the work waits for an authorized person to approve or reject it; the developer's own practice pages and the mentor are written regardless.",
	},
	AUTOMATIC: {
		label: "Send automatically",
		icon: SendIcon,
		badgeVariant: "success",
		description: "Eligible feedback is sent onto the work without waiting for approval.",
	},
};
