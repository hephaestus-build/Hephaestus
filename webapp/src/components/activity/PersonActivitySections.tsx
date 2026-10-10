import type { ReactElement } from "react";

import { Section } from "@/components/layout/Section";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityOverviewState } from "./activity-buckets";
import { type ActivityPeriod, periodLabel } from "./activity-period";
import { ActivityRepositoryTable } from "./ActivityRepositoryTable";
import { ActivityTiles } from "./ActivityTiles";
import { ActivityWorkLog, type ActivityWorkLogState, type WorkLogSubject } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";

export interface PersonActivitySectionsProps {
	/** The heading level of the sections: 2 on a page, 3 in a drawer level under its title. */
	level: 2 | 3;
	period: ActivityPeriod;
	/** Beside the period's title: the controls that pick it, on a page that has them. */
	periodActions?: ReactElement;
	overview: ActivityOverviewState;
	workLog: ActivityWorkLogState;
	providerType: ProviderType;
	subject: WorkLogSubject;
}

/**
 * One person's activity in a period: a tile for each kind of work with its weekly bars, the counts
 * in each repository, and the timeline of the work itself.
 */
export function PersonActivitySections({
	level,
	period,
	periodActions,
	overview,
	workLog,
	providerType,
	subject,
}: PersonActivitySectionsProps) {
	const repositories = overview.status === "ready" ? overview.overview.repositories : [];
	return (
		<>
			<Section level={level} size="lg" title={periodLabel(period)} actions={periodActions}>
				<ActivityTiles state={overview} providerType={providerType} />
			</Section>
			{repositories.length > 1 && (
				<Section level={level} size="lg" title="Repositories">
					<ActivityRepositoryTable repositories={repositories} providerType={providerType} />
				</Section>
			)}
			<Section
				level={level}
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
		</>
	);
}
