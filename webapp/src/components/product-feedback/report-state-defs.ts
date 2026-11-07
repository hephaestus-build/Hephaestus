import { CircleAlert, CircleCheck, Clock, Inbox, PenLine, Send } from "lucide-react";

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
		label: "Waiting",
		icon: Clock,
		badgeVariant: "outline",
		description: "It waits a few minutes before it is filed, so you can still keep it private.",
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
