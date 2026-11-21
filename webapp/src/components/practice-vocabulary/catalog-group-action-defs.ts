import { CircleAlert, CircleCheck, CornerDownLeft, Plus } from "lucide-react";

import type { CatalogGroupPracticeAction } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type CatalogGroupAction = CatalogGroupPracticeAction["action"];

export const CATALOG_GROUP_ACTION_DEFS: StatusDefs<CatalogGroupAction> = {
	ADD: {
		label: "Adds",
		icon: Plus,
		badgeVariant: "secondary",
		description: "A new copy this workspace owns.",
	},
	MOVE_TO_GROUP: {
		label: "Moves back",
		icon: CornerDownLeft,
		badgeVariant: "secondary",
		description: "An unassigned copy returns here. Local edits are kept.",
	},
	KEEP: {
		label: "Already here",
		icon: CircleCheck,
		badgeVariant: "outline",
		description: "Nothing changes.",
	},
	BLOCKED: {
		label: "Blocked",
		icon: CircleAlert,
		badgeVariant: "warning",
		// Blocked for several reasons: another practice uses the name, the copy sits in another group,
		// or its folder in the practice repository sets its group. The words hold for each of them.
		description: "This practice stays as it is.",
	},
};

/** The actions that change the workspace, so a preview can count them without re-listing them. */
export const CATALOG_GROUP_CHANGE_ACTIONS: readonly CatalogGroupAction[] = ["ADD", "MOVE_TO_GROUP"];
