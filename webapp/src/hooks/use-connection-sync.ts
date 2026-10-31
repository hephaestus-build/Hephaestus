import { type UseQueryResult, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "@/lib/toast";

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
	returnedJobMessage,
	type SyncTriggerType,
	syncPollInterval,
} from "@/components/admin/integrations/sync-format";
import type { SyncJobsTableProps } from "@/components/admin/integrations/SyncJobsTable";
import type { SyncResourcesTableProps } from "@/components/admin/integrations/SyncResourcesTable";
import type {
	SyncStatusHeaderError,
	SyncStatusHeaderProps,
} from "@/components/admin/integrations/SyncStatusHeader";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const JOBS_PAGE_SIZE = 10;

/**
 * A sync read as its table shows it. A retryable failed refresh keeps the rows this key last read,
 * with the failure beside them. A refusal, or a read that never succeeded, shows no rows.
 */
function syncRead<TData>(query: UseQueryResult<TData>) {
	if (query.isRefetchError && queryErrorIsRetryable(query.error)) {
		const refreshFailure: RefreshFailure = {
			error: query.error,
			pending: query.isFetching,
			onRetry: () => {
				void query.refetch();
			},
		};
		return { data: query.data, isError: false, refreshFailure };
	}
	return {
		data: query.isError ? undefined : query.data,
		isError: query.isError,
		refreshFailure: undefined,
	};
}

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
	const [pagination, setPagination] = useState({ connectionId, page: 0 });
	const jobsPage = pagination.connectionId === connectionId ? pagination.page : 0;
	if (pagination.connectionId !== connectionId) {
		setPagination({ connectionId, page: 0 });
	}
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

	const resourcesOptions = listConnectionSyncResourcesOptions({ path });
	const resourcesQuery = useQuery({
		...resourcesOptions,
		enabled: hasConnection,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});
	useQueryReadRefusal(resourcesOptions.queryKey, resourcesQuery.isError, resourcesQuery.error);
	const resources = syncRead(resourcesQuery);

	const jobsOptions = listConnectionSyncJobsOptions({
		path,
		query: { page: jobsPage, size: JOBS_PAGE_SIZE },
	});
	const jobsQuery = useQuery({
		...jobsOptions,
		enabled: hasConnection,
		refetchInterval: syncPollInterval(hasActiveJob, livePushUnavailable),
	});
	useQueryReadRefusal(jobsOptions.queryKey, jobsQuery.isError, jobsQuery.error);
	const jobs = syncRead(jobsQuery);

	const invalidateSyncActivity = async () => {
		const invalidations: Promise<void>[] = [];
		if (connectionId != null) {
			const keyPath = { path: { workspaceSlug, connectionId } };
			invalidations.push(
				queryClient.invalidateQueries({ queryKey: getConnectionSyncStatusQueryKey(keyPath) }),

				queryClient.invalidateQueries({ queryKey: listConnectionSyncJobsQueryKey(keyPath) }),
				queryClient.invalidateQueries({
					queryKey: listConnectionSyncResourcesQueryKey(keyPath),
				}),
			);
		}
		invalidateAlso?.();
		await Promise.all(invalidations);
	};

	const triggerSync = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: async (job) => {
			toast.message(returnedJobMessage(job));
			await invalidateSyncActivity();
		},
		onError: (e) => {
			toast.error("We could not start the sync", { description: problemDetailOf(e) });
		},
	});

	const cancelJob = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: async () => {
			toast.success(`Canceling the sync. It stops after the current ${cancelsAfter}.`);
			await invalidateSyncActivity();
		},
		onError: (e) => {
			toast.error("We could not cancel the sync", { description: problemDetailOf(e) });
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

	// A connection that cannot be read makes its status meaningless, so the connection error wins
	// when both are set.
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
			if (hasConnection) {
				void statusQuery.refetch();
			}
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
		invalidateSyncActivity: () => {
			void invalidateSyncActivity();
		},
		syncStatusHeaderProps,
		syncResourcesProps: {
			resources: resources.data ?? [],
			isLoading: resourcesQuery.isLoading,
			isError: resources.isError,
			error: resourcesQuery.error,
			refreshFailure: resources.refreshFailure,
			onRetry: () => {
				void resourcesQuery.refetch();
			},
			resourceNoun,
			resourceNounPlural,
			syncIntervalSeconds: status?.syncIntervalSeconds,
			expectedClassKeys,
		} satisfies SyncResourcesTableProps,
		jobHistoryProps: {
			jobs: jobs.data?.content ?? [],
			isLoading: jobsQuery.isLoading,
			isError: jobs.isError,
			error: jobsQuery.error,
			refreshFailure: jobs.refreshFailure,
			onRetry: () => {
				void jobsQuery.refetch();
			},
			page: jobsPage,
			totalPages: jobs.data?.totalPages ?? 1,
			onPageChange: (page: number) => setPagination({ connectionId, page }),
		} satisfies SyncJobsTableProps,
	};
}
