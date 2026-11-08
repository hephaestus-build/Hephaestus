import {
	CircleCheckIcon,
	CircleDashedIcon,
	CircleDollarSignIcon,
	CircleSlashIcon,
	LoaderIcon,
	PauseCircleIcon,
} from "lucide-react";

import type { ReviewBatch } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export const REVIEW_BATCH_STATUS_DEFS = {
	AWAITING_CONFIRMATION: {
		label: "Awaiting confirmation",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "An administrator must confirm this batch before reviews can start.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "The batch is requesting reviews for its remaining work.",
	},
	PAUSED: {
		label: "Paused",
		icon: PauseCircleIcon,
		badgeVariant: "outline",
		description: "The batch has paused and retains its remaining work.",
	},
	COMPLETED: {
		label: "Finished",
		icon: CircleCheckIcon,
		badgeVariant: "secondary",
		description: "The batch has finished requesting reviews for its selected work.",
	},
	CANCELLED: {
		label: "Stopped",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "An administrator stopped this batch.",
	},
	BUDGET_EXHAUSTED: {
		label: "Run limit reached",
		icon: CircleDollarSignIcon,
		badgeVariant: "outline",
		description:
			"This scheduled run ended at its spending limit. Later scheduled checks can start new runs.",
	},
} satisfies StatusDefs<ReviewBatch["status"]>;
