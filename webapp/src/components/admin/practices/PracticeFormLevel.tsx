import type { ReactNode } from "react";

import { PRACTICE_SETUP_LEVEL_LABELS } from "@/components/admin/practices/practice-search";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";

export interface PracticeFormLevelProps {
	creating: boolean;
	nested?: boolean;
	/** Where the level sits, from the host's `levelPathAt`. */
	path: LevelPath;
	children: ReactNode;
}

/** The practice editor as a drawer level. Guarded — `webapp/AGENTS.md` § Guarded levels. */
export function PracticeFormLevel({ creating, nested, path, children }: PracticeFormLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={PRACTICE_SETUP_LEVEL_LABELS[creating ? "practice-new" : "practice-edit"]}
				description={
					creating
						? "Define a way of working and choose how it is reviewed."
						: "Update this practice's review rules and developer guidance."
				}
			/>
			{children}
		</>
	);
}
