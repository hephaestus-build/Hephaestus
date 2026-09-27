import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";

import { ActivityTimeline, type ActivityTimelineProps } from "./ActivityTimeline";

export interface ActivityCategoryLevelProps extends ActivityTimelineProps {
	nested?: boolean;
	path: LevelPath;
	/** The category's title: "Reviews", "Merge requests". */
	title: string;
	/** Whose activity and over which range: "Reviews in Platform / Payments in the last 7 days." */
	description: string;
}

export function ActivityCategoryLevel({
	nested,
	path,
	title,
	description,
	...timeline
}: ActivityCategoryLevelProps) {
	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<DetailPath {...path} current={title} />
					<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
						{title}
					</DrawerTitle>
					<DrawerDescription className="max-w-2xl">{description}</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="pt-2">
				<ActivityTimeline {...timeline} />
			</DrawerBody>
		</>
	);
}
