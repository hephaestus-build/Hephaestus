import type { ReactNode } from "react";

import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";

import { CATALOG_LEVEL_LABELS, type CatalogLevelKind } from "./catalog-search";

const DESCRIPTIONS: Record<CatalogLevelKind, string> = {
	"practice-new": "Define a practice for the instance catalog.",
	"practice-edit":
		"Saving updates the instance catalog. Existing workspace copies will not change.",
	"group-new": "Groups keep related practices together in the instance catalog.",
	"group-edit": "Saving updates the instance catalog. Existing workspace copies will not change.",
};

export interface CatalogFormLevelProps {
	kind: CatalogLevelKind;
	nested?: boolean;
	path: LevelPath;
	children: ReactNode;
}

/** An instance-catalog editor as a drawer level. Guarded — `webapp/DESIGN.md` § Guarded levels. */
export function CatalogFormLevel({ kind, nested, path, children }: CatalogFormLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={CATALOG_LEVEL_LABELS[kind]}
				description={DESCRIPTIONS[kind]}
			/>
			{children}
		</>
	);
}
