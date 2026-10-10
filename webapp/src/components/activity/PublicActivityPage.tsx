import { Link } from "@tanstack/react-router";
import { Globe } from "lucide-react";

import type { ActivityCoverage } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

import { coverageNote } from "./activity-coverage";
import type { ActivityPeriod } from "./activity-period";
import {
	ActivityPeopleTable,
	type ActivityPeopleTableProps,
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
	repositories: ActivityPeopleTableProps["repositories"];
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
	return (
		<PageLayout className="max-w-4xl space-y-8">
			<PageHeader
				icon={<Globe />}
				title={workspaceName === undefined ? "Public activity" : `${workspaceName} activity`}
				description={
					<>
						This page shows public {pullRequests}, reviews and issues in public{" "}
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
			<div className="flex justify-end">
				<ActivityPeriodPicker period={period} onPeriodChange={onPeriodChange} updating={updating} />
			</div>
			<Section
				size="lg"
				title="People"
				description={
					<>
						Contributions are {pullRequests} opened and reviewed, plus issues opened.
						{note !== undefined && ` ${note}`}
					</>
				}
			>
				<ActivityPeopleTable
					state={people}
					providerType={providerType}
					order={order}
					onOrderChange={onOrderChange}
					repositories={repositories}
					repo={repo}
					onRepoChange={onRepoChange}
					personLink={providerProfileLink}
				/>
			</Section>
		</PageLayout>
	);
}
