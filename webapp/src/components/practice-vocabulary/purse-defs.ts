import { KeyRoundIcon, type LucideIcon, ServerIcon } from "lucide-react";

import { statusValues } from "@/components/common/status-def";

/** Whose money a model call spends: the host's shared models, or the workspace's own provider. */
export type Purse = "SHARED" | "OWN_PROVIDER";

interface PurseDef {
	label: string;
	icon: LucideIcon;
	/** Who sets its cap or bills it, under its spend on the workspace's AI usage page. */
	description: string;
}

/**
 * A plain map rather than `StatusDefs`: a purse is not a state, so it has no tone and no badge. Two
 * purses are different people's money, so no surface adds them together. A label reads the same to
 * a workspace admin and to an instance admin who opened the workspace's details.
 */
export const PURSE_DEFS = {
	SHARED: {
		label: "Shared models",
		icon: ServerIcon,
		description: "Set by an instance admin",
	},
	OWN_PROVIDER: {
		label: "Own provider",
		icon: KeyRoundIcon,
		description: "Billed to you by your provider",
	},
} satisfies Record<Purse, PurseDef>;

/** Every purse, in the order a usage table shows them. */
export const PURSES = statusValues(PURSE_DEFS);
