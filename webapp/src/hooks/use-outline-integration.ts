import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	deleteOutlineCollectionMutation,
	getOutlineTokenStatusOptions,
	initiateMutation,
	listOptions,
	listOutlineCollectionsOptions,
	registerOutlineCollectionMutation,
	updateConnectionStatusMutation,
	updateOutlineCollectionStateMutation,
} from "@/api/@tanstack/react-query.gen";
import type { OutlineMirrorState } from "@/components/admin/integrations/outline/OutlineCollectionsSection";
import type { OutlineConnectInput } from "@/components/admin/integrations/outline/OutlineConnectCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import { useConnectionSync } from "@/hooks/use-connection-sync";
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

	const collectionsQueryOptions = listOutlineCollectionsOptions({ path: { workspaceSlug } });
	const tokenStatusQueryOptions = getOutlineTokenStatusOptions({ path: { workspaceSlug } });

	const sync = useConnectionSync({
		workspaceSlug,
		connectionId,
		isConnectionActive,
		credentialsUnreadableSince: outlineConnection?.credentialsUnreadableSince,
		isConnectionLoading: connectionsQuery.isLoading,
		connectionError: connectionsQuery.error,
		retryConnection: () => {
			void connectionsQuery.refetch();
		},
		resourceNoun: "collection",
		resourceNounPlural: "collections",
		expectedClassKeys: ["documents"],
		// A run mirrors collections and is where a rejected token shows up, so both refresh with it.
		invalidateAlso: () => {
			void queryClient.invalidateQueries({ queryKey: collectionsQueryOptions.queryKey });
			void queryClient.invalidateQueries({ queryKey: tokenStatusQueryOptions.queryKey });
		},
	});

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

	const invalidateConnections = async () =>
		queryClient.invalidateQueries({ queryKey: connectionsQueryOptions.queryKey });

	const connect = useMutation({
		...initiateMutation(),
		onSuccess: () => {
			toast.success("Outline connected");
			void invalidateConnections();
			sync.invalidateSyncActivity();
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
			sync.invalidateSyncActivity();
		},
		onError: (e) => {
			toast.error("Failed to disconnect Outline", { description: problemDetailOf(e) });
		},
	});

	const registerCollection = useMutation({
		...registerOutlineCollectionMutation(),
		onSuccess: (collection) => {
			toast.success(`Collection “${collection.name ?? collection.collectionId}” added`);
			sync.invalidateSyncActivity();
		},
		onError: (e) => {
			toast.error("Failed to add collection", { description: problemDetailOf(e) });
		},
	});

	const updateCollectionState = useMutation({
		...updateOutlineCollectionStateMutation(),
		onSuccess: (collection) => {
			toast.success(collection.state === "PAUSED" ? "Collection paused" : "Collection resumed");
			sync.invalidateSyncActivity();
		},
		onError: (e) => {
			toast.error("Failed to update collection", { description: problemDetailOf(e) });
		},
	});

	const removeCollection = useMutation({
		...deleteOutlineCollectionMutation(),
		onSuccess: () => {
			toast.success("Collection removed and its mirrored documents erased");
			sync.invalidateSyncActivity();
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

	return {
		// Exposed even when SUSPENDED: reading job history is safe, and a suspended connection is when
		// an admin most needs to see what the last run did. Sync controls stay gated (isConnectionActive).
		connectionId,
		hasConnection,
		isConnectionActive,
		connectionState: outlineConnection?.state,
		credentialsUnreadableSince: outlineConnection?.credentialsUnreadableSince,
		isLoading: connectionsQuery.isLoading,
		connectionsError: connectionsQuery.error,
		retryConnections: () => {
			void connectionsQuery.refetch();
		},
		tokenStatusError,
		retryTokenStatus: () => {
			void refetchTokenStatus();
		},
		syncStatusHeaderProps: sync.syncStatusHeaderProps,
		// The per-collection observability ledger — the same shared table SCM and Slack mount. Shown
		// even when suspended, so an admin can see how far behind each collection got before sync stopped.
		syncResourcesProps: sync.syncResourcesProps,
		jobHistoryProps: sync.jobHistoryProps,
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
					onRetry: async () => refetchCollections(),
					onRegisterCollection: handleRegisterCollection,
					onUpdateCollectionState: handleUpdateCollectionState,
					onRemoveCollection: handleRemoveCollection,
				}
			: undefined,
	};
}
