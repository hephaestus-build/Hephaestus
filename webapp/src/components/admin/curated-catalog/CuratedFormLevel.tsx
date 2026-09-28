import type { ReactNode } from "react";

import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";

import { CURATED_LEVEL_LABELS, type CuratedLevelKind } from "./curated-catalog-search";

const DESCRIPTIONS: Record<CuratedLevelKind, string> = {
	"practice-new": "Define a practice for the instance catalog.",
	"practice-edit":
		"Saving updates the instance catalog. Existing workspace copies will not change.",
	"group-new": "Groups keep related practices together in the instance catalog.",
	"group-edit": "Saving updates the instance catalog. Existing workspace copies will not change.",
};

export interface CuratedFormLevelProps {
	kind: CuratedLevelKind;
	nested?: boolean;
	/** Where the level sits, from the host's `levelPathAt`. */
	path: LevelPath;
	children: ReactNode;
}

/** An instance-catalog editor as a drawer level. Guarded — `webapp/AGENTS.md` § Guarded levels. */
export function CuratedFormLevel({ kind, nested, path, children }: CuratedFormLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={CURATED_LEVEL_LABELS[kind]}
				description={DESCRIPTIONS[kind]}
			/>
			{children}
		</>
	);
}
