import { ShieldAlert } from "lucide-react";

import { Badge } from "@/components/ui/badge";

/**
 * The words both audit viewers use for instance-admin elevation. One copy, so the sign-in trail and
 * the settings trail can never describe the same action differently.
 */
export const ELEVATION_LABEL = "Elevated";

export const ELEVATION_DESCRIPTION =
	"Instance-admin access to a workspace the actor is not a member of";

export interface ElevationBadgeProps {
	elevated: boolean;
}

/**
 * Renders nothing when the action was not elevated — badging every row would colour the baseline and
 * bury the rows that are different. Absence is not a claim of membership either: rows recorded before
 * elevation was tracked read as not elevated, and a system actor is a member of nothing.
 */
export function ElevationBadge({ elevated }: ElevationBadgeProps) {
	if (!elevated) return null;
	return (
		<Badge variant="warning" title={ELEVATION_DESCRIPTION}>
			<ShieldAlert aria-hidden />
			{ELEVATION_LABEL}
		</Badge>
	);
}
