import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ComponentProps } from "react";
import { toast } from "sonner";

import {
	addRepositoryToMonitorMutation,
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogOptions,
	getRepositoriesToMonitorOptions,
	getWorkspaceOptions,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesOptions,
	listConnectionSyncResourcesQueryKey,
	removeRepositoryToMonitorMutation,
	triggerSyncJobMutation,
	updateConnectionSyncJobMutation,
	updateTokenMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Workspace } from "@/api/types.gen";
import type { AdminRepositoriesSettings } from "@/components/admin/integrations/AdminRepositoriesSettings";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import {
	SCM_CLASS_KEYS,
	type SyncResourcesTableProps,
} from "@/components/admin/integrations/SyncResourcesTable";
import type { SyncStatusHeaderProps } from "@/components/admin/integrations/SyncStatusHeader";
import type { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { problemDetailOf } from "@/lib/problem-detail";

type ScmKind = "GITHUB" | "GITLAB";

/** Which source-control provider the workspace is on, and its display name once that is known. */
function scmProviderOf(workspace: Workspace | undefined): {
	kind: ScmKind | undefined;
	label: string;
} {
	const kind = workspace ? (workspace.kind === "GITLAB" ? "GITLAB" : "GITHUB") : undefined;
	const label = kind === "GITLAB" ? "GitLab" : kind === "GITHUB" ? "GitHub" : "Source control";
	return { kind, label };
}

export function useScmIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const livePushUnavailable = useLivePushUnavailable();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	const workspaceData = workspaceQuery.data;
	const { kind, label } = scmProviderOf(workspaceData);
	const isAppInstallationWorkspace = kind === "GITHUB" && workspaceData?.installationId != null;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	const entry = kind ? catalogQuery.data?.find((e) => e.kind === kind) : undefined;
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE";
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

	const repositoriesQueryOptions = getRepositoriesToMonitorOptions({ path: { workspaceSlug } });
	const {
		data: repositories,
		isLoading: isLoadingRepositories,
		error: repositoriesError,
		refetch: refetchRepositories,
	} = useQuery({
		...repositoriesQueryOptions,
	});

	const invalidateSyncState = () => {
		if (connectionId == null) {
			return;
		}
		void queryClient.invalidateQueries({
			queryKey: getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listConnectionSyncJobsQueryKey({ path: { workspaceSlug, connectionId } }),
		});
		void queryClient.invalidateQueries({
			queryKey: listConnectionSyncResourcesQueryKey({ path: { workspaceSlug, connectionId } }),
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

	const pendingTriggerType = triggerSync.isPending ? triggerSync.variables.body.type : undefined;
	const triggeringType =
		pendingTriggerType === "RECONCILIATION" || pendingTriggerType === "BACKFILL"
			? pendingTriggerType
			: null;

	const triggerSyncOfType = (type: "RECONCILIATION" | "BACKFILL") => {
		if (connectionId == null) {
			return;
		}
		triggerSync.mutate({ path: { workspaceSlug, connectionId }, body: { type } });
	};

	const syncStatusHeaderProps: Omit<SyncStatusHeaderProps, "label" | "actions"> = {
		status,
		isLoading: workspaceQuery.isLoading || catalogQuery.isLoading || statusQuery.isLoading,
		error: workspaceQuery.error ?? catalogQuery.error ?? statusQuery.error,
		isConnectionActive,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		triggeringType,
		isCancelling: cancelJob.isPending,
		onRetry: () => {
			void workspaceQuery.refetch();
			void catalogQuery.refetch();
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

	const handleReplaceToken = async (personalAccessToken: string) => {
		try {
			await replaceToken.mutateAsync({ path: { workspaceSlug }, body: { personalAccessToken } });
			replaceToken.reset();
			return true;
		} catch {
			return false;
		}
	};

	return {
		kind,
		label,
		isAppInstallationWorkspace,
		hasConnection,
		isConnectionActive,
		connectionState: entry?.connectionState,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		// Lets the route poll its job-history query on the same adaptive cadence as the rest.
		hasActiveJob,
		connectionId,
		status,
		syncStatusHeaderProps,
		syncResourcesProps: {
			resources: resources ?? [],
			isLoading: isResourcesLoading,
			isError: isResourcesError,
			error: resourcesError,
			onRetry: () => void refetchResources(),
			resourceNoun: "repository",
			resourceNounPlural: "repositories",
			syncIntervalSeconds: status?.syncIntervalSeconds,
			expectedClassKeys: SCM_CLASS_KEYS,
		} satisfies SyncResourcesTableProps,
		tokenSettingsProps: {
			providerLabel: label,
			isSaving: replaceToken.isPending,
			error: replaceToken.error,
			onSave: handleReplaceToken,
		} satisfies ComponentProps<typeof WorkspaceScmTokenSettings>,
		repositoriesSettingsProps: {
			repositories: (repositories ?? []).map((repo) => ({ nameWithOwner: repo })),
			providerLabel: label,
			isLoading: isLoadingRepositories,
			error: repositoriesError,
			addRepositoryError: addRepository.error,
			isAddingRepository: addRepository.isPending,
			isRemovingRepository: removeRepository.isPending,
			onAddRepository: (nameWithOwner: string) => {
				addRepository.mutate({ path: { workspaceSlug }, query: { nameWithOwner } });
			},
			onRemoveRepository: (nameWithOwner: string) => {
				removeRepository.mutate({ path: { workspaceSlug }, query: { nameWithOwner } });
			},
			onRetry: () => void refetchRepositories(),
		} satisfies ComponentProps<typeof AdminRepositoriesSettings>,
	};
}
