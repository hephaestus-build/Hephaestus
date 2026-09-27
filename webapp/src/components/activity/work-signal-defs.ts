import { CheckIcon, CircleSlashIcon, FileDiffIcon, PencilLineIcon } from "lucide-react";

import type { WorkItem } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/**
 * What an open pull request is waiting on, as far as the provider said. Only what asks for attention is a
 * signal: a pull request waiting for its first review, or whose checks passed, shows none.
 */
export type WorkSignal = "draft" | "changes-requested" | "approved" | "checks-failing";

export const WORK_SIGNAL_DEFS = {
	draft: {
		label: "Draft",
		icon: PencilLineIcon,
		badgeVariant: "secondary",
		description: "Not ready for review yet.",
	},
	"changes-requested": {
		label: "Changes requested",
		icon: FileDiffIcon,
		badgeVariant: "warning",
		description: "A reviewer asked for changes.",
	},
	approved: {
		label: "Approved",
		icon: CheckIcon,
		badgeVariant: "success",
		description: "Approved by the reviewers it needs.",
	},
	"checks-failing": {
		label: "Checks failing",
		icon: CircleSlashIcon,
		badgeVariant: "destructive",
		description: "A check on the latest commit failed.",
	},
} as const satisfies StatusDefs<WorkSignal>;

export function workSignals(
	work: Pick<WorkItem, "isDraft" | "reviewDecision" | "checks">,
): WorkSignal[] {
	return [
		...(work.isDraft ? (["draft"] as const) : []),
		...(work.reviewDecision === "CHANGES_REQUESTED" ? (["changes-requested"] as const) : []),
		...(work.reviewDecision === "APPROVED" ? (["approved"] as const) : []),
		...(work.checks === "FAILURE" ? (["checks-failing"] as const) : []),
	];
}
