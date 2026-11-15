import { CalendarClockIcon, HandIcon, HistoryIcon, ZapIcon } from "lucide-react";

import type { ReviewRef } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type ReviewOrigin = ReviewRef["origin"];

export const REVIEW_ORIGIN_DEFS = {
	EVENT: {
		label: "Event",
		icon: ZapIcon,
		badgeVariant: "outline",
		description: "Reviewed when something happened on the work.",
	},
	REQUEST: {
		label: "Requested",
		icon: HandIcon,
		badgeVariant: "outline",
		description: "Someone asked for this review.",
	},
	SCHEDULE: {
		label: "Scheduled",
		icon: CalendarClockIcon,
		badgeVariant: "outline",
		description: "A review schedule started this review.",
	},
	BACKFILL: {
		label: "Backfilled",
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: "Reviewed while catching up on work that predates the connection.",
	},
} satisfies StatusDefs<string>;
