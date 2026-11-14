import { Badge } from "@/components/ui/badge";

import { currentnessCopy, type Freshness } from "./claim-currentness";

export function ClaimCurrentnessBadge({ freshness }: { freshness: Freshness }) {
	const copy = currentnessCopy(freshness);
	if (copy === undefined) {
		return null;
	}
	return <Badge variant={copy.badgeVariant}>{copy.badge}</Badge>;
}
