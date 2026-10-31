import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	getIntegrationCatalogOptions,
	getSlackScopesOptions,
	getWorkspaceOptions,
	initiateMutation,
	listSlackChannelCandidatesOptions,
	listSlackChannelConsentEventsQueryKey,
	listSlackChannelsOptions,
	registerSlackChannelMutation,
	updateConnectionStatusMutation,
	updateSlackChannelConsentMutation,
} from "@/api/@tanstack/react-query.gen";
import type { SlackScopes } from "@/api/types.gen";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import type {
	WorkspaceSlackChannelsSettingsProps,
	SlackConsentState,
} from "@/components/admin/integrations/WorkspaceSlackChannelsSettings";
import type {
	SlackScopeCheck,
	WorkspaceSlackConnectionSettingsProps,
} from "@/components/admin/integrations/WorkspaceSlackConnectionSettings";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useConnectionSync } from "@/hooks/use-connection-sync";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export function useSlackIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	useQueryReadRefusal(workspaceQueryOptions.queryKey, workspaceQuery.isError, workspaceQuery.error);
	// A failed refresh that can be retried keeps the workspace and connection each key last read, and
	// what is open on them, beside the failure. A refusal withdraws them at once, before its entry is
	// evicted, so nothing the connection owns stays actionable.
	const workspaceRetained =
		workspaceQuery.isRefetchError && queryErrorIsRetryable(workspaceQuery.error);
	const workspaceData =
		!workspaceQuery.isError || workspaceRetained ? workspaceQuery.data : undefined;
	const hasSlackCredentials = workspaceData?.hasSlackToken === true;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	useQueryReadRefusal(catalogQueryOptions.queryKey, catalogQuery.isError, catalogQuery.error);
	const catalogRetained = catalogQuery.isRefetchError && queryErrorIsRetryable(catalogQuery.error);
	const catalog = !catalogQuery.isError || catalogRetained ? catalogQuery.data : undefined;
	// The connection stands only while both reads that own it do.
	const entry = workspaceData === undefined ? undefined : catalog?.find((e) => e.kind === "SLACK");
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE" && hasSlackCredentials;
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const isLoading = workspaceQuery.isLoading || catalogQuery.isLoading;
	const loadError = workspaceQuery.error ?? catalogQuery.error;
	const connectionRetained =
		loadError != null &&
		(!workspaceQuery.isError || workspaceRetained) &&
		(!catalogQuery.isError || catalogRetained);
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

	// Slack answers this from the token itself. A failed read claims nothing, also over an earlier answer.
	const slackScopesQuery = useQuery({
		...getSlackScopesOptions({ path: { workspaceSlug } }),
		enabled: isConnectionActive,
	});

	const slackChannelsQueryOptions = listSlackChannelsOptions({ path: { workspaceSlug } });
	const slackChannelsQuery = useQuery({
		...slackChannelsQueryOptions,
		enabled: hasSlackCredentials,
		refetchInterval: syncPollInterval(sync.hasActiveJob, livePushUnavailable),
	});
	useQueryReadRefusal(
		slackChannelsQueryOptions.queryKey,
		slackChannelsQuery.isError,
		slackChannelsQuery.error,
	);
	// A retryable failed refresh keeps the channels this key last read, with the failure beside them.
	// A refusal, or a read that never succeeded, shows none.
	const channelsRetained =
		slackChannelsQuery.isRefetchError && queryErrorIsRetryable(slackChannelsQuery.error);
	const channelsFailed = slackChannelsQuery.isError && !channelsRetained;

	// The channels Slack lists for the picker are a separate read: its failure stays in the picker,
	// and the monitored channels and the pasted-reference path do not depend on it.
	const slackChannelCandidatesQueryOptions = listSlackChannelCandidatesOptions({
		path: { workspaceSlug },
	});
	const candidatesQuery = useQuery({
		...slackChannelCandidatesQueryOptions,
		enabled: hasSlackCredentials,
	});
	useQueryReadRefusal(
		slackChannelCandidatesQueryOptions.queryKey,
		candidatesQuery.isError,
		candidatesQuery.error,
	);

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
			toast.error("We could not add the channel", { description: problemDetailOf(e) });
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
				toast.error("We could not remove the channel", { description: problemDetailOf(e) });
			} else {
				toast.error("We could not update the channel", { description: problemDetailOf(e) });
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
			toast.error("We could not connect Slack", { description: problemDetailOf(e) });
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
			toast.error("We could not disconnect Slack", { description: problemDetailOf(e) });
		},
	});
	const slackConnectionId = workspaceData?.slackConnectionId;

	// Connecting and reconnecting are one OAuth start: the server refreshes this workspace's active
	// connection when Slack returns the team it already holds.
	const startSlackOAuth = () => {
		connect.mutate({ path: { workspaceSlug }, body: { kind: "SLACK", userInput: {} } });
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
		connectionRetained,
		retryLoad,
		syncStatusHeaderProps: sync.syncStatusHeaderProps,
		syncResourcesProps: sync.syncResourcesProps,
		jobHistoryProps: sync.jobHistoryProps,
		connectionSettingsProps: (isConnectionActive
			? {
					state: "connected",
					credentialsUnreadableSince: entry.credentialsUnreadableSince,
					scopeCheck: slackScopeCheck(slackScopesQuery),
					onReconnect: startSlackOAuth,
					isReconnecting: connect.isPending,
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
					onConnect: startSlackOAuth,
					isConnecting: connect.isPending,
				}) satisfies WorkspaceSlackConnectionSettingsProps,
		channelsSettingsProps: {
			workspaceSlug,
			hasSlackConnection: isConnectionActive,
			channels: isConnectionActive && !channelsFailed ? (slackChannelsQuery.data ?? []) : [],
			isLoading: isConnectionActive && slackChannelsQuery.isLoading,
			isError: isConnectionActive && channelsFailed,
			error: slackChannelsQuery.error,
			onRetry: () => {
				void slackChannelsQuery.refetch();
			},
			refreshFailure:
				isConnectionActive && channelsRetained
					? {
							error: slackChannelsQuery.error,
							pending: slackChannelsQuery.isFetching,
							onRetry: () => {
								void slackChannelsQuery.refetch();
							},
						}
					: undefined,
			channelCandidates: candidatesQuery.isError ? [] : (candidatesQuery.data ?? []),
			candidatesLoading: candidatesQuery.isLoading,
			candidatesFailure: candidatesQuery.isError
				? {
						error: candidatesQuery.error,
						pending: candidatesQuery.isFetching,
						onRetry: () => {
							void candidatesQuery.refetch();
						},
					}
				: undefined,
			onRegisterChannel: handleRegisterChannel,
			onUpdateConsent: handleUpdateConsent,
			onRemoveChannel: handleRemoveChannel,
		} satisfies WorkspaceSlackChannelsSettingsProps,
	};
}

function slackScopeCheck(query: UseQueryResult<SlackScopes, unknown>): SlackScopeCheck {
	if (query.isError) {
		return {
			state: "failed",
			onRetry: () => {
				void query.refetch();
			},
			isRetrying: query.isFetching,
		};
	}
	return query.data === undefined
		? { state: "checking" }
		: { state: "checked", missingScopes: query.data.missingScopes };
}
