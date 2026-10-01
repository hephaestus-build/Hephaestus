import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { DrawerBody } from "@/components/ui/drawer";

import { AllPracticeGroupsTable, type AllPracticeGroupsTableProps } from "./AllPracticeGroupsTable";
import { ALL_PRACTICE_GROUPS } from "./practice-profile-search";

export interface AllPracticeGroupsLevelProps extends AllPracticeGroupsTableProps {
	/** True when a level sits under this one; the practice profile opens it from the page. */
	nested?: boolean;
	/**
	 * Where the level sits, from the drawer. Its crumb and its title read the same: this level is
	 * its own kind.
	 */
	path: LevelPath;
}

/**
 * Every practice group as a level over the practice profile: the "All practice groups" table under its
 * own heading. A row opens its group as the next level, so dismissing the group returns here.
 */
export function AllPracticeGroupsLevel({ nested, path, ...table }: AllPracticeGroupsLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={ALL_PRACTICE_GROUPS}
				description={
					<p className="max-w-2xl">
						Every practice this workspace reviews, in its group. What needs you is named; the rest
						is counted. Open a group for the work behind it.
					</p>
				}
			/>
			<DrawerBody className="pt-2">
				<AllPracticeGroupsTable {...table} />
			</DrawerBody>
		</>
	);
}
