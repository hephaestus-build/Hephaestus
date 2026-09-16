import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { listConnectionSyncJobsOptions } from "@/api/@tanstack/react-query.gen";
import { AdminSlackChannelsSettings } from "@/components/admin/integrations/AdminSlackChannelsSettings";
import { AdminSlackNotificationSettings } from "@/components/admin/integrations/AdminSlackNotificationSettings";
import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { SlackIcon } from "@/components/icons/brand";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useSlackIntegration } from "@/hooks/use-slack-integration";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/slack")({
	head: workspaceAdminHead("Slack"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: SlackIntegrationPage,
});

const JOBS_PAGE_SIZE = 10;

function SlackIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const [jobsPage, setJobsPage] = useState(0);
	const livePushUnavailable = useLivePushUnavailable();
	const slack = useSlackIntegration(slug);
	const { connectionId, hasConnection } = slack;
	const ready = !slack.isLoading && !slack.loadError;

	const {
		data: jobsPageData,
		isLoading: isJobsLoading,
		isError: isJobsError,
		error: jobsError,
		refetch: refetchJobs,
	} = useQuery({
		...listConnectionSyncJobsOptions({
			path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
			query: { page: jobsPage, size: JOBS_PAGE_SIZE },
		}),
		enabled: connectionId != null,
		refetchInterval: syncPollInterval(slack.hasActiveJob, livePushUnavailable),
		placeholderData: (previousData) => previousData,
	});

	return (
		<PageLayout>
			<PageHeader
				icon={<SlackIcon className="size-6" />}
				title="Slack"
				description="Connection, weekly digest, monitored channels and sync activity for this workspace's Slack app."
			/>

			{slack.isLoading && <Skeleton className="h-48 w-full" />}

			{slack.loadError && (
				<QueryErrorAlert
					error={slack.loadError}
					title="We couldn't load the Slack connection"
					onRetry={slack.retryLoad}
				/>
			)}

			{ready && slack.isStatusError && (
				<QueryErrorAlert
					error={slack.statusError}
					title="We couldn't load Slack sync status"
					onRetry={slack.retryStatus}
				/>
			)}

			{ready && hasConnection && (
				<ConnectionStateNotice
					connectionState={slack.connectionState}
					credentialsUnreadableSince={slack.credentialsUnreadableSince}
					credentialRecovery="Replace it by disconnecting and connecting again"
					displayName="Slack"
				/>
			)}

			{ready && slack.status && <SyncStatusHeader label="Slack" {...slack.syncStatusHeaderProps} />}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Channel sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable {...slack.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{ready && (
				<AdminSlackNotificationSettings
					key={slack.notificationSettingsKey}
					{...slack.notificationSettingsProps}
				/>
			)}

			{ready && <AdminSlackChannelsSettings {...slack.channelsSettingsProps} />}

			{hasConnection && (
				<JobHistoryCard
					jobs={jobsPageData?.content ?? []}
					isLoading={isJobsLoading}
					isError={isJobsError}
					error={jobsError}
					onRetry={() => void refetchJobs()}
					page={jobsPage}
					totalPages={jobsPageData?.totalPages ?? 1}
					onPageChange={setJobsPage}
				/>
			)}
		</PageLayout>
	);
}
