import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { Section } from "@/components/layout/Section";
import { DrawerBody } from "@/components/ui/drawer";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_CATEGORY_DEFS, type ActivityCategory } from "./activity-kind-defs";
import type { ActivityRange } from "./activity-range";
import { ActivityTrendChart } from "./ActivityTrendChart";
import { ActivityWorkLog, type ActivityWorkLogState, type WorkLogSubject } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";

export interface ActivityCategoryLevelProps {
	nested?: boolean;
	path: LevelPath;
	category: ActivityCategory;
	/** The range the level counts. */
	range: ActivityRange;
	/** The range and whose activity it is: "Last 30 days · Platform". */
	description: string;
	providerType: ProviderType;
	/** The overview of the level's owner, whose buckets the chart draws. */
	overview: ActivityOverviewState;
	/** The owner's timeline, filtered to the category's kinds. */
	workLog: ActivityWorkLogState;
	subject: WorkLogSubject;
}

/** One category of activity grown out of its tile: the same bars with a date axis, then its timeline. */
export function ActivityCategoryLevel({
	nested,
	path,
	category,
	range,
	description,
	providerType,
	overview,
	workLog,
	subject,
}: ActivityCategoryLevelProps) {
	const title = ACTIVITY_CATEGORY_DEFS[category].label(providerType);
	return (
		<>
			<LevelHeader nested={nested} path={path} current={title} description={description} />
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<ActivityTrendChart
					state={overview}
					category={category}
					range={range}
					providerType={providerType}
				/>
				<Section
					level={3}
					size="lg"
					title="Timeline"
					actions={
						workLog.status === "ready" && workLog.items.length > 0 ? (
							<CopyMarkdownButton onCopy={workLog.onCopy} />
						) : undefined
					}
				>
					<ActivityWorkLog state={workLog} providerType={providerType} subject={subject} />
				</Section>
			</DrawerBody>
		</>
	);
}
