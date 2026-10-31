import {
	CircleCheckIcon,
	CircleDashedIcon,
	CircleHelpIcon,
	LoaderIcon,
	TriangleAlertIcon,
} from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/** Where one fact of the setup summary stands, in the words of a task list. */
export type WorkspaceSetupStatus = "done" | "in-progress" | "to-do" | "incomplete" | "unknown";

export const WORKSPACE_SETUP_STATUS_DEFS: StatusDefs<WorkspaceSetupStatus> = {
	done: {
		label: "Done",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Members get what this row is about.",
	},
	"in-progress": {
		label: "In progress",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Hephaestus is working on it. Nothing to do now.",
	},
	"to-do": {
		label: "To do",
		icon: CircleDashedIcon,
		badgeVariant: "warning",
		description: "Not set up yet. Open the linked page to set it up.",
	},
	incomplete: {
		label: "Incomplete",
		icon: TriangleAlertIcon,
		badgeVariant: "warning",
		description: "Set up for some members or some work, but not all.",
	},
	unknown: {
		label: "Unknown",
		icon: CircleHelpIcon,
		badgeVariant: "outline",
		description: "We could not read this fact, so we cannot say whether it is set up.",
	},
};
