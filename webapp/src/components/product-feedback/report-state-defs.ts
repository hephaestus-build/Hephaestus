import {
	CircleAlert,
	CircleCheck,
	Clock,
	Globe,
	Inbox,
	Lock,
	LockOpen,
	PenLine,
	Send,
} from "lucide-react";

import type { MyProductFeedback } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/** Where one of the person's reports is, as Your product feedback badges it. */
export const REPORT_STATE_DEFS: StatusDefs<MyProductFeedback["state"]> = {
	SAVED_FOR_ADMINS: {
		label: "Saved for admins",
		icon: Inbox,
		badgeVariant: "secondary",
		description: "It is saved on this instance for its admins. It is not on GitHub.",
	},
	DRAFTING: {
		label: "Drafting",
		icon: PenLine,
		badgeVariant: "outline",
		description: "Hephaestus prepares the issue from your report.",
	},
	HELD: {
		label: "Public soon",
		icon: Clock,
		badgeVariant: "outline",
		description:
			"Its issue is drafted. It waits a few minutes before it becomes public, so you can still keep it private or edit it.",
	},
	FILING: {
		label: "Filing",
		icon: Send,
		badgeVariant: "outline",
		description: "Hephaestus is filing it on GitHub.",
	},
	FILED: {
		label: "On GitHub",
		icon: CircleCheck,
		badgeVariant: "success",
		description: "It is an issue on GitHub.",
	},
	STOPPED: {
		label: "Needs attention",
		icon: CircleAlert,
		badgeVariant: "warning",
		description:
			"We could not confirm that it reached GitHub. It is saved for the admins of this instance, who can try again.",
	},
};

/** Who can read a report on GitHub: now, or once it files. */
export type ReportVisibility = "PUBLIC" | "CAN_BECOME_PUBLIC" | "PRIVATE";

export const REPORT_VISIBILITY_DEFS: StatusDefs<ReportVisibility> = {
	PUBLIC: {
		label: "Public",
		icon: Globe,
		badgeVariant: "outline",
		description: "It is a public issue on GitHub. Anyone can read it.",
	},
	CAN_BECOME_PUBLIC: {
		label: "Can become public",
		icon: LockOpen,
		badgeVariant: "outline",
		description: "It can become a public issue on GitHub. A check can still keep it private.",
	},
	PRIVATE: {
		label: "Private",
		icon: Lock,
		badgeVariant: "secondary",
		description: "It is not public.",
	},
};

/** A report heads for a public issue only on the public route, and it is public once it is filed there. */
export function reportVisibility(
	report: Pick<MyProductFeedback, "state" | "route">,
): ReportVisibility {
	if (report.route !== "PUBLIC") {
		return "PRIVATE";
	}
	return report.state === "FILED" ? "PUBLIC" : "CAN_BECOME_PUBLIC";
}
