import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogOptions,
	getWorkspaceOptions,
	listConnectionSyncJobsOptions,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesOptions,
	listConnectionSyncResourcesQueryKey,
	listSlackChannelCandidatesOptions,
	listSlackChannelConsentEventsQueryKey,
	listSlackChannelsOptions,
	registerSlackChannelMutation,
	triggerSyncJobMutation,
	updateConnectionSyncJobMutation,
	updateSlackChannelConsentMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	AdminSlackChannelsSettings,
	type SlackConsentState,
} from "@/components/admin/integrations/AdminSlackChannelsSettings";
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
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/slack")({
	head: workspaceAdminHead("Slack"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: SlackIntegrationPage,
});

const JOBS_PAGE_SIZE = 10;

function SlackIntegrationPage() {
	const queryClient = useQueryClient();
	const { workspaceSlug: slug } = Route.useParams();
	const [jobsPage, setJobsPage] = useState(0);
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug: slug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	const workspaceData = workspaceQuery.data;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug: slug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	const catalog = catalogQuery.data;
	const entry = catalog?.find((e) => e.kind === "SLACK");
	const hasConnection = entry?.connected === true;
	const isConnectionActive =
		entry?.connectionState === "ACTIVE" && workspaceData?.hasSlackToken === true;
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const statusQuery = useQuery({
		...getConnectionSyncStatusOptions({
			path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
		}),
		enabled: connectionId != null,
		refetchInterval: (query) =>
			syncPollInterval(query.state.data?.activeJob != null, livePushUnavailable),
	});
	const status = statusQuery.data;
	const hasActiveJob = status?.activeJob != null;

	const {
		data: resources,
		isLoading: isResourcesLoading,
		isError: isResourcesError,
		error: resourcesError,
		refetch: refetchResources,
	} = useQuery({
		...listConnectionSyncResourcesOptions({
			path: { workspaceSlug: slug, connectionId: connectionId ?? -1 },
		}),
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

	const slackChannelsQueryOptions = listSlackChannelsOptions({ path: { workspaceSlug: slug } });
	const {
		data: slackChannels,
		isLoading: isLoadingSlackChannels,
		isError: isSlackChannelsError,
		refetch: refetchSlackChannels,
	} = useQuery({
		...slackChannelsQueryOptions,
		enabled: workspaceData?.hasSlackToken === true,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});

	const slackChannelCandidatesQueryOptions = listSlackChannelCandidatesOptions({
		path: { workspaceSlug: slug },
	});
	const {
		data: slackChannelCandidates,
		isLoading: isLoadingSlackChannelCandidates,
		isError: isSlackChannelCandidatesError,
		refetch: refetchSlackChannelCandidates,
	} = useQuery({
		...slackChannelCandidatesQueryOptions,
		enabled: workspaceData?.hasSlackToken === true,
	});

	const invalidateSlackChannels = () => {
		void queryClient.invalidateQueries({ queryKey: slackChannelsQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: slackChannelCandidatesQueryOptions.queryKey });
		if (connectionId != null) {
			void queryClient.invalidateQueries({
				queryKey: listConnectionSyncResourcesQueryKey({
					path: { workspaceSlug: slug, connectionId },
				}),
			});
		}
	};

	const registerSlackChannel = useMutation({
		...registerSlackChannelMutation(),
		onSuccess: () => {
			toast.success("Channel added");
			invalidateSlackChannels();
		},
		onError: (e) => {
			toast.error("Failed to add channel", { description: problemDetailOf(e) });
		},
	});

	const updateSlackChannelConsent = useMutation({
		...updateSlackChannelConsentMutation(),
		onSuccess: (_data, variables) => {
			if (variables.body.consentState === "REVOKED") {
				toast.success("Channel removed and its data erased");
			} else {
				toast.success("Channel updated");
			}
			invalidateSlackChannels();
			void queryClient.invalidateQueries({
				queryKey: listSlackChannelConsentEventsQueryKey({
					path: { workspaceSlug: slug, slackChannelId: variables.path.slackChannelId },
				}),
			});
		},
		onError: (e, variables) => {
			if (variables.body.consentState === "REVOKED") {
				toast.error("Failed to remove channel", { description: problemDetailOf(e) });
			} else {
				toast.error("Failed to update channel", { description: problemDetailOf(e) });
			}
		},
	});

	const triggerSync = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: () => {
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
			toast.success("Sync started");
		},
		onError: (e) => {
			toast.error("Failed to start sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: () => {
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
			toast.success("Cancelling — stopping after current channel…");
		},
		onError: (e) => {
			toast.error("Failed to cancel sync", { description: problemDetailOf(e) });
		},
	});

	const routeLoading = workspaceQuery.isLoading || catalogQuery.isLoading;
	const routeError = workspaceQuery.error ?? catalogQuery.error;

	return (
		<PageLayout>
			<PageHeader
				icon={<SlackIcon className="size-6" />}
				title="Slack"
				description="Connection, weekly digest, monitored channels and sync activity for this workspace's Slack app."
			/>

			{routeLoading && <Skeleton className="h-48 w-full" />}

			{routeError && (
				<QueryErrorAlert
					error={routeError}
					title="We couldn't load the Slack connection"
					onRetry={() => {
						void workspaceQuery.refetch();
						void catalogQuery.refetch();
						void statusQuery.refetch();
					}}
				/>
			)}

			{!routeLoading && !routeError && statusQuery.isError && (
				<QueryErrorAlert
					error={statusQuery.error}
					title="We couldn't load Slack sync status"
					onRetry={() => void statusQuery.refetch()}
				/>
			)}

			{!routeLoading && !routeError && hasConnection && (
				<ConnectionStateNotice
					connectionState={entry.connectionState}
					credentialsUnreadableSince={entry.credentialsUnreadableSince}
					credentialRecovery="Replace it by disconnecting and connecting again"
					displayName="Slack"
				/>
			)}

			{!routeLoading && !routeError && status && (
				<SyncStatusHeader
					label="Slack"
					status={status}
					isConnectionActive={isConnectionActive}
					credentialsUnreadableSince={entry?.credentialsUnreadableSince}
					triggeringType={triggerSync.isPending ? "RECONCILIATION" : null}
					isCancelling={cancelJob.isPending}
					onRetry={() => void statusQuery.refetch()}
					onSync={() => {
						if (connectionId == null) return;
						triggerSync.mutate({
							path: { workspaceSlug: slug, connectionId },
							body: { type: "RECONCILIATION" },
						});
					}}
					onCancel={() => {
						const jobId = status.activeJob?.id;
						if (connectionId == null || jobId == null) return;
						cancelJob.mutate({
							path: { workspaceSlug: slug, connectionId, jobId },
							body: { cancelRequested: true },
						});
					}}
				/>
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Channel sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable
							resources={resources ?? []}
							isLoading={isResourcesLoading}
							isError={isResourcesError}
							error={resourcesError}
							onRetry={() => void refetchResources()}
							resourceNoun="channel"
							resourceNounPlural="channels"
							syncIntervalSeconds={status?.syncIntervalSeconds}
							expectedClassKeys={["messages"]}
						/>
					</CardContent>
				</Card>
			)}

			{!routeLoading && !routeError && (
				<AdminSlackNotificationSettings
					key={`slack:${workspaceData?.slackConnectionId ?? "none"}:${workspaceData?.leaderboardNotificationChannelId ?? ""}:${workspaceData?.leaderboardNotificationEnabled ?? false}:${workspaceData?.leaderboardScheduleDay ?? ""}:${workspaceData?.leaderboardScheduleTime ?? ""}:${workspaceData?.leaderboardNotificationTeam ?? ""}`}
					workspaceSlug={slug}
					hasSlackConnection={isConnectionActive}
					slackConnectionId={workspaceData?.slackConnectionId ?? undefined}
					channelId={workspaceData?.leaderboardNotificationChannelId ?? undefined}
					teamLabel={workspaceData?.leaderboardNotificationTeam ?? undefined}
					enabled={workspaceData?.leaderboardNotificationEnabled ?? false}
					scheduleDay={workspaceData?.leaderboardScheduleDay ?? undefined}
					scheduleTime={workspaceData?.leaderboardScheduleTime ?? undefined}
					channelCandidates={slackChannelCandidates ?? []}
					onSaved={() => {
						void queryClient.invalidateQueries({ queryKey: workspaceQueryOptions.queryKey });
						void queryClient.invalidateQueries({ queryKey: catalogQueryOptions.queryKey });
						invalidateSlackChannels();
					}}
				/>
			)}

			{!routeLoading && !routeError && (
				<AdminSlackChannelsSettings
					workspaceSlug={slug}
					hasSlackConnection={isConnectionActive}
					channels={isConnectionActive ? (slackChannels ?? []) : []}
					channelCandidates={slackChannelCandidates ?? []}
					isLoading={
						isConnectionActive && (isLoadingSlackChannels || isLoadingSlackChannelCandidates)
					}
					isError={isConnectionActive && (isSlackChannelsError || isSlackChannelCandidatesError)}
					onRetry={() => {
						void refetchSlackChannels();
						void refetchSlackChannelCandidates();
					}}
					onRegisterChannel={async ({
						slackChannelId,
						channelName,
					}: {
						slackChannelId: string;
						channelName?: string;
					}) => {
						await registerSlackChannel.mutateAsync({
							path: { workspaceSlug: slug },
							body: { slackChannelId, channelName },
						});
					}}
					onUpdateConsent={async ({
						slackChannelId,
						consentState,
						reason,
					}: {
						slackChannelId: string;
						consentState: SlackConsentState;
						reason?: string;
					}) => {
						await updateSlackChannelConsent.mutateAsync({
							path: { workspaceSlug: slug, slackChannelId },
							body: { consentState, reason },
						});
					}}
					onRemoveChannel={async ({
						slackChannelId,
						reason,
					}: {
						slackChannelId: string;
						reason?: string;
					}) => {
						await updateSlackChannelConsent.mutateAsync({
							path: { workspaceSlug: slug, slackChannelId },
							body: { consentState: "REVOKED", reason: reason?.trim() ? reason : undefined },
						});
					}}
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
