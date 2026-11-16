import { CircleAlertIcon, CirclePauseIcon, GaugeIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

import type { Purse } from "./purse-defs";

export type CapState = "PAUSED" | "NEAR" | "UNPRICED";

function capStateDefs(limit: "budget" | "cap"): StatusDefs<CapState> {
	return {
		PAUSED: {
			label: "Paused",
			icon: CirclePauseIcon,
			badgeVariant: "destructive",
			description: `New calls on these models wait until the ${limit} is raised or the month ends.`,
		},
		NEAR: {
			label: `Near the ${limit}`,
			icon: GaugeIcon,
			badgeVariant: "warning",
			description: `Most of this month’s ${limit} is used.`,
		},
		UNPRICED: {
			label: "Unavailable cost",
			icon: CircleAlertIcon,
			badgeVariant: "warning",
			description:
				"A rate is missing or a measurement is unverified, so real spend may be higher than shown.",
		},
	};
}

/** The host's limit is a budget and the workspace's own is a cap (`llm-cost-vocabulary.mdx` Rule 3). */
export const CAP_STATE_DEFS = {
	SHARED: capStateDefs("budget"),
	OWN_PROVIDER: capStateDefs("cap"),
} satisfies Record<Purse, StatusDefs<CapState>>;
