import { FolderGit2, PencilLine } from "lucide-react";

import type { PracticeEditedIn } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/**
 * Where a practice's definition and group are changed (ADR 0118). Practice setup shows the badge
 * only for a practice edited in the practice repository: on every other row it would say the same
 * thing, which is no information.
 */
export const PRACTICE_EDITED_IN_DEFS: StatusDefs<PracticeEditedIn> = {
	HEPHAESTUS: {
		label: "Edited in Hephaestus",
		icon: PencilLine,
		badgeVariant: "outline",
		description: "Change the practice in Practice setup.",
	},
	REPOSITORY: {
		label: "Edited in the practice repository",
		icon: FolderGit2,
		badgeVariant: "secondary",
		description:
			"The practice’s folder in the practice repository holds its definition and group. Only its feedback mode and position change here.",
	},
};
