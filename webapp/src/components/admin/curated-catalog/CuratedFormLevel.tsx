import type { ReactNode } from "react";

import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DrawerDescription, DrawerTitle } from "@/components/ui/drawer";

import type { CuratedLevelKind } from "./curated-catalog-search";

const TITLES: Record<CuratedLevelKind, { title: string; description: string }> = {
	"practice-new": {
		title: "Create practice",
		description: "Define a practice for the instance catalog.",
	},
	"practice-edit": {
		title: "Edit practice",
		description: "Saving updates the instance catalog. Existing workspace copies will not change.",
	},
	"group-new": {
		title: "Create group",
		description: "Groups keep related practices together in the instance catalog.",
	},
	"group-edit": {
		title: "Edit group",
		description: "Saving updates the instance catalog. Existing workspace copies will not change.",
	},
};

export interface CuratedFormLevelProps {
	kind: CuratedLevelKind;
	nested?: boolean;
	children: ReactNode;
}

/** An instance-catalog editor as a drawer level. Guarded — `webapp/AGENTS.md` § Guarded levels. */
export function CuratedFormLevel({ kind, nested, children }: CuratedFormLevelProps) {
	const { title, description } = TITLES[kind];
	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="min-w-0 flex-1 space-y-0.5">
					<DrawerTitle>{title}</DrawerTitle>
					<DrawerDescription>{description}</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			{children}
		</>
	);
}
