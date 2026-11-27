import { CircleCheckIcon, CircleDotIcon, CircleSlashIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/** Where a team decision on one problem stands. A member records each state by an explicit act. */
export type TeamDecisionState = "OPEN" | "ACTED_ON" | "DISMISSED";

export const TEAM_DECISION_STATE_DEFS = {
	OPEN: {
		label: "Open",
		icon: CircleDotIcon,
		badgeVariant: "outline",
		description: "The team has not acted on this problem yet.",
	},
	ACTED_ON: {
		label: "Acted on",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "A member recorded that the team acted on this problem.",
	},
	DISMISSED: {
		label: "Dismissed",
		icon: CircleSlashIcon,
		badgeVariant: "secondary",
		description: "A member recorded that the team will not act on this problem.",
	},
} satisfies StatusDefs<TeamDecisionState>;
