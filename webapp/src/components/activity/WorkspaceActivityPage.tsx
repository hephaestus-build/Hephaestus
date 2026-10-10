import { Building2 } from "lucide-react";

import type { ActivityPeople, ActivityPerson } from "@/api/types.gen";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { formatDate } from "@/lib/dates";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityPeriod } from "./activity-period";
import { ActivityAutomationList } from "./ActivityAutomationList";
import { ActivityHighlights } from "./ActivityHighlights";
import {
	type ActivityPeopleState,
	ActivityPeopleTable,
	type PeopleOrder,
} from "./ActivityPeopleTable";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";
import { ActivityTeamPicker } from "./ActivityTeamPicker";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";

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
	timeline,
}: WorkspaceActivityPageProps) {
	const ready = people.status === "ready" ? people.people : undefined;
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	return (
		<PageLayout className="space-y-8">
			<PageHeader
				icon={<Building2 />}
				title="Workspace activity"
				description={ready && coverageNote(ready)}
			/>
			<div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
				<ActivityTeamPicker teams={ready?.teams} value={team} onChange={onTeamChange} />
				<ActivityPeriodPicker
					period={period}
					onPeriodChange={onPeriodChange}
					updating={
						(people.status === "ready" && people.stale) ||
						(timeline.status === "ready" && timeline.stale)
					}
				/>
			</div>
			{ready && <Highlights people={ready} />}
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
					<ActivityAutomationList automation={ready.automation} />
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

/** "History since 3 March 2024 for 12 of 14 repositories": how far back the counts are complete. */
function coverageNote({ coverage }: ActivityPeople): string | undefined {
	const { since, completeRepositories, totalRepositories } = coverage;
	if (since === undefined || totalRepositories === 0) {
		return undefined;
	}
	const repositories = totalRepositories === 1 ? "repository" : "repositories";
	return `History since ${formatDate(since)} for ${completeRepositories} of ${totalRepositories} ${repositories}.`;
}

function Highlights({ people }: { people: ActivityPeople }) {
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
			<ActivityHighlights
				firstContributors={firstContributors}
				mostPeopleHelped={mostPeopleHelped}
			/>
		</Section>
	);
}
