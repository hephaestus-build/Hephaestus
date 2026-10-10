import { Building2 } from "lucide-react";

import { cn } from "cn";
import type { ActivityPeople } from "@/api/types.gen";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { coverageNote } from "./activity-coverage";
import type { ActivityPeriod } from "./activity-period";
import { STALE } from "./activity-tones";
import { ActivityAutomationList } from "./ActivityAutomationList";
import {
	type ActivityPeopleState,
	ActivityPeopleTable,
	type PeopleOrder,
} from "./ActivityPeopleTable";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";
import { ActivityTeamPicker } from "./ActivityTeamPicker";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";
import { personLevelLink } from "./people-links";

/** What the team and repository pickers offer: the same in every scope. */
export type ActivityFacets = Pick<ActivityPeople, "teams" | "repositories">;

export interface WorkspaceActivityPageProps {
	providerType: ProviderType;
	period: ActivityPeriod;
	onPeriodChange: (period: ActivityPeriod) => void;
	/** The team's slug; undefined is everyone. */
	team: string | undefined;
	onTeamChange: (team: string | undefined) => void;
	/** Repositories by full path; none is every repository. */
	repo: readonly string[];
	onRepoChange: (repo: string[]) => void;
	order: PeopleOrder;
	onOrderChange: (order: PeopleOrder) => void;
	people: ActivityPeopleState;
	/** Undefined until the first people arrive; the previous scope's while another loads. */
	facets: ActivityFacets | undefined;
	timeline: ActivityWorkLogState;
}

/**
 * Who contributed to the workspace, or to one team, in a period: everyone in one table that sorts
 * by any count, what is worth a thank-you, the automation apart from the people, and the timeline
 * of the work.
 */
export function WorkspaceActivityPage({
	providerType,
	period,
	onPeriodChange,
	team,
	onTeamChange,
	repo,
	onRepoChange,
	order,
	onOrderChange,
	people,
	facets,
	timeline,
}: WorkspaceActivityPageProps) {
	const ready = people.status === "ready" ? people.people : undefined;
	const stale = people.status === "ready" && people.stale;
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	return (
		<PageLayout className="max-w-4xl space-y-8">
			<PageHeader
				icon={<Building2 />}
				title="Workspace activity"
				description={ready && coverageNote(ready.coverage)}
			/>
			<div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
				<ActivityTeamPicker teams={facets?.teams} value={team} onChange={onTeamChange} />
				<ActivityPeriodPicker
					period={period}
					onPeriodChange={onPeriodChange}
					updating={stale || (timeline.status === "ready" && timeline.stale)}
				/>
			</div>
			<Section
				size="lg"
				title="People"
				description={`Contributions are ${pullRequests} opened and reviewed, plus issues opened.`}
			>
				<ActivityPeopleTable
					state={people}
					providerType={providerType}
					order={order}
					onOrderChange={onOrderChange}
					repositories={facets?.repositories ?? []}
					repo={repo}
					onRepoChange={onRepoChange}
					personLink={personLevelLink}
				/>
			</Section>
			{ready && ready.automation.length > 0 && (
				<Section
					size="lg"
					title="Automation"
					description="Bots and accounts treated as automation, counted apart from people."
				>
					<div aria-busy={stale || undefined} className={cn(stale && STALE)}>
						<ActivityAutomationList automation={ready.automation} />
					</div>
				</Section>
			)}
			<Section
				size="lg"
				title="Timeline"
				actions={
					timeline.status === "ready" && timeline.items.length > 0 ? (
						<CopyMarkdownButton onCopy={timeline.onCopy} />
					) : undefined
				}
			>
				<ActivityWorkLog
					state={timeline}
					providerType={providerType}
					subject={{ people: "several" }}
				/>
			</Section>
		</PageLayout>
	);
}
