import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	deleteOutlineCollectionMutation,
	getConnectionSyncStatusOptions,
	getOutlineTokenStatusOptions,
	initiateMutation,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesOptions,
	listOptions,
	listOutlineCollectionsOptions,
	registerOutlineCollectionMutation,
	triggerSyncJobMutation,
	updateConnectionStatusMutation,
	updateConnectionSyncJobMutation,
	updateOutlineCollectionStateMutation,
} from "@/api/@tanstack/react-query.gen";
import type { OutlineMirrorState } from "@/components/admin/integrations/outline/OutlineCollectionsSection";
import type { OutlineConnectInput } from "@/components/admin/integrations/outline/OutlineConnectCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import type { SyncResourcesTableProps } from "@/components/admin/integrations/SyncResourcesTable";
import type { SyncStatusHeaderProps } from "@/components/admin/integrations/SyncStatusHeader";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";

const TOKEN_STATUS_STALE_MS = 5 * 60_000;

export function useOutlineIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();

	const connectionsQueryOptions = listOptions({ path: { workspaceSlug } });
	const connectionsQuery = useQuery({
		...connectionsQueryOptions,
		enabled: Boolean(workspaceSlug),
	});
	const connections = connectionsQuery.data;

	const outlineConnection = (connections ?? []).find(
		(connection) => connection.kind === "OUTLINE" && connection.state !== "UNINSTALLED",
	);
	const hasConnection = outlineConnection != null;
	const isConnectionActive = outlineConnection?.state === "ACTIVE";
	const connectionId = outlineConnection?.id;

	const statusQueryOptions = getConnectionSyncStatusOptions({
		path: { workspaceSlug, connectionId: connectionId ?? -1 },
	});
	const {
		data: connectionStatus,
		error: statusError,
		refetch: refetchStatus,
	} = useQuery({
		...statusQueryOptions,
		enabled: hasConnection && connectionId != null,
		refetchInterval: (query) =>
			syncPollInterval(query.state.data?.activeJob != null, livePushUnavailable),
	});

	const resourcesQueryOptions = listConnectionSyncResourcesOptions({
		path: { workspaceSlug, connectionId: connectionId ?? -1 },
	});
	const {
		data: resources,
		isLoading: isResourcesLoading,
		isError: isResourcesError,
		error: resourcesError,
		refetch: refetchResources,
	} = useQuery({
		...resourcesQueryOptions,
		enabled: hasConnection && connectionId != null,
		refetchInterval: syncPollInterval(connectionStatus?.activeJob != null, livePushUnavailable),
	});

	const collectionsQueryOptions = listOutlineCollectionsOptions({ path: { workspaceSlug } });
	const {
		data: collections,
		isLoading: isLoadingCollections,
		error: collectionsError,
		refetch: refetchCollections,
	} = useQuery({
		...collectionsQueryOptions,
		enabled: isConnectionActive,
		// A collection still PENDING is work in flight, so it drives the same adaptive cadence as
		// an active job rather than a third hand-tuned interval.
		refetchInterval: (query) =>
			syncPollInterval(
				query.state.data?.some((collection) => collection.syncStatus === "PENDING") ?? false,
				livePushUnavailable,
			),
	});

	const tokenStatusQueryOptions = getOutlineTokenStatusOptions({ path: { workspaceSlug } });
	const {
		data: tokenStatus,
		isLoading: isTokenStatusLoading,
		error: tokenStatusError,
		refetch: refetchTokenStatus,
	} = useQuery({
		...tokenStatusQueryOptions,
		enabled: isConnectionActive,
		staleTime: TOKEN_STATUS_STALE_MS,
		retry: false,
	});

	const invalidateConnections = () =>
		queryClient.invalidateQueries({ queryKey: connectionsQueryOptions.queryKey });

	const invalidateOutline = () => {
		void queryClient.invalidateQueries({ queryKey: collectionsQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: statusQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: resourcesQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: tokenStatusQueryOptions.queryKey });
		if (connectionId != null) {
			void queryClient.invalidateQueries({
				queryKey: listConnectionSyncJobsQueryKey({ path: { workspaceSlug, connectionId } }),
			});
		}
	};

	const connect = useMutation({
		...initiateMutation(),
		onSuccess: () => {
			toast.success("Outline connected");
			void invalidateConnections();
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Could not connect Outline", { description: problemDetailOf(e) });
		},
	});

	const disconnect = useMutation({
		...updateConnectionStatusMutation(),
		onSuccess: () => {
			toast.success("Outline disconnected");
			void invalidateConnections();
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to disconnect Outline", { description: problemDetailOf(e) });
		},
	});

	const syncNow = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: () => {
			toast.success("Sync started");
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to start sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: () => {
			toast.success("Cancelling — stopping after current collection…");
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to cancel sync", { description: problemDetailOf(e) });
		},
	});

	const registerCollection = useMutation({
		...registerOutlineCollectionMutation(),
		onSuccess: (collection) => {
			toast.success(`Collection “${collection.name ?? collection.collectionId}” added`);
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to add collection", { description: problemDetailOf(e) });
		},
	});

	const updateCollectionState = useMutation({
		...updateOutlineCollectionStateMutation(),
		onSuccess: (collection) => {
			toast.success(collection.state === "PAUSED" ? "Collection paused" : "Collection resumed");
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to update collection", { description: problemDetailOf(e) });
		},
	});

	const removeCollection = useMutation({
		...deleteOutlineCollectionMutation(),
		onSuccess: () => {
			toast.success("Collection removed and its mirrored documents erased");
			invalidateOutline();
		},
		onError: (e) => {
			toast.error("Failed to remove collection", { description: problemDetailOf(e) });
		},
	});

	// The catalog does not expose deployment availability, so translate the server's missing-strategy error.
	const connectErrorMessage = connect.error == null ? undefined : problemDetailOf(connect.error);
	const connectUnavailable =
		connectErrorMessage != null && /no connectionstrategy registered/iu.test(connectErrorMessage);

	const handleConnect = (input: OutlineConnectInput) => {
		connect.mutate({
			path: { workspaceSlug },
			body: {
				kind: "OUTLINE",
				userInput: {
					server_url: input.serverUrl,
					token: input.token,
				},
			},
		});
	};

	const handleDisconnect = () => {
		if (outlineConnection?.id == null) {
			return;
		}
		disconnect.mutate({
			path: { workspaceSlug, id: outlineConnection.id },
			body: { state: "UNINSTALLED" },
		});
	};

	const handleRegisterCollection = async ({ collectionId }: { collectionId: string }) => {
		await registerCollection.mutateAsync({
			path: { workspaceSlug },
			body: { collectionId },
		});
	};

	const handleUpdateCollectionState = async ({
		collectionId,
		state,
	}: {
		collectionId: string;
		state: OutlineMirrorState;
	}) => {
		await updateCollectionState.mutateAsync({
			path: { workspaceSlug, collectionId },
			body: { state },
		});
	};

	const handleRemoveCollection = async ({ collectionId }: { collectionId: string }) => {
		await removeCollection.mutateAsync({ path: { workspaceSlug, collectionId } });
	};

	// The shared `SyncStatusHeader` renders the whole connection plane from the raw unified status.
	// Outline exposes no backfill affordance, so `onBackfill` is omitted and the split button never
	// appears.
	const syncStatusHeaderProps: Omit<SyncStatusHeaderProps, "label"> = {
		credentialsUnreadableSince: outlineConnection?.credentialsUnreadableSince,
		status: connectionStatus,
		isConnectionActive,
		// Outline's only manual trigger is a reconciliation, so a bare `isPending` names it exactly.
		triggeringType: syncNow.isPending ? "RECONCILIATION" : null,
		isCancelling: cancelJob.isPending,
		onRetry: () => void refetchStatus(),
		onSync: () => {
			if (connectionId == null) {
				return;
			}
			syncNow.mutate({
				path: { workspaceSlug, connectionId },
				body: { type: "RECONCILIATION" },
			});
		},
		onCancel: () => {
			const jobId = connectionStatus?.activeJob?.id;
			if (connectionId == null || jobId == null) {
				return;
			}
			cancelJob.mutate({
				path: { workspaceSlug, connectionId, jobId },
				body: { cancelRequested: true },
			});
		},
	};

	return {
		// Exposed even when SUSPENDED: reading job history is safe, and a suspended connection is when
		// an admin most needs to see what the last run did. Sync controls stay gated (isConnectionActive).
		connectionId,
		hasConnection,
		isConnectionActive,
		connectionState: outlineConnection?.state,
		credentialsUnreadableSince: outlineConnection?.credentialsUnreadableSince,
		// Lets the route poll its job-history query on the same adaptive cadence as the rest.
		hasActiveJob: connectionStatus?.activeJob != null,
		isLoading: connectionsQuery.isLoading,
		connectionsError: connectionsQuery.error,
		retryConnections: () => void connectionsQuery.refetch(),
		// The raw unified status; the route gates the shared header on its presence.
		status: connectionStatus,
		statusError,
		retryStatus: () => void refetchStatus(),
		tokenStatusError,
		retryTokenStatus: () => void refetchTokenStatus(),
		syncStatusHeaderProps,
		// The per-collection observability ledger — the same shared table SCM and Slack mount. Shown
		// even when suspended, so an admin can see how far behind each collection got before sync stopped.
		syncResourcesProps: {
			resources: resources ?? [],
			isLoading: isResourcesLoading,
			isError: isResourcesError,
			error: resourcesError,
			onRetry: () => void refetchResources(),
			resourceNoun: "collection",
			resourceNounPlural: "collections",
			// The freshness cadence comes from the server so the client doesn't hard-code one; without it
			// the ledger can't judge staleness.
			syncIntervalSeconds: connectionStatus?.syncIntervalSeconds,
			expectedClassKeys: ["documents"],
		} satisfies SyncResourcesTableProps,
		connectCardProps: {
			connected: hasConnection,
			connectionState: outlineConnection?.state,
			connectionLabel: outlineConnection?.displayName,
			tokenStatus,
			isTokenStatusLoading: hasConnection && isTokenStatusLoading,
			isConnecting: connect.isPending,
			isDisconnecting: disconnect.isPending,
			errorMessage: connectErrorMessage,
			connectUnavailable,
			onConnect: handleConnect,
			onDisconnect: handleDisconnect,
		},
		collectionsProps: isConnectionActive
			? {
					workspaceSlug,
					collections: collections ?? [],
					isLoading: isLoadingCollections,
					error: collectionsError,
					onRetry: () => refetchCollections(),
					onRegisterCollection: handleRegisterCollection,
					onUpdateCollectionState: handleUpdateCollectionState,
					onRemoveCollection: handleRemoveCollection,
				}
			: undefined,
	};
}
