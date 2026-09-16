import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { ExternalLinkIcon, WebhookIcon } from "lucide-react";
import { useState } from "react";

import { listConnectionSyncJobsOptions } from "@/api/@tanstack/react-query.gen";
import { AdminRepositoriesSettings } from "@/components/admin/integrations/AdminRepositoriesSettings";
import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { useScmIntegration } from "@/hooks/use-scm-integration";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/scm")({
	head: workspaceAdminHead("Source control"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ScmIntegrationPage,
});

const JOBS_PAGE_SIZE = 10;

function ScmIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const [jobsPage, setJobsPage] = useState(0);
	const livePushUnavailable = useLivePushUnavailable();
	const scm = useScmIntegration(slug);
	const {
		kind,
		label,
		connectionId,
		hasConnection,
		isConnectionActive,
		isAppInstallationWorkspace,
	} = scm;

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
		refetchInterval: syncPollInterval(scm.hasActiveJob, livePushUnavailable),
		placeholderData: (previousData) => previousData,
	});

	return (
		<PageLayout>
			<PageHeader
				icon={
					kind === "GITLAB" ? (
						<GitlabIcon className="size-6" />
					) : kind === "GITHUB" ? (
						<GithubIcon className="size-6" />
					) : (
						<WebhookIcon className="size-6" />
					)
				}
				title={label}
				description={`Connection health, repositories and sync activity for this workspace's ${label} connection.`}
			/>

			{hasConnection && (
				<ConnectionStateNotice
					connectionState={scm.connectionState}
					credentialsUnreadableSince={scm.credentialsUnreadableSince}
					credentialRecovery={
						isAppInstallationWorkspace
							? "Reconnect through the GitHub App installation"
							: isConnectionActive
								? "Replace it using the personal access token form below"
								: "Reconnect this source-control integration before replacing its token"
					}
					displayName={label}
				/>
			)}

			<SyncStatusHeader
				label={label}
				actions={
					isAppInstallationWorkspace && (
						<a
							href="https://github.com/settings/installations"
							target="_blank"
							rel="noreferrer"
							className={buttonVariants({ variant: "outline", size: "sm" })}
						>
							Manage installation on GitHub
							<ExternalLinkIcon className="size-3.5" />
						</a>
					)
				}
				{...scm.syncStatusHeaderProps}
			/>

			{isConnectionActive && kind && !isAppInstallationWorkspace && (
				<WorkspaceScmTokenSettings {...scm.tokenSettingsProps} />
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Repository sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable {...scm.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{isConnectionActive && !isAppInstallationWorkspace && (
				<AdminRepositoriesSettings {...scm.repositoriesSettingsProps} />
			)}

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
