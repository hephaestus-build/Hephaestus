import { CircleAlertIcon, CircleCheckIcon } from "lucide-react";

import type { AgentBinding } from "@/api/types.gen";

import type { StatusDefs } from "./status-def";

export type BindingReadiness = "READY" | "NOT_READY";

/**
 * Whether a bound model can run for its row right now, as the server judged it: the model and its
 * connection are on, the workspace may use it, and its declared tier still fits the row.
 */
export const BINDING_READINESS_DEFS: StatusDefs<BindingReadiness> = {
	READY: {
		label: "Ready",
		icon: CircleCheckIcon,
		badgeVariant: "secondary",
		description: "This model can run for the members this row serves.",
	},
	NOT_READY: {
		label: "Not ready",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description:
			"This model can't run: it or its connection is off, or it is no longer declared as this row's tier.",
	},
};

export function bindingReadiness(binding: Pick<AgentBinding, "ready">): BindingReadiness {
	return binding.ready ? "READY" : "NOT_READY";
}
