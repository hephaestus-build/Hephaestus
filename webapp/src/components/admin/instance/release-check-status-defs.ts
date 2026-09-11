import {
	BanIcon,
	CircleArrowUpIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleHelpIcon,
	TriangleAlertIcon,
} from "lucide-react";

import type { ReleaseStatus } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

export type ReleaseCheckStatus = ReleaseStatus["status"];

/** Only `CURRENT` reads green; every other value is a reason not to conclude "up to date". */
export const RELEASE_CHECK_STATUS_DEFS: StatusDefs<ReleaseCheckStatus> = {
	CURRENT: {
		label: "Up to date",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "No newer release was published when GitHub was last asked.",
	},
	UPDATE_AVAILABLE: {
		label: "Update available",
		icon: CircleArrowUpIcon,
		badgeVariant: "warning",
		description: "A newer release is published. Upgrading stays your decision.",
	},
	FAILED: {
		label: "Check failed",
		icon: TriangleAlertIcon,
		badgeVariant: "destructive",
		description: "The last check did not complete.",
	},
	NEVER_CHECKED: {
		label: "Not checked yet",
		icon: CircleHelpIcon,
		badgeVariant: "secondary",
		description: "No check has completed since the server started.",
	},
	NOT_APPLICABLE: {
		label: "Not a release",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description:
			"This server runs a commit or a development build, so there is no release to compare with.",
	},
	DISABLED: {
		label: "Checks disabled",
		icon: BanIcon,
		badgeVariant: "outline",
		description: "Outbound release checks are switched off.",
	},
};

export type ReleaseCheckFailure = NonNullable<ReleaseStatus["failure"]>;

/** Each label reads as the start of a sentence that continues with when it happened. */
export const RELEASE_CHECK_FAILURE_LABELS: Record<ReleaseCheckFailure, string> = {
	RATE_LIMITED: "GitHub rate-limited the request",
	UNAVAILABLE: "GitHub could not be reached",
	MALFORMED: "GitHub did not answer with a release",
};
