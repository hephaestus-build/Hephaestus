import {
	CircleCheckIcon,
	CircleDashedIcon,
	CircleSlashIcon,
	type LucideIcon,
	LoaderIcon,
} from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/**
 * Where one row of Get started stands for the reader, in the words of a task list. Each comes from the
 * server's facts, never from a press: a row is done only when the thing it names has happened.
 */
export type GetStartedStatus = "done" | "in-progress" | "to-do" | "cannot-start";

export const GET_STARTED_STATUS_DEFS: StatusDefs<GetStartedStatus> = {
	done: {
		label: "Done",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "This has happened. Nothing to do here.",
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
		description: "Your next step. The row says what to do.",
	},
	"cannot-start": {
		label: "Cannot start yet",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "It waits for a step above it. Take that step first.",
	},
};

/**
 * A chapter of the guided tour that this account finished in this browser. It is a mark, not a
 * status: another browser has no record of it, so it can only miss a chapter, never claim one.
 */
export const TOUR_CHAPTER_FINISHED: { label: string; icon: LucideIcon; description: string } = {
	label: "Finished",
	icon: CircleCheckIcon,
	description: "You finished this chapter of the tour in this browser.",
};
