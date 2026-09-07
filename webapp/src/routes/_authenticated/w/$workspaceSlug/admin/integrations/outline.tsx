import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { listConnectionSyncJobsOptions } from "@/api/@tanstack/react-query.gen";
import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { OutlineCollectionsSection } from "@/components/admin/integrations/outline/OutlineCollectionsSection";
import { OutlineConnectCard } from "@/components/admin/integrations/outline/OutlineConnectCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { OutlineIcon } from "@/components/icons/brand";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useOutlineIntegration } from "@/hooks/use-outline-integration";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/outline")(
	{
		head: workspaceAdminHead("Outline"),
		remountDeps: ({ params }) => params.workspaceSlug,
		component: OutlineIntegrationPage,
	},
);

const JOBS_PAGE_SIZE = 10;

function OutlineIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const [jobsPage, setJobsPage] = useState(0);
	const livePushUnavailable = useLivePushUnavailable();
	const outline = useOutlineIntegration(slug);
	const connectionId = outline.connectionId;

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
		refetchInterval: syncPollInterval(outline.hasActiveJob, livePushUnavailable),
		placeholderData: (previousData) => previousData,
	});

	return (
		<PageLayout>
			<PageHeader
				icon={<OutlineIcon className="size-6" />}
				title="Outline"
				description="Mirror Outline collections so their documents reach practice reviews as context."
			/>

			{outline.isLoading && <Skeleton className="h-48 w-full" />}

			{outline.connectionsError && (
				<QueryErrorAlert
					error={outline.connectionsError}
					title="We couldn't load the Outline connection"
					onRetry={outline.retryConnections}
				/>
			)}

			{!outline.isLoading && !outline.connectionsError && (
				<>
					{outline.hasConnection && outline.statusError && (
						<QueryErrorAlert
							error={outline.statusError}
							title="We couldn't load Outline sync status"
							onRetry={outline.retryStatus}
						/>
					)}
					{outline.tokenStatusError && (
						<QueryErrorAlert
							error={outline.tokenStatusError}
							title="We couldn't verify the Outline token"
							onRetry={outline.retryTokenStatus}
						/>
					)}

					{outline.hasConnection && (
						<ConnectionStateNotice
							connectionState={outline.connectionState}
							credentialsUnreadableSince={outline.credentialsUnreadableSince}
							credentialRecovery="Replace it by disconnecting and connecting again"
							displayName="Outline"
						/>
					)}

					{outline.status && (
						<SyncStatusHeader label="Outline" {...outline.syncStatusHeaderProps} />
					)}

					{outline.hasConnection && (
						<Card>
							<CardHeader>
								<IntegrationCardHeading>Collection sync state</IntegrationCardHeading>
							</CardHeader>
							<CardContent>
								<SyncResourcesTable {...outline.syncResourcesProps} />
							</CardContent>
						</Card>
					)}

					<OutlineConnectCard key={connectionId ?? "new"} {...outline.connectCardProps} />

					{outline.collectionsProps && <OutlineCollectionsSection {...outline.collectionsProps} />}
				</>
			)}

			{connectionId != null && (
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
