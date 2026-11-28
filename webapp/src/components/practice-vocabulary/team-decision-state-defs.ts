import { CircleCheckIcon, CircleDotIcon, CircleSlashIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/** Where a team decision on one problem stands. A member records each state by an explicit act. */
export type TeamDecisionState = "OPEN" | "ACTED_ON" | "DISMISSED";

export const TEAM_DECISION_STATE_DEFS = {
	OPEN: {
		label: "Open",
		icon: CircleDotIcon,
		badgeVariant: "outline",
		description: "The problem still needs the team’s attention.",
	},
	ACTED_ON: {
		label: "Acted on",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The team changed the work for this problem.",
	},
	DISMISSED: {
		label: "Dismissed",
		icon: CircleSlashIcon,
		badgeVariant: "secondary",
		description: "The team will not change the work for this problem.",
	},
} satisfies StatusDefs<TeamDecisionState>;
