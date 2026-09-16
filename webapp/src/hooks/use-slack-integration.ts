import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogOptions,
	getWorkspaceOptions,
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
import type { Workspace } from "@/api/types.gen";
import type {
	AdminSlackChannelsSettingsProps,
	SlackConsentState,
} from "@/components/admin/integrations/AdminSlackChannelsSettings";
import type { AdminSlackNotificationSettingsProps } from "@/components/admin/integrations/AdminSlackNotificationSettings";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import type { SyncResourcesTableProps } from "@/components/admin/integrations/SyncResourcesTable";
import type { SyncStatusHeaderProps } from "@/components/admin/integrations/SyncStatusHeader";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

/**
 * The digest form's seed, read off the workspace. The `key` remounts the form whenever any seeded
 * value changes, so a save never leaves the fields showing the values from before it.
 */
function digestSettingsOf(workspace: Workspace | undefined) {
	const slackConnectionId = workspace?.slackConnectionId ?? undefined;
	const channelId = workspace?.leaderboardNotificationChannelId ?? undefined;
	const teamLabel = workspace?.leaderboardNotificationTeam ?? undefined;
	const enabled = workspace?.leaderboardNotificationEnabled ?? false;
	const scheduleDay = workspace?.leaderboardScheduleDay ?? undefined;
	const scheduleTime = workspace?.leaderboardScheduleTime ?? undefined;
	return {
		key: `slack:${slackConnectionId ?? "none"}:${channelId ?? ""}:${enabled}:${scheduleDay ?? ""}:${scheduleTime ?? ""}:${teamLabel ?? ""}`,
		slackConnectionId,
		channelId,
		teamLabel,
		enabled,
		scheduleDay,
		scheduleTime,
	};
}

