import { Building2 } from "lucide-react";

import { cn } from "cn";
import type { ActivityPeople, ActivityPerson } from "@/api/types.gen";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { formatDate } from "@/lib/dates";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityPeriod } from "./activity-period";
import { STALE } from "./activity-tones";
import { ActivityAutomationList } from "./ActivityAutomationList";
import { ActivityHighlights, ActivityHighlightsSkeleton } from "./ActivityHighlights";
import {
	type ActivityPeopleState,
	ActivityPeopleTable,
	type PeopleOrder,
} from "./ActivityPeopleTable";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";
import { ActivityTeamPicker } from "./ActivityTeamPicker";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";

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
		<PageLayout className="space-y-8">
			<PageHeader
				icon={<Building2 />}
				title="Workspace activity"
				description={ready && coverageNote(ready)}
			/>
			<div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
				<ActivityTeamPicker teams={facets?.teams} value={team} onChange={onTeamChange} />
				<ActivityPeriodPicker
					period={period}
					onPeriodChange={onPeriodChange}
					updating={stale || (timeline.status === "ready" && timeline.stale)}
				/>
			</div>
			{people.status === "loading" && (
				<Section size="lg" title="Highlights">
					<ActivityHighlightsSkeleton />
				</Section>
			)}
			{ready && <Highlights people={ready} stale={stale} />}
			<Section
				size="lg"
				title="People"
				description={`Contributions are the ${pullRequests} a person opened and reviewed, and the issues they opened.`}
			>
				<ActivityPeopleTable
					state={people}
					providerType={providerType}
					order={order}
					onOrderChange={onOrderChange}
					repositories={facets?.repositories ?? []}
					repo={repo}
					onRepoChange={onRepoChange}
				/>
			</Section>
			{ready && ready.automation.length > 0 && (
				<Section
					size="lg"
					title="Automation"
					description="Bot accounts and accounts treated as automation. Their work is not counted for people."
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

/**
 * How far back the counts are complete: "History since 3 March 2024 for 12 of 14 repositories", or,
 * while the complete repositories hold no history yet, how many repositories are still incomplete.
 */
function coverageNote({ coverage }: ActivityPeople): string | undefined {
	const { since, completeRepositories, totalRepositories } = coverage;
	const incomplete = totalRepositories - completeRepositories;
	if (totalRepositories === 0 || (since === undefined && incomplete === 0)) {
		return undefined;
	}
	const repositories = totalRepositories === 1 ? "repository" : "repositories";
	if (since === undefined) {
		const which =
			completeRepositories === 0
				? `the ${totalRepositories} ${repositories}`
				: `${incomplete} of ${totalRepositories} ${repositories}`;
		return `The history of ${which} is not complete yet, so the counts can be low.`;
	}
	return `History since ${formatDate(since)} for ${completeRepositories} of ${totalRepositories} ${repositories}.`;
}

function Highlights({ people, stale }: { people: ActivityPeople; stale: boolean }) {
	const byId = new Map(people.people.map((person) => [person.person.id, person]));
	const resolve = (ids: readonly number[]): ActivityPerson[] =>
		ids.flatMap((id) => {
			const person = byId.get(id);
			return person ? [person] : [];
		});
	const firstContributors = resolve(people.highlights.firstContributors);
	const mostPeopleHelped = resolve(people.highlights.mostPeopleHelped);
	if (firstContributors.length === 0 && mostPeopleHelped.length === 0) {
		return null;
	}
	return (
		<Section size="lg" title="Highlights">
			<div aria-busy={stale || undefined} className={cn(stale && STALE)}>
				<ActivityHighlights
					firstContributors={firstContributors}
					mostPeopleHelped={mostPeopleHelped}
				/>
			</div>
		</Section>
	);
}
