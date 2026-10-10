import { CircleAlertIcon } from "lucide-react";

import type { AgentBinding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

/** Only the exception has a status: a bound model that can run is the normal case and draws nothing. */
export type BindingReadiness = "NOT_READY";

/**
 * Whether a bound model can run for its row right now, as the server judged it: the model and its
 * connection are on, the workspace may use it, and its declared tier still fits the row.
 */
export const BINDING_READINESS_DEFS: StatusDefs<BindingReadiness> = {
	NOT_READY: {
		label: "Not ready",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description:
			"This model cannot run: it or its connection is off, or it is no longer declared as this assignment’s tier.",
	},
};

export function bindingReadiness(binding: Pick<AgentBinding, "ready">): BindingReadiness | null {
	return binding.ready ? null : "NOT_READY";
}