export function useSlackIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	const workspaceData = workspaceQuery.data;
	const hasSlackToken = workspaceData?.hasSlackToken === true;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	const entry = catalogQuery.data?.find((e) => e.kind === "SLACK");
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE" && hasSlackToken;
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const statusQuery = useQuery({
		...getConnectionSyncStatusOptions({
			path: { workspaceSlug, connectionId: connectionId ?? -1 },
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
			path: { workspaceSlug, connectionId: connectionId ?? -1 },
		}),
		enabled: connectionId != null,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});

	const slackChannelsQueryOptions = listSlackChannelsOptions({ path: { workspaceSlug } });
	const {
		data: slackChannels,
		isLoading: isLoadingSlackChannels,
		isError: isSlackChannelsError,
		refetch: refetchSlackChannels,
	} = useQuery({
		...slackChannelsQueryOptions,
		enabled: hasSlackToken,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});

	const slackChannelCandidatesQueryOptions = listSlackChannelCandidatesOptions({
		path: { workspaceSlug },
	});
	const {
		data: slackChannelCandidates,
		isLoading: isLoadingSlackChannelCandidates,
		isError: isSlackChannelCandidatesError,
		refetch: refetchSlackChannelCandidates,
	} = useQuery({
		...slackChannelCandidatesQueryOptions,
		enabled: hasSlackToken,
	});

	const invalidateSlackChannels = () => {
		void queryClient.invalidateQueries({ queryKey: slackChannelsQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: slackChannelCandidatesQueryOptions.queryKey });
		if (connectionId != null) {
			void queryClient.invalidateQueries({
				queryKey: listConnectionSyncResourcesQueryKey({
					path: { workspaceSlug, connectionId },
				}),
			});
		}
	};

	const invalidateSyncActivity = (id: number) => {
		void queryClient.invalidateQueries({
			queryKey: getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId: id } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listConnectionSyncJobsQueryKey({ path: { workspaceSlug, connectionId: id } }),
		});
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
					path: { workspaceSlug, slackChannelId: variables.path.slackChannelId },
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
			if (connectionId == null) {
				return;
			}
			invalidateSyncActivity(connectionId);
			toast.success("Sync started");
		},
		onError: (e) => {
			toast.error("Failed to start sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: () => {
			if (connectionId == null) {
				return;
			}
			invalidateSyncActivity(connectionId);
			toast.success("Cancelling — stopping after current channel…");
		},
		onError: (e) => {
			toast.error("Failed to cancel sync", { description: problemDetailOf(e) });
		},
	});

	const syncStatusHeaderProps: Omit<SyncStatusHeaderProps, "label"> = {
		status,
		isConnectionActive,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		triggeringType: triggerSync.isPending ? "RECONCILIATION" : null,
		isCancelling: cancelJob.isPending,
		onRetry: () => void statusQuery.refetch(),
		onSync: () => {
			if (connectionId == null) {
				return;
			}
			triggerSync.mutate({
				path: { workspaceSlug, connectionId },
				body: { type: "RECONCILIATION" },
			});
		},
		onCancel: () => {
			const jobId = status?.activeJob?.id;
			if (connectionId == null || jobId == null) {
				return;
			}
			cancelJob.mutate({
				path: { workspaceSlug, connectionId, jobId },
				body: { cancelRequested: true },
			});
		},
	};

	const handleRegisterChannel = async ({
		slackChannelId,
		channelName,
	}: {
		slackChannelId: string;
		channelName?: string;
	}) => {
		await registerSlackChannel.mutateAsync({
			path: { workspaceSlug },
			body: { slackChannelId, channelName },
		});
	};

	const handleUpdateConsent = async ({
		slackChannelId,
		consentState,
		reason,
	}: {
		slackChannelId: string;
		consentState: SlackConsentState;
		reason?: string;
	}) => {
		await updateSlackChannelConsent.mutateAsync({
			path: { workspaceSlug, slackChannelId },
			body: { consentState, reason },
		});
	};

	const handleRemoveChannel = async ({
		slackChannelId,
		reason,
	}: {
		slackChannelId: string;
		reason?: string;
	}) => {
		await updateSlackChannelConsent.mutateAsync({
			path: { workspaceSlug, slackChannelId },
			body: {
				consentState: "REVOKED",
				reason: hasText(reason?.trim()) ? reason : undefined,
			},
		});
	};

	const { key: notificationSettingsKey, ...digestSettings } = digestSettingsOf(workspaceData);

	return {
		connectionId,
		hasConnection,
		isConnectionActive,
		connectionState: entry?.connectionState,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		// Lets the route poll its job-history query on the same adaptive cadence as the rest.
		hasActiveJob,
		isLoading: workspaceQuery.isLoading || catalogQuery.isLoading,
		loadError: workspaceQuery.error ?? catalogQuery.error,
		retryLoad: () => {
			void workspaceQuery.refetch();
			void catalogQuery.refetch();
			void statusQuery.refetch();
		},
		status,
		isStatusError: statusQuery.isError,
		statusError: statusQuery.error,
		retryStatus: () => void statusQuery.refetch(),
		syncStatusHeaderProps,
		syncResourcesProps: {
			resources: resources ?? [],
			isLoading: isResourcesLoading,
			isError: isResourcesError,
			error: resourcesError,
			onRetry: () => void refetchResources(),
			resourceNoun: "channel",
			resourceNounPlural: "channels",
			syncIntervalSeconds: status?.syncIntervalSeconds,
			expectedClassKeys: ["messages"],
		} satisfies SyncResourcesTableProps,
		notificationSettingsKey,
		notificationSettingsProps: {
			workspaceSlug,
			hasSlackConnection: isConnectionActive,
			...digestSettings,
			channelCandidates: slackChannelCandidates ?? [],
			onSaved: () => {
				void queryClient.invalidateQueries({ queryKey: workspaceQueryOptions.queryKey });
				void queryClient.invalidateQueries({ queryKey: catalogQueryOptions.queryKey });
				invalidateSlackChannels();
			},
		} satisfies AdminSlackNotificationSettingsProps,
		channelsSettingsProps: {
			workspaceSlug,
			hasSlackConnection: isConnectionActive,
			channels: isConnectionActive ? (slackChannels ?? []) : [],
			channelCandidates: slackChannelCandidates ?? [],
			isLoading: isConnectionActive && (isLoadingSlackChannels || isLoadingSlackChannelCandidates),
			isError: isConnectionActive && (isSlackChannelsError || isSlackChannelCandidatesError),
			onRetry: () => {
				void refetchSlackChannels();
				void refetchSlackChannelCandidates();
			},
			onRegisterChannel: handleRegisterChannel,
			onUpdateConsent: handleUpdateConsent,
			onRemoveChannel: handleRemoveChannel,
		} satisfies AdminSlackChannelsSettingsProps,
	};
}
