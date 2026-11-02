import { CircleAlert, CircleCheck, Clock, Mail, PenLine, Send } from "lucide-react";

import type { MyProductFeedback } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/** Where one of the person's reports is, as Your product feedback badges it. */
export const REPORT_STATE_DEFS: StatusDefs<MyProductFeedback["state"]> = {
	SENT_TO_ADMINS: {
		label: "With the admins",
		icon: Mail,
		badgeVariant: "secondary",
		description: "The admins of this instance have it. It is not on GitHub.",
	},
	DRAFTING: {
		label: "Drafting",
		icon: PenLine,
		badgeVariant: "outline",
		description: "An AI model drafts the issue from your report.",
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
		label: "Not filed",
		icon: CircleAlert,
		badgeVariant: "warning",
		description:
			"We could not file it on GitHub. The admins of this instance have it and can try again.",
	},
};
