import type { CatalogEntryStatus } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";

import { curatedEntryCopy, isOrdinary } from "./curated-entry-state";

const TONE_VARIANT = { neutral: "outline", info: "secondary", attention: "warning" } as const;

export interface CuratedEntryBadgesProps {
	status: CatalogEntryStatus;
	kind: "practice" | "group";
}

export function CuratedEntryBadges({ status, kind }: CuratedEntryBadgesProps) {
	if (isOrdinary(status)) {
		return null;
	}
	const copy = curatedEntryCopy(status, kind);
	return (
		<>
			{!status.offered && <Badge variant="outline">Excluded</Badge>}
			{status.state !== "FROM_HEPHAESTUS" && (
				<Badge variant={TONE_VARIANT[copy.tone]}>{copy.label}</Badge>
			)}
		</>
	);
}
