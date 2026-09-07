import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { ExternalLinkIcon, WebhookIcon } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import {
	addRepositoryToMonitorMutation,
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogOptions,
	getRepositoriesToMonitorOptions,
	getWorkspaceOptions,
	listConnectionSyncJobsOptions,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesOptions,
	listConnectionSyncResourcesQueryKey,
	removeRepositoryToMonitorMutation,
	triggerSyncJobMutation,
	updateConnectionSyncJobMutation,
	updateTokenMutation,
} from "@/api/@tanstack/react-query.gen";
import { AdminRepositoriesSettings } from "@/components/admin/integrations/AdminRepositoriesSettings";
import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import {
	SCM_CLASS_KEYS,
	SyncResourcesTable,
} from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/scm")({
	head: workspaceAdminHead("Source control"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ScmIntegrationPage,
});

const JOBS_PAGE_SIZE = 10;

function ScmIntegrationPage() {
	const queryClient = useQueryClient();
	const { workspaceSlug: slug } = Route.useParams();
	const [jobsPage, setJobsPage] = useState(0);
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug: slug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	const workspaceData = workspaceQuery.data;

	const kind = workspaceData ? (workspaceData.kind === "GITLAB" ? "GITLAB" : "GITHUB") : undefined;
	const label = kind === "GITLAB" ? "GitLab" : kind === "GITHUB" ? "GitHub" : "Source control";
	const isAppInstallationWorkspace = kind === "GITHUB" && workspaceData?.installationId != null;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug: slug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	const entry = kind ? catalogQuery.data?.find((e) => e.kind === kind) : undefined;
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE";
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const statusQueryOptions = getConnectionSyncStatusOptions({
		path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
	});
	const statusQuery = useQuery({
		...statusQueryOptions,
		enabled: connectionId != null,
		refetchInterval: (query) =>
			syncPollInterval(query.state.data?.activeJob != null, livePushUnavailable),
	});
	const status = statusQuery.data;
	const hasActiveJob = status?.activeJob != null;

	const resourcesQueryOptions = listConnectionSyncResourcesOptions({
		path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
	});
	const {
		data: resources,
		isLoading: isResourcesLoading,
		isError: isResourcesError,
		error: resourcesError,
		refetch: refetchResources,
	} = useQuery({
		...resourcesQueryOptions,
		enabled: connectionId != null,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});

	const jobsQueryOptions = listConnectionSyncJobsOptions({
		path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
		query: { page: jobsPage, size: JOBS_PAGE_SIZE },
	});
	const {
		data: jobsPageData,
		isLoading: isJobsLoading,
		isError: isJobsError,
		error: jobsError,
		refetch: refetchJobs,
	} = useQuery({
		...jobsQueryOptions,
		enabled: connectionId != null,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
		placeholderData: (previousData) => previousData,
	});

	const repositoriesQueryOptions = getRepositoriesToMonitorOptions({
		path: { workspaceSlug: slug },
	});
	const {
		data: repositories,
		isLoading: isLoadingRepositories,
		error: repositoriesError,
		refetch: refetchRepositories,
	} = useQuery({
		...repositoriesQueryOptions,
	});

	const invalidateSyncState = () => {
		if (connectionId == null) return;
		void queryClient.invalidateQueries({
			queryKey: getConnectionSyncStatusQueryKey({
				path: { workspaceSlug: slug, connectionId },
			}),
		});
		void queryClient.invalidateQueries({
			queryKey: listConnectionSyncJobsQueryKey({
				path: { workspaceSlug: slug, connectionId },
			}),
		});
		void queryClient.invalidateQueries({
			queryKey: listConnectionSyncResourcesQueryKey({
				path: { workspaceSlug: slug, connectionId },
			}),
		});
	};

	const onRepositorySetChanged = () => {
		void queryClient.invalidateQueries({ queryKey: repositoriesQueryOptions.queryKey });
		invalidateSyncState();
	};

	const addRepository = useMutation({
		...addRepositoryToMonitorMutation(),
		onSuccess: onRepositorySetChanged,
		onError: (e) => {
			toast.error("Failed to add repository", { description: problemDetailOf(e) });
		},
	});
	const removeRepository = useMutation({
		...removeRepositoryToMonitorMutation(),
		onSuccess: onRepositorySetChanged,
		onError: (e) => {
			toast.error("Failed to stop monitoring repository", { description: problemDetailOf(e) });
		},
	});

	const replaceToken = useMutation({
		...updateTokenMutation(),
		gcTime: 0,
		onSuccess: async () => {
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: workspaceQueryOptions.queryKey }),
				queryClient.invalidateQueries({ queryKey: catalogQueryOptions.queryKey }),
			]);
			invalidateSyncState();
			toast.success("Personal access token replaced");
		},
	});

	const triggerSync = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: (job) => {
			invalidateSyncState();
			toast.success(job.type === "BACKFILL" ? "Backfill started" : "Sync started");
		},
		onError: (e) => {
			toast.error("Failed to start sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: () => {
			invalidateSyncState();
			toast.success("Cancelling — stopping after current step…");
		},
		onError: (e) => {
			toast.error("Failed to cancel sync", { description: problemDetailOf(e) });
		},
	});

	const activeJob = status?.activeJob;

	const pendingTriggerType = triggerSync.isPending ? triggerSync.variables.body.type : undefined;
	const triggeringType =
		pendingTriggerType === "RECONCILIATION" || pendingTriggerType === "BACKFILL"
			? pendingTriggerType
			: null;

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
					connectionState={entry.connectionState}
					credentialsUnreadableSince={entry.credentialsUnreadableSince}
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
				status={status}
				isLoading={workspaceQuery.isLoading || catalogQuery.isLoading || statusQuery.isLoading}
				error={workspaceQuery.error ?? catalogQuery.error ?? statusQuery.error}
				isConnectionActive={isConnectionActive}
				credentialsUnreadableSince={entry?.credentialsUnreadableSince}
				triggeringType={triggeringType}
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
				isCancelling={cancelJob.isPending}
				onRetry={() => {
					void workspaceQuery.refetch();
					void catalogQuery.refetch();
					void statusQuery.refetch();
				}}
				onSync={() => {
					if (connectionId == null) return;
					triggerSync.mutate({
						path: { workspaceSlug: slug, connectionId },
						body: { type: "RECONCILIATION" },
					});
				}}
				onBackfill={() => {
					if (connectionId == null) return;
					triggerSync.mutate({
						path: { workspaceSlug: slug, connectionId },
						body: { type: "BACKFILL" },
					});
				}}
				onCancel={() => {
					if (connectionId == null || activeJob == null) return;
					cancelJob.mutate({
						path: { workspaceSlug: slug, connectionId, jobId: activeJob.id },
						body: { cancelRequested: true },
					});
				}}
			/>

			{isConnectionActive && kind && !isAppInstallationWorkspace && (
				<WorkspaceScmTokenSettings
					providerLabel={label}
					isSaving={replaceToken.isPending}
					error={replaceToken.error}
					onSave={async (personalAccessToken) => {
						try {
							await replaceToken.mutateAsync({
								path: { workspaceSlug: slug },
								body: { personalAccessToken },
							});
							replaceToken.reset();
							return true;
						} catch {
							return false;
						}
					}}
				/>
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Repository sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable
							resources={resources ?? []}
							isLoading={isResourcesLoading}
							isError={isResourcesError}
							error={resourcesError}
							onRetry={() => void refetchResources()}
							resourceNoun="repository"
							resourceNounPlural="repositories"
							syncIntervalSeconds={status?.syncIntervalSeconds}
							expectedClassKeys={SCM_CLASS_KEYS}
						/>
					</CardContent>
				</Card>
			)}

			{isConnectionActive && !isAppInstallationWorkspace && (
				<AdminRepositoriesSettings
					repositories={(repositories ?? []).map((repo) => ({ nameWithOwner: repo }))}
					providerLabel={label}
					isLoading={isLoadingRepositories}
					error={repositoriesError}
					addRepositoryError={addRepository.error}
					isAddingRepository={addRepository.isPending}
					isRemovingRepository={removeRepository.isPending}
					onAddRepository={(nameWithOwner) => {
						addRepository.mutate({ path: { workspaceSlug: slug }, query: { nameWithOwner } });
					}}
					onRemoveRepository={(nameWithOwner) => {
						removeRepository.mutate({ path: { workspaceSlug: slug }, query: { nameWithOwner } });
					}}
					onRetry={() => void refetchRepositories()}
				/>
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
