import { Activity, UserRoundXIcon } from "lucide-react";
import type { ReactElement } from "react";

import type { OpenWork } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ActivityTiles } from "./ActivityTiles";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";
import { OpenWorkSections } from "./OpenWorkSections";
import { RangeControls } from "./RangeControls";

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
	overview: ActivityOverviewState;
	timeline: ActivityWorkLogState;
}

/**
 * Your own activity, action before history: what needs you and what is assigned to you, then what
 * the range adds up to, then the timeline of the work you did.
 */
export function ActivityPage({
	providerType,
	account,
	range,
	onRangeChange,
	openWork,
	overview,
	timeline,
}: ActivityPageProps) {
	const header = <PageHeader icon={<Activity />} title="Activity" />;
	if (account.status === "error") {
		return (
			<PageLayout>
				{header}
				<QueryErrorAlert
					error={account.error}
					title="Couldn't load your membership in this workspace"
					onRetry={account.onRetry}
				/>
			</PageLayout>
		);
	}
	if (account.status === "none") {
		const { settingsLink } = account;
		return (
			<PageLayout>
				{header}
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<UserRoundXIcon />
						</EmptyMedia>
						<EmptyTitle role="heading" aria-level={2}>
							No connected account
						</EmptyTitle>
						<EmptyDescription>
							{`Activity follows the ${getProviderTerms(providerType).displayName} account this workspace knows you by.`}
						</EmptyDescription>
					</EmptyHeader>
					{settingsLink && <EmptyContent>{settingsLink}</EmptyContent>}
				</Empty>
			</PageLayout>
		);
	}
	const login = account.status === "ready" ? account.login : undefined;
	return (
		<PageLayout className="space-y-8">
			{header}
			<OpenWorkSections
				state={openWork}
				providerType={providerType}
				perspective="self"
				login={login}
			/>
			<Section
				size="lg"
				title={ACTIVITY_RANGE_DEFS[range].label}
				actions={
					<RangeControls
						range={range}
						onRangeChange={onRangeChange}
						updating={
							(overview.status === "ready" && overview.stale) ||
							(timeline.status === "ready" && timeline.stale)
						}
					/>
				}
			>
				<ActivityTiles state={overview} providerType={providerType} />
			</Section>
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
					subject={{ people: "one", login }}
				/>
			</Section>
		</PageLayout>
	);
}
