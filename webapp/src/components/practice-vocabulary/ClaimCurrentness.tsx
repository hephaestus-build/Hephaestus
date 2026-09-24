import { CircleHelp, ClockAlert } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";

type Currentness = ReviewObservation["claimCurrentness"];

const NON_CURRENT = {
	STALE: {
		badge: "Historical observation",
		badgeVariant: "warning",
		Icon: ClockAlert,
		title: "This observation is no longer current",
		description:
			"The practice or the reviewed work changed after this observation. The earlier result remains in the record, but it does not describe the current state.",
	},
	UNVERIFIABLE: {
		badge: "Rules version unknown",
		badgeVariant: "outline",
		Icon: CircleHelp,
		title: "We can't tell which version of the practice this was judged against",
		description:
			"The record of which practice text the review read was not kept, so there is no way to say whether the practice has changed since. Treat it as you would any observation you have not checked.",
	},
} as const satisfies Record<
	Exclude<Currentness, "CURRENT">,
	{
		badge: string;
		badgeVariant: "warning" | "outline";
		Icon: typeof ClockAlert;
		title: string;
		description: string;
	}
>;

export function ClaimCurrentnessBadge({ currentness }: { currentness: Currentness }) {
	if (currentness === "CURRENT") {
		return null;
	}
	const config = NON_CURRENT[currentness];
	return <Badge variant={config.badgeVariant}>{config.badge}</Badge>;
}

export function ClaimCurrentnessAlert({ currentness }: { currentness: Currentness }) {
	if (currentness === "CURRENT") {
		return null;
	}
	const { Icon, title, description } = NON_CURRENT[currentness];
	return (
		<Alert variant="warning">
			<Icon />
			<AlertTitle>{title}</AlertTitle>
			<AlertDescription>{description}</AlertDescription>
		</Alert>
	);
}
