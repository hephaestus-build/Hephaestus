import type { ReactNode } from "react";

import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DrawerDescription, DrawerTitle } from "@/components/ui/drawer";

export interface PracticeFormLevelProps {
	creating: boolean;
	nested?: boolean;
	children: ReactNode;
}

/** The practice editor as a drawer level. Guarded — `webapp/AGENTS.md` § Guarded levels. */
export function PracticeFormLevel({ creating, nested, children }: PracticeFormLevelProps) {
	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="min-w-0 flex-1 space-y-0.5">
					<DrawerTitle>{creating ? "Create practice" : "Edit practice"}</DrawerTitle>
					<DrawerDescription>
						{creating
							? "Define a way of working and choose how it is reviewed."
							: "Update this practice's review rules and developer guidance."}
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			{children}
		</>
	);
}
