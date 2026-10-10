import { CircleAlertIcon, CirclePauseIcon, GaugeIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

import type { Purse } from "./purse-defs";

/** A spend limit worth naming. A limit with room left and every call priced has no state, so no badge. */
export type CapState = "PAUSED" | "NEAR" | "UNPRICED";

/** Worst first, so a pause is never listed under a warning. */
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
			label: "No price set",
			icon: CircleAlertIcon,
			badgeVariant: "warning",
			description: "Some calls have no price, so real spend may be higher than shown.",
		},
	};
}

/** The host's limit is a budget and the workspace's own is a cap (`llm-cost-vocabulary.md` Rule 3). */
export const CAP_STATE_DEFS = {
	SHARED: capStateDefs("budget"),
	OWN_PROVIDER: capStateDefs("cap"),
} satisfies Record<Purse, StatusDefs<CapState>>;
