import { HandIcon, HistoryIcon, ZapIcon } from "lucide-react";

import type { ObservationDetail } from "@/api/types.gen";

import type { StatusDefs } from "./status-def";

export type ObservationOrigin = ObservationDetail["origin"];

/**
 * What occasioned the measurement. A live observation is the ordinary case and a surface says
 * nothing for it; the other two were self-selected — asked for by hand, or made while catching up
 * on work that predates the connection — so reading either as a random draw from the work is the
 * mistake the badge prevents.
 */
export const OBSERVATION_ORIGIN_DEFS: StatusDefs<ObservationOrigin> = {
	LIVE: {
		label: "Live",
		icon: ZapIcon,
		badgeVariant: "outline",
		description: "Reviewed as the work arrived.",
	},
	MANUAL: {
		label: "Requested",
		icon: HandIcon,
		badgeVariant: "outline",
		description: "Someone asked for this review.",
	},
	BACKFILL: {
		label: "Backfilled",
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: "Reviewed while catching up on work that predates the connection.",
	},
};
