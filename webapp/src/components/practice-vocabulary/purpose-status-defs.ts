import {
	CircleDashedIcon,
	CircleDotDashedIcon,
	CircleOffIcon,
	TriangleAlertIcon,
} from "lucide-react";

import type { AgentBinding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type PurposeStatus = "PARTLY_SET" | "NOT_SET" | "NEEDS_ATTENTION" | "OFF";

/**
 * One purpose on AI models, judged by who it serves today. A purpose that serves every member has
 * no status and draws no badge: the row's tier columns already say who gets which model. `satisfies`
 * keeps each tone's literal, so a note about one status can take that status's tone as an `Alert`
 * variant.
 */
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
		description:
			"An assigned model cannot run, or a practice needs a model that some members do not get.",
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
	/** A practice's precompute script requires this model and some members do not get one. */
	requiredNeedUnmet: boolean;
	/** False for a precompute purpose that no practice declares; true for every other purpose. */
	used: boolean;
}

/**
 * Null when there is nothing to flag: every member is served, or a precompute model that no practice
 * uses asks for nothing, whatever it assigns; its cells still show the assignments. An enabled
 * assignment the server cannot run, or an unmet required need, outranks coverage.
 */
export function purposeStatus({
	bindings,
	served,
	off,
	requiredNeedUnmet,
	used,
}: PurposeStatusInput): PurposeStatus | null {
	if (off) {
		return "OFF";
	}
	if (!used) {
		return null;
	}
	if (requiredNeedUnmet || bindings.some((binding) => binding.enabled && !binding.ready)) {
		return "NEEDS_ATTENTION";
	}
	if (served.every(Boolean)) {
		return null;
	}
	return served.some(Boolean) ? "PARTLY_SET" : "NOT_SET";
}
