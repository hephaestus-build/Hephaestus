import { AsteriskIcon } from "lucide-react";

import type { PrecomputeNeed } from "@/api/types.gen";

import type { StatusDef } from "@/components/common/status-def";

export type PrecomputeNeedKind = PrecomputeNeed["need"];

/**
 * A model without which a practice's precompute script does not run. An optional model is the
 * normal case and draws nothing, so only the required one has words, an icon and a tone.
 */
export const PRECOMPUTE_REQUIRED_DEF: StatusDef = {
	label: "Required",
	icon: AsteriskIcon,
	badgeVariant: "secondary",
	description: "Without this model the precompute script does not run.",
};

/** The badge a need wears, or null for an optional one, which wears none. */
export function precomputeNeedBadge(need: PrecomputeNeedKind): StatusDef | null {
	return need === "REQUIRED" ? PRECOMPUTE_REQUIRED_DEF : null;
}
