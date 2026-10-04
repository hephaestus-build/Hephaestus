import {
	AsteriskIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleMinusIcon,
	SlidersHorizontalIcon,
	ThumbsUpIcon,
	TriangleAlertIcon,
} from "lucide-react";

import type { ConfigurationFact } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type ConfigurationStatus = ConfigurationFact["status"];
export type ConfigurationRequirement = ConfigurationFact["requirement"];

/**
 * Declaration order is the order the overview lists the groups in: what needs the operator first.
 * `NOT_CONFIGURED` is neutral on purpose, because the server reports it only for a setting that is
 * allowed to stay absent.
 */
export const CONFIGURATION_STATUS_DEFS: StatusDefs<ConfigurationStatus> = {
	ACTION_REQUIRED: {
		label: "Action required",
		icon: TriangleAlertIcon,
		badgeVariant: "warning",
		description: "A check that applies to this instance failed.",
	},
	NOT_CONFIGURED: {
		label: "Not configured",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "Optional settings you have not set. Nothing is broken.",
	},
	SATISFIED: {
		label: "Passed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The check passed.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleMinusIcon,
		badgeVariant: "secondary",
		description: "The check does not apply to the runtime roles of this server.",
	},
};

/** Ordered from the strictest down, which is the order rows sort in inside a group. */
export const CONFIGURATION_REQUIREMENT_DEFS: StatusDefs<ConfigurationRequirement> = {
	REQUIRED: {
		label: "Required",
		icon: AsteriskIcon,
		badgeVariant: "secondary",
		description: "The instance does not work without it.",
	},
	RECOMMENDED: {
		label: "Recommended",
		icon: ThumbsUpIcon,
		badgeVariant: "secondary",
		description: "The instance works without it, but the setting is advised.",
	},
	OPTIONAL: {
		label: "Optional",
		icon: SlidersHorizontalIcon,
		badgeVariant: "outline",
		description: "A capability you can leave off.",
	},
};
