import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";

import { AllPracticesTable, type AllPracticesTableProps } from "./AllPracticesTable";

export interface AllPracticesLevelProps extends AllPracticesTableProps {
	/** True when a level sits under this one; the practice profile opens it from the page. */
	nested?: boolean;
	/**
	 * Where the level sits, from the drawer. Its crumb and its title read the same: this level is
	 * its own kind.
	 */
	path?: LevelPath;
}

/**
 * Every practice group as a level over the practice profile: the "All practice groups" table under its
 * own heading. A row opens its group as the next level, so dismissing the group returns here.
 */
export function AllPracticesLevel({ nested, path, ...table }: AllPracticesLevelProps) {
	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<DetailPath {...path} current="All practice groups" />
					<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
						All practice groups
					</DrawerTitle>
					<DrawerDescription className="max-w-2xl">
						Every practice this workspace reviews, in its group. What needs you is named; the rest
						is counted. Open a group for the work behind it.
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="pt-2">
				<AllPracticesTable {...table} />
			</DrawerBody>
		</>
	);
}
