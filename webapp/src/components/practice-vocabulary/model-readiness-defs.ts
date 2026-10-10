import { CircleDollarSignIcon, CircleOffIcon, LockIcon, UnplugIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/**
 * Why a registered model, or the provider it runs on, cannot be used now. A model that can run is
 * the normal case and draws nothing.
 */
export type ModelReadiness = "OFF" | "CONNECTION_OFF" | "PRICE_MISSING" | "NO_WORKSPACE_ACCESS";

export const MODEL_READINESS_DEFS: StatusDefs<ModelReadiness> = {
	OFF: {
		label: "Off",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "Turned off, so nothing new runs on it until it is turned on again.",
	},
	CONNECTION_OFF: {
		label: "Connection off",
		icon: UnplugIcon,
		badgeVariant: "secondary",
		description: "Its connection is turned off, so nothing new runs on this model.",
	},
	PRICE_MISSING: {
		label: "Price missing",
		icon: CircleDollarSignIcon,
		badgeVariant: "warning",
		description: "Workspaces cannot use it until it has a price or is marked as having no cost.",
	},
	NO_WORKSPACE_ACCESS: {
		label: "No workspace access",
		icon: LockIcon,
		badgeVariant: "warning",
		description: "It is shared with no workspace, so none can pick it.",
	},
};
