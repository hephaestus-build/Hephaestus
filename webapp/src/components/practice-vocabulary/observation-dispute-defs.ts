import { MessageSquareIcon, MessageSquareWarningIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/**
 * Whether the developer disputes feedback written from an observation, as workspace admins filter
 * and read it. The developer's own words for their answer are `feedback-resolution-defs`.
 */
export type DeveloperResponse = "DISPUTED" | "UNDISPUTED";

export const DEVELOPER_RESPONSE_DEFS: StatusDefs<DeveloperResponse> = {
	DISPUTED: {
		label: "Disputed",
		icon: MessageSquareWarningIcon,
		badgeVariant: "warning",
		description:
			"The developer disputes feedback written from this observation and explained why. Mark the observation incorrect or withdraw the feedback if they are right.",
	},
	UNDISPUTED: {
		label: "Not disputed",
		icon: MessageSquareIcon,
		badgeVariant: "outline",
		description: "The developer disputes no feedback written from this observation.",
	},
};
