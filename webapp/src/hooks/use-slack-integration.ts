import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getIntegrationCatalogOptions,
	getWorkspaceOptions,
	initiateMutation,
	listSlackChannelCandidatesOptions,
	listSlackChannelConsentEventsQueryKey,
	listSlackChannelsOptions,
	registerSlackChannelMutation,
	updateConnectionStatusMutation,
	updateSlackChannelConsentMutation,
} from "@/api/@tanstack/react-query.gen";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import type {
	WorkspaceSlackChannelsSettingsProps,
	SlackConsentState,
} from "@/components/admin/integrations/WorkspaceSlackChannelsSettings";
import type { WorkspaceSlackConnectionSettingsProps } from "@/components/admin/integrations/WorkspaceSlackConnectionSettings";
import { useConnectionSync } from "@/hooks/use-connection-sync";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

export function useSlackIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	const workspaceData = workspaceQuery.data;
	const hasSlackCredentials = workspaceData?.hasSlackToken === true;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	const entry = catalogQuery.data?.find((e) => e.kind === "SLACK");
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE" && hasSlackCredentials;
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const isLoading = workspaceQuery.isLoading || catalogQuery.isLoading;
	const loadError = workspaceQuery.error ?? catalogQuery.error;
	const retryLoad = () => {
		void workspaceQuery.refetch();
		void catalogQuery.refetch();
	};

	const sync = useConnectionSync({
		workspaceSlug,
		connectionId,
		isConnectionActive,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		isConnectionLoading: isLoading,
		connectionError: loadError,
		retryConnection: retryLoad,
		resourceNoun: "channel",
		resourceNounPlural: "channels",
		expectedClassKeys: ["messages"],
	});

	const slackChannelsQueryOptions = listSlackChannelsOptions({ path: { workspaceSlug } });
	const {
		data: slackChannels,
		isLoading: isLoadingSlackChannels,
		isError: isSlackChannelsError,
		refetch: refetchSlackChannels,
	} = useQuery({
		...slackChannelsQueryOptions,
		enabled: hasSlackCredentials,
		refetchInterval: syncPollInterval(sync.hasActiveJob, livePushUnavailable),
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
		enabled: hasSlackCredentials,
	});

	// A channel joining or leaving the set is a change to what sync mirrors, so the ledger refreshes too.
	const invalidateSlackChannels = () => {
		void queryClient.invalidateQueries({ queryKey: slackChannelsQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: slackChannelCandidatesQueryOptions.queryKey });
		sync.invalidateSyncActivity();
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

	const connect = useMutation({
		...initiateMutation(),
		onSuccess: (initiation) => {
			if (initiation.type === "REDIRECT" && hasText(initiation.vendorUrl)) {
				window.location.assign(initiation.vendorUrl);
				// The page is unloading.
				return;
			}
			throw new Error(`Unexpected non-redirect Slack initiation: ${initiation.type}`);
		},
		onError: (e) => {
			toast.error("Could not start Slack OAuth", { description: problemDetailOf(e) });
		},
	});

	const disconnect = useMutation({
		...updateConnectionStatusMutation(),
		onSuccess: () => {
			toast.success("Slack disconnected");
			void queryClient.invalidateQueries({ queryKey: workspaceQueryOptions.queryKey });
			void queryClient.invalidateQueries({ queryKey: catalogQueryOptions.queryKey });
			invalidateSlackChannels();
		},
		onError: (e) => {
			toast.error("Failed to disconnect Slack", { description: problemDetailOf(e) });
		},
	});
	const slackConnectionId = workspaceData?.slackConnectionId;

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
		const trimmedReason = reason?.trim();
		await updateSlackChannelConsent.mutateAsync({
			path: { workspaceSlug, slackChannelId },
			body: {
				consentState: "REVOKED",
				reason: hasText(trimmedReason) ? trimmedReason : undefined,
			},
		});
	};

	return {
		hasConnection,
		connectionState: entry?.connectionState,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		isLoading,
		loadError,
		retryLoad,
		syncStatusHeaderProps: sync.syncStatusHeaderProps,
		syncResourcesProps: sync.syncResourcesProps,
		jobHistoryProps: sync.jobHistoryProps,
		connectionSettingsProps: (isConnectionActive
			? {
					state: "connected",
					credentialsUnreadableSince: entry.credentialsUnreadableSince,
					onDisconnect:
						slackConnectionId == null
							? undefined
							: async () => {
									await disconnect.mutateAsync({
										path: { workspaceSlug, id: slackConnectionId },
										body: { state: "UNINSTALLED" },
									});
								},
					isDisconnecting: disconnect.isPending,
				}
			: {
					state: "disconnected",
					onConnect: () => {
						// The OAuth landing route reads the slug to route back here.
						window.sessionStorage.setItem("slack-connect-return-slug", workspaceSlug);
						connect.mutate({ path: { workspaceSlug }, body: { kind: "SLACK", userInput: {} } });
					},
					isConnecting: connect.isPending,
				}) satisfies WorkspaceSlackConnectionSettingsProps,
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
		} satisfies WorkspaceSlackChannelsSettingsProps,
	};
}
