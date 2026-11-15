import { CircleDollarSignIcon, CircleOffIcon, GaugeIcon, LockIcon, UnplugIcon } from "lucide-react";

import type { LlmModel } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/** Whether a registered model can run now, or the first reason it cannot. The server decides it for both consoles. */
export type ModelReadiness = LlmModel["readiness"];

/**
 * Why a model, or the provider it runs on, cannot be used now. A model that can run is the normal
 * case and draws no badge.
 */
export type ModelNotReady = Exclude<ModelReadiness, "READY">;

export const MODEL_READINESS_DEFS: StatusDefs<ModelNotReady> = {
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
		description: "It cannot be turned on until it has a price or is marked as having no cost.",
	},
	LIMITS_MISSING: {
		label: "Limits missing",
		icon: GaugeIcon,
		badgeVariant: "warning",
		description:
			"Hephaestus refuses its calls until it knows its output limit. Set Max output tokens or Context window, or test the connection.",
	},
	NO_WORKSPACE_ACCESS: {
		label: "No workspace access",
		icon: LockIcon,
		badgeVariant: "warning",
		description: "It is shared with no workspace, so none can pick it.",
	},
};
