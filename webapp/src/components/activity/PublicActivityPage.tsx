import { Link } from "@tanstack/react-router";
import { Globe } from "lucide-react";

import type { ActivityCoverage, ActivityRepository } from "@/api/types.gen";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { InlineLink } from "@/components/common/InlineLink";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

import { coverageNote } from "./activity-coverage";
import { contributionsNote } from "./activity-people-rows";
import type { ActivityPeriod } from "./activity-period";
import {
	ActivityPeopleTable,
	type PeopleOrder,
	type PeopleTableState,
} from "./ActivityPeopleTable";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";
import { providerProfileLink } from "./people-links";

export interface PublicActivityPageProps {
	/** Undefined while the page could not read the workspace at all. */
	workspaceName: string | undefined;
	providerType: ProviderType;
	/** Who reads the page decides how a person on it hides themselves. */
	viewer: { status: "signed-out"; onSignIn: () => void } | { status: "signed-in" };
	period: ActivityPeriod;
	onPeriodChange: (period: ActivityPeriod) => void;
	order: PeopleOrder;
	onOrderChange: (order: PeopleOrder) => void;
	repo: readonly string[];
	onRepoChange: (repo: string[]) => void;
	/** The public repositories to pick from. */
	repositories: readonly Pick<ActivityRepository, "key">[];
	coverage: ActivityCoverage | undefined;
	people: PeopleTableState;
	/** Another period is loading, and the page still shows the previous one. */
	updating: boolean;
}

/**
 * What anyone can see of a workspace that publishes its activity: who contributed to its public
 * repositories in a period, in the table that members see, with only what the public page carries.
 */
export function PublicActivityPage({
	workspaceName,
	providerType,
	viewer,
	period,
	onPeriodChange,
	order,
	onOrderChange,
	repo,
	onRepoChange,
	repositories,
	coverage,
	people,
	updating,
}: PublicActivityPageProps) {
	const terms = getProviderTerms(providerType);
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	const note = coverage && coverageNote(coverage);
	// A repository the URL names but the workspace does not list stays in the picker, so it can be cleared.
	const repositoryKeys = [...new Set([...repositories.map(({ key }) => key), ...repo])];
	return (
		<PageLayout className="max-w-4xl space-y-8">
			<PageHeader
				icon={<Globe />}
				title={workspaceName === undefined ? "Public activity" : `${workspaceName} activity`}
				description={
					<>
						This page shows {pullRequests}, reviews and issues in public{" "}
						{terms.repositories.toLowerCase()}.{" "}
						{viewer.status === "signed-out" ? (
							<>
								On this page?{" "}
								<InlineLink onClick={viewer.onSignIn} className="underline">
									Sign in
								</InlineLink>{" "}
								to hide yourself.
							</>
						) : (
							<>
								On this page? Hide yourself in{" "}
								<InlineLink render={<Link to="/settings" />} className="underline">
									User settings
								</InlineLink>
								.
							</>
						)}
					</>
				}
			/>
			<div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
				<FilterToolbar hasFilter={repo.length > 0} onReset={() => onRepoChange([])}>
					{repositoryKeys.length > 1 && (
						<FacetMultiSelect
							title="Repository"
							options={repositoryKeys.map((key) => ({ value: key, label: key }))}
							selected={repo}
							onChange={onRepoChange}
						/>
					)}
				</FilterToolbar>
				<ActivityPeriodPicker period={period} onPeriodChange={onPeriodChange} updating={updating} />
			</div>
			<Section
				size="lg"
				title="Activity by person"
				description={
					<>
						{contributionsNote(providerType)}
						{note !== undefined && ` ${note}`}
					</>
				}
			>
				<ActivityPeopleTable
					state={people}
					providerType={providerType}
					order={order}
					onOrderChange={onOrderChange}
					repo={repo}
					personLink={providerProfileLink}
				/>
			</Section>
		</PageLayout>
	);
}
