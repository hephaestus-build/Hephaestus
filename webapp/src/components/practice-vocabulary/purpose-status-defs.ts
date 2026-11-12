import {
	CircleDashedIcon,
	CircleDotDashedIcon,
	CircleOffIcon,
	TriangleAlertIcon,
} from "lucide-react";

import type { AgentBinding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type PurposeStatus = "PARTLY_SET" | "NOT_SET" | "NEEDS_ATTENTION" | "OFF";

export const PURPOSE_STATUS_DEFS = {
	PARTLY_SET: {
		label: "Not set for some members",
		icon: CircleDotDashedIcon,
		badgeVariant: "warning",
		description: "Some members get no model for this. The row shows which.",
	},
	NOT_SET: {
		label: "Not set",
		icon: CircleDashedIcon,
		badgeVariant: "warning",
		description: "No member gets a model for this.",
	},
	NEEDS_ATTENTION: {
		label: "Needs attention",
		icon: TriangleAlertIcon,
		badgeVariant: "destructive",
		description: "An assigned model cannot run.",
	},
	OFF: {
		label: "Off",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "Practice reviews are off, so no model is used for this.",
	},
} satisfies StatusDefs<PurposeStatus>;

export interface PurposeStatusInput {
	/** This purpose's bindings only. */
	bindings: readonly Pick<AgentBinding, "enabled" | "ready">[];
	/**
	 * Whether each member choice the row shows gets a model, judged as the row's tier columns judge
	 * it. Once members must choose, the row shows no column for members who have not chosen.
	 */
	served: readonly boolean[];
	/** The purpose runs inside practice reviews, and they are off. */
	off: boolean;
}

export function purposeStatus({ bindings, served, off }: PurposeStatusInput): PurposeStatus | null {
	if (off) {
		return "OFF";
	}
	if (bindings.some((binding) => binding.enabled && !binding.ready)) {
		return "NEEDS_ATTENTION";
	}
	if (served.every(Boolean)) {
		return null;
	}
	return served.some(Boolean) ? "PARTLY_SET" : "NOT_SET";
}
