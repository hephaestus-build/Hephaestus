import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	listConnectionSyncJobsOptions,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesOptions,
	listConnectionSyncResourcesQueryKey,
	triggerSyncJobMutation,
	updateConnectionSyncJobMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	type SyncTriggerType,
	syncPollInterval,
} from "@/components/admin/integrations/sync-format";
import type { SyncJobsTableProps } from "@/components/admin/integrations/SyncJobsTable";
import type { SyncResourcesTableProps } from "@/components/admin/integrations/SyncResourcesTable";
import type {
	SyncStatusHeaderError,
	SyncStatusHeaderProps,
} from "@/components/admin/integrations/SyncStatusHeader";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";

const JOBS_PAGE_SIZE = 10;

export interface ConnectionSyncOptions extends Pick<
	SyncResourcesTableProps,
	"resourceNoun" | "resourceNounPlural" | "expectedClassKeys"
> {
	workspaceSlug: string;
	/** Undefined until the provider's lookup has found a connection; every sync query waits on it. */
	connectionId: number | undefined;
	isConnectionActive: boolean;
	credentialsUnreadableSince?: Date | null;
	/** The lookup that finds the connection: the status header shows its loading and failure as its own. */
	isConnectionLoading: boolean;
	connectionError: unknown;
	retryConnection: () => void;
	/** What a cancelled run finishes before it stops, when that is not one resource. */
	cancelsAfter?: string;
	/** Provider-owned queries a sync run also changes; invalidated together with status, jobs and resources. */
	invalidateAlso?: () => void;
}

/**
 * The sync plane every integration shares: the unified status, the per-resource ledger, the paged
 * job history, and the trigger and cancel that change them. Each provider hook finds its connection
 * its own way and composes this on top, so the polling cadence, the invalidation set and the toasts
 * are decided once.
 */
export function useConnectionSync({
	workspaceSlug,
	connectionId,
	isConnectionActive,
	credentialsUnreadableSince,
	isConnectionLoading,
	connectionError,
	retryConnection,
	resourceNoun,
	resourceNounPlural,
	expectedClassKeys,
	cancelsAfter = resourceNoun,
	invalidateAlso,
}: ConnectionSyncOptions) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();
	const [jobsPage, setJobsPage] = useState(0);
	const hasConnection = connectionId != null;
	const path = { workspaceSlug, connectionId: connectionId ?? -1 };

	const statusQuery = useQuery({
		...getConnectionSyncStatusOptions({ path }),
		enabled: hasConnection,
		refetchInterval: (query) =>
			syncPollInterval(query.state.data?.activeJob != null, livePushUnavailable),
	});
	const status = statusQuery.data;
	const hasActiveJob = status?.activeJob != null;

	const resourcesQuery = useQuery({
		...listConnectionSyncResourcesOptions({ path }),
		enabled: hasConnection,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});

	const jobsQuery = useQuery({
		...listConnectionSyncJobsOptions({ path, query: { page: jobsPage, size: JOBS_PAGE_SIZE } }),
		enabled: hasConnection,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
		placeholderData: (previousData) => previousData,
	});

	const invalidateSyncActivity = () => {
		if (connectionId != null) {
			const keyPath = { path: { workspaceSlug, connectionId } };
			void queryClient.invalidateQueries({ queryKey: getConnectionSyncStatusQueryKey(keyPath) });
			void queryClient.invalidateQueries({ queryKey: listConnectionSyncJobsQueryKey(keyPath) });
			void queryClient.invalidateQueries({
				queryKey: listConnectionSyncResourcesQueryKey(keyPath),
			});
		}
		invalidateAlso?.();
	};

	const triggerSync = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: (job) => {
			invalidateSyncActivity();
			toast.success(job.type === "BACKFILL" ? "Backfill started" : "Sync started");
		},
		onError: (e) => {
			toast.error("Failed to start sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: () => {
			invalidateSyncActivity();
			toast.success(`Cancelling — stopping after current ${cancelsAfter}…`);
		},
		onError: (e) => {
			toast.error("Failed to cancel sync", { description: problemDetailOf(e) });
		},
	});

	// Sync and Backfill share one mutation, so the header learns which one is in flight from the
	// variables rather than from a bare `isPending`. The wire type also admits `INITIAL`, which
	// `triggerSyncOfType` never sends, so the in-flight value is narrowed back to what it can be.
	const pendingTriggerType = triggerSync.isPending ? triggerSync.variables.body.type : undefined;
	const triggeringType: SyncTriggerType | null =
		pendingTriggerType === "RECONCILIATION" || pendingTriggerType === "BACKFILL"
			? pendingTriggerType
			: null;

	const triggerSyncOfType = (type: SyncTriggerType) => {
		if (connectionId == null) {
			return;
		}
		triggerSync.mutate({ path: { workspaceSlug, connectionId }, body: { type } });
	};

	// The connection lookup fails first; the status lookup only runs once there is a connection.
	let headerError: SyncStatusHeaderError | undefined;
	if (connectionError != null) {
		headerError = { failedQuery: "connection", cause: connectionError };
	} else if (statusQuery.isError) {
		headerError = { failedQuery: "status", cause: statusQuery.error };
	}

	// `onBackfill` is always offered; the header shows it only where the server reports
	// `backfillSupported`, which is the one home for which providers can backfill.
	const syncStatusHeaderProps: Omit<SyncStatusHeaderProps, "label" | "actions"> = {
		status,
		isLoading: isConnectionLoading || statusQuery.isLoading,
		error: headerError,
		isConnectionActive,
		credentialsUnreadableSince,
		triggeringType,
		isCancelling: cancelJob.isPending,
		onRetry: () => {
			retryConnection();
			void statusQuery.refetch();
		},
		onSync: () => triggerSyncOfType("RECONCILIATION"),
		onBackfill: () => triggerSyncOfType("BACKFILL"),
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

	return {
		/** Lets a provider poll its own queries on the same adaptive cadence as the sync plane. */
		hasActiveJob,
		invalidateSyncActivity,
		syncStatusHeaderProps,
		syncResourcesProps: {
			resources: resourcesQuery.data ?? [],
			isLoading: resourcesQuery.isLoading,
			isError: resourcesQuery.isError,
			error: resourcesQuery.error,
			onRetry: () => {
				void resourcesQuery.refetch();
			},
			resourceNoun,
			resourceNounPlural,
			syncIntervalSeconds: status?.syncIntervalSeconds,
			expectedClassKeys,
		} satisfies SyncResourcesTableProps,
		jobHistoryProps: {
			jobs: jobsQuery.data?.content ?? [],
			isLoading: jobsQuery.isLoading,
			isError: jobsQuery.isError,
			error: jobsQuery.error,
			onRetry: () => {
				void jobsQuery.refetch();
			},
			page: jobsPage,
			totalPages: jobsQuery.data?.totalPages ?? 1,
			onPageChange: setJobsPage,
		} satisfies SyncJobsTableProps,
	};
}
