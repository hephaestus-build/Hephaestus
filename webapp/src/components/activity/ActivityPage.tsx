import { Activity, UserRoundXIcon } from "lucide-react";
import type { ReactElement } from "react";

import type { ActivitySummary, OpenWork } from "@/api/types.gen";
import { FilterToggle } from "@/components/common/FilterToggle";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

import { ACTIVITY_RANGE_DEFS, ACTIVITY_RANGE_OPTIONS, type ActivityRange } from "./activity-range";
import { ActivityPageLayout } from "./ActivityPageLayout";
import { ActivitySummaryList } from "./ActivitySummaryList";
import { ActivityTimeline, type ActivityTimelineState } from "./ActivityTimeline";
import { OpenWorkSections } from "./OpenWorkSections";

/** Whose activity the page reads: the account's login in this workspace, once the membership says. */
export type ActivityAccount =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	/**
	 * The workspace knows no provider account for this person. `settingsLink` leads to where one is
	 * connected, for a reader who can connect it.
	 */
	| { status: "none"; settingsLink?: ReactElement }
	| { status: "ready"; login: string };

export interface ActivityPageProps {
	providerType: ProviderType;
	account: ActivityAccount;
	range: ActivityRange;
	onRangeChange: (range: ActivityRange) => void;
	openWork: PanelState<{ openWork: OpenWork }>;
	summary: PanelState<{ summary: ActivitySummary }>;
	timeline: ActivityTimelineState;
}

/** Your own activity: what waits on you, what you have open, what the range adds up to, and what you did. */
export function ActivityPage({
	providerType,
	account,
	range,
	onRangeChange,
	openWork,
	summary,
	timeline,
}: ActivityPageProps) {
	const { label, inSentence } = ACTIVITY_RANGE_DEFS[range];
	const header = (
		<PageHeader
			icon={<Activity />}
			title="Activity"
			description="What is waiting on you, what you have open and what you did lately."
		/>
	);
	if (account.status === "error") {
		return (
			<ActivityPageLayout>
				{header}
				<QueryErrorAlert
					error={account.error}
					title="Couldn't load your membership in this workspace"
					onRetry={account.onRetry}
				/>
			</ActivityPageLayout>
		);
	}
	if (account.status === "none") {
		const { settingsLink } = account;
		return (
			<ActivityPageLayout>
				{header}
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<UserRoundXIcon />
						</EmptyMedia>
						<EmptyTitle role="heading" aria-level={2}>
							No connected account in this workspace
						</EmptyTitle>
						<EmptyDescription>
							{`Activity follows the ${getProviderTerms(providerType).displayName} account this workspace knows you by, and none is connected here.`}
						</EmptyDescription>
					</EmptyHeader>
					{settingsLink && <EmptyContent>{settingsLink}</EmptyContent>}
				</Empty>
			</ActivityPageLayout>
		);
	}
	return (
		<ActivityPageLayout>
			{header}
			<OpenWorkSections
				state={openWork}
				providerType={providerType}
				perspective="self"
				login={account.status === "ready" ? account.login : undefined}
			/>
			<Section
				size="lg"
				title="Summary"
				description="Counts of what you did. Open a row for the activity behind it."
				actions={
					<FilterToggle
						label="Time range"
						options={ACTIVITY_RANGE_OPTIONS}
						value={range}
						onChange={onRangeChange}
					/>
				}
			>
				<ActivitySummaryList state={summary} providerType={providerType} range={range} />
			</Section>
			<Section size="lg" title="Recent activity" description={label}>
				<ActivityTimeline
					state={timeline}
					providerType={providerType}
					people="one"
					empty={{
						title: `Nothing in ${inSentence}`,
						description: `Your ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType)}, reviews, issues and comments show up here.`,
					}}
				/>
			</Section>
		</ActivityPageLayout>
	);
}
