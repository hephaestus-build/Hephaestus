import { Activity, UserRoundXIcon } from "lucide-react";
import type { ReactElement } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
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
import type { ActivityPeriod } from "./activity-period";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";
import type { ActivityWorkLogState } from "./ActivityWorkLog";
import { type OpenWorkReviewNow, OpenWorkSections, type OpenWorkState } from "./OpenWorkSections";
import { PersonActivitySections } from "./PersonActivitySections";

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
	period: ActivityPeriod;
	onPeriodChange: (period: ActivityPeriod) => void;
	openWork: OpenWorkState;
	overview: ActivityOverviewState;
	timeline: ActivityWorkLogState;
	/** Absent where the workspace reviews no practices, or the reader may not ask on your behalf. */
	reviewNow?: OpenWorkReviewNow;
}

/**
 * Your own activity, action before history: what needs you and what is assigned to you, then what
 * the period adds up to, by kind of work and by repository, then the timeline of the work you did.
 */
export function ActivityPage({
	providerType,
	account,
	period,
	onPeriodChange,
	openWork,
	overview,
	timeline,
	reviewNow,
}: ActivityPageProps) {
	const header = <PageHeader icon={<Activity />} title="Activity" />;
	if (account.status === "error") {
		return (
			<PageLayout>
				{header}
				<QueryErrorAlert
					error={account.error}
					title="We could not load your membership in this workspace"
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
							{`Activity shows the work you do with your ${getProviderTerms(providerType).displayName} account. Connect it to see your activity here.`}
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
			<OpenWorkSections state={openWork} providerType={providerType} reviewNow={reviewNow} />
			<PersonActivitySections
				level={2}
				period={period}
				periodActions={
					<ActivityPeriodPicker
						period={period}
						onPeriodChange={onPeriodChange}
						updating={
							(overview.status === "ready" && overview.stale) ||
							(timeline.status === "ready" && timeline.stale)
						}
					/>
				}
				overview={overview}
				workLog={timeline}
				providerType={providerType}
				subject={{ people: "one", login }}
			/>
		</PageLayout>
	);
}
