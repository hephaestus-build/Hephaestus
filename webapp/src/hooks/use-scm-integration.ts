import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ComponentProps } from "react";
import { toast } from "@/lib/toast";

import {
	addRepositoryToMonitorMutation,
	getGitLabRepositorySelectionOptions,
	getGitLabRepositorySelectionQueryKey,
	getIntegrationCatalogOptions,
	getRepositoriesToMonitorOptions,
	getStoppedGitLabProjectsOptions,
	getWorkspaceOptions,
	removeRepositoryToMonitorMutation,
	removeStoppedGitLabProjectMutation,
	readOptions,
	updateGitLabRepositorySelectionMutation,
	updateTokenMutation,
} from "@/api/@tanstack/react-query.gen";
import type { GitLabRepositorySelection, GitLabStoppedProject, Workspace } from "@/api/types.gen";
import { SCM_CLASS_KEYS } from "@/components/admin/integrations/SyncResourcesTable";
import type { WorkspaceGitLabProjectSelection } from "@/components/admin/integrations/WorkspaceGitLabProjectSelection";
import type { WorkspaceRepositoriesSettings } from "@/components/admin/integrations/WorkspaceRepositoriesSettings";
import type { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import type { WorkspaceStoppedGitLabProjects } from "@/components/admin/integrations/WorkspaceStoppedGitLabProjects";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useConnectionSync } from "@/hooks/use-connection-sync";
import { problemDetailOf } from "@/lib/problem-detail";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

type ScmKind = "GITHUB" | "GITLAB";

/** Whether a failed refresh can be retried, so the data this key last read still stands beside it. */
function refreshRetryable(query: { isRefetchError: boolean; error: unknown }) {
	return query.isRefetchError && queryErrorIsRetryable(query.error);
}

/** Which source-control provider the workspace is on, and its display name once that is known. */
function scmProviderOf(workspace: Workspace | undefined): {
	kind: ScmKind | undefined;
	label: string;
} {
	if (!workspace) {
		return { kind: undefined, label: "Source control" };
	}
	if (workspace.kind === "GITLAB") {
		return { kind: "GITLAB", label: "GitLab" };
	}
	return { kind: "GITHUB", label: "GitHub" };
}

export function useScmIntegration(workspaceSlug: string) {
	const queryClient = useQueryClient();

	const workspaceQueryOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const workspaceQuery = useQuery(workspaceQueryOptions);
	useQueryReadRefusal(workspaceQueryOptions.queryKey, workspaceQuery.isError, workspaceQuery.error);
	const workspaceData =
		!workspaceQuery.isError || refreshRetryable(workspaceQuery) ? workspaceQuery.data : undefined;
	const { kind, label } = scmProviderOf(workspaceData);
	const isAppInstallationWorkspace = kind === "GITHUB" && workspaceData?.installationId != null;

	const catalogQueryOptions = getIntegrationCatalogOptions({ path: { workspaceSlug } });
	const catalogQuery = useQuery(catalogQueryOptions);
	useQueryReadRefusal(catalogQueryOptions.queryKey, catalogQuery.isError, catalogQuery.error);
	// A refused lookup withdraws the connection it last read at once, before its entry is evicted, so
	// nothing the connection owns stays actionable. An outage keeps it, with the failure in the header.
	const catalog =
		!catalogQuery.isError || refreshRetryable(catalogQuery) ? catalogQuery.data : undefined;
	const entry = kind ? catalog?.find((e) => e.kind === kind) : undefined;
	const hasConnection = entry?.connected === true;
	const isConnectionActive = entry?.connectionState === "ACTIVE";
	const connectionId = hasConnection ? entry.connectionId : undefined;

	const connectionQueryOptions = readOptions({ path: { workspaceSlug, id: connectionId ?? -1 } });
	const connectionQuery = useQuery({
		...connectionQueryOptions,
		enabled: kind === "GITLAB" && connectionId != null,
		refetchInterval: 60_000,
	});

	const sync = useConnectionSync({
		workspaceSlug,
		connectionId,
		isConnectionActive,
		credentialsUnreadableSince: entry?.credentialsUnreadableSince,
		isConnectionLoading: workspaceQuery.isLoading || catalogQuery.isLoading,
		connectionError: workspaceQuery.error ?? catalogQuery.error,
		retryConnection: () => {
			void workspaceQuery.refetch();
			void catalogQuery.refetch();
		},
		resourceNoun: "repository",
		resourceNounPlural: "repositories",
		expectedClassKeys: SCM_CLASS_KEYS,
		cancelsAfter: "step",
	});

	const repositoriesQueryOptions = getRepositoriesToMonitorOptions({ path: { workspaceSlug } });
	const repositoriesQuery = useQuery(repositoriesQueryOptions);
	useQueryReadRefusal(
		repositoriesQueryOptions.queryKey,
		repositoriesQuery.isError,
		repositoriesQuery.error,
	);
	// A failed refresh that can be retried keeps the rows, and a Stop open on one, beside the failure.
	// A refusal, or a read that never succeeded, shows none.
	const repositoriesRetained = refreshRetryable(repositoriesQuery);
	const repositoriesFailed = repositoriesQuery.isError && !repositoriesRetained;
	const refetchRepositories = () => {
		void repositoriesQuery.refetch();
	};

	// Only an active GitLab connection has a project choice or stopped projects; the server refuses
	// both reads for any other, and removes stopped work only while the connection is active.
	const followsGitLabGroup = kind === "GITLAB" && isConnectionActive;

	const stoppedQueryOptions = getStoppedGitLabProjectsOptions({ path: { workspaceSlug } });
	const stoppedQuery = useQuery({ ...stoppedQueryOptions, enabled: followsGitLabGroup });
	useQueryReadRefusal(stoppedQueryOptions.queryKey, stoppedQuery.isError, stoppedQuery.error);
	const stoppedRetained = refreshRetryable(stoppedQuery);
	const stoppedFailed = stoppedQuery.isError && !stoppedRetained;

	// A Stop, an Add and a removal each move a project between the two lists.
	const invalidateRepositorySet = () => {
		void queryClient.invalidateQueries({ queryKey: repositoriesQueryOptions.queryKey });
		void queryClient.invalidateQueries({ queryKey: stoppedQueryOptions.queryKey });
		sync.invalidateSyncActivity();
	};

	const addRepository = useMutation({
		...addRepositoryToMonitorMutation(),
		onSuccess: invalidateRepositorySet,
		onError: (e) => {
			toast.error("We could not add the repository", { description: problemDetailOf(e) });
		},
	});
	const removeRepository = useMutation({
		...removeRepositoryToMonitorMutation(),
		onSuccess: invalidateRepositorySet,
		onError: (e) => {
			toast.error("We could not stop monitoring the repository", {
				description: problemDetailOf(e),
			});
		},
	});

	// A refused or stale removal is answered by a fresh read, never sent again on its own. A newer Stop
	// read then replaces the row and its open confirmation, so the reason also stays in a toast.
	const removeStoppedProject = useMutation({
		...removeStoppedGitLabProjectMutation(),
		onError: (e) => {
			toast.error("We could not remove the synced work", { description: problemDetailOf(e) });
		},
		onSettled: invalidateRepositorySet,
	});

	const selectionQueryOptions = getGitLabRepositorySelectionOptions({ path: { workspaceSlug } });
	const selectionQuery = useQuery({ ...selectionQueryOptions, enabled: followsGitLabGroup });
	useQueryReadRefusal(selectionQueryOptions.queryKey, selectionQuery.isError, selectionQuery.error);
	const selectionRetained = refreshRetryable(selectionQuery);
	const selectionFailed = selectionQuery.isError && !selectionRetained;

	const changeSelection = useMutation({
		...updateGitLabRepositorySelectionMutation(),
		// The answer is the stored choice; reading it again afterwards catches a change made meanwhile.
		onSuccess: (stored, { path }) => {
			queryClient.setQueryData(getGitLabRepositorySelectionQueryKey({ path }), stored);
		},
		onSettled: async (_stored, _error, { path }) => {
			await queryClient.invalidateQueries({
				queryKey: getGitLabRepositorySelectionQueryKey({ path }),
			});
		},
	});

	const handleSelectionChange = async (
		repositorySelection: GitLabRepositorySelection["repositorySelection"],
	) => {
		if (changeSelection.isPending) {
			return false;
		}
		try {
			await changeSelection.mutateAsync({ path: { workspaceSlug }, body: { repositorySelection } });
			return true;
		} catch {
			return false;
		}
	};

	const replaceToken = useMutation({
		...updateTokenMutation(),
		gcTime: 0,
		onSuccess: async () => {
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: workspaceQueryOptions.queryKey }),
				queryClient.invalidateQueries({ queryKey: catalogQueryOptions.queryKey }),
				queryClient.invalidateQueries({ queryKey: connectionQueryOptions.queryKey }),
			]);
			sync.invalidateSyncActivity();
			toast.success("Personal access token replaced");
		},
	});

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
		followsGitLabGroup,
		projectSelectionProps: {
			selection: selectionFailed ? undefined : selectionQuery.data?.repositorySelection,
			isLoading: selectionQuery.isLoading,
			error: selectionFailed ? selectionQuery.error : null,
			refreshFailure: selectionRetained
				? {
						error: selectionQuery.error,
						pending: selectionQuery.isFetching,
						onRetry: () => {
							void selectionQuery.refetch();
						},
					}
				: undefined,
			onRetry: () => {
				void selectionQuery.refetch();
			},
			isSaving: changeSelection.isPending,
			saveError: changeSelection.error,
			onSelectionChange: handleSelectionChange,
		} satisfies ComponentProps<typeof WorkspaceGitLabProjectSelection>,
		syncStatusHeaderProps: sync.syncStatusHeaderProps,
		syncResourcesProps: sync.syncResourcesProps,
		jobHistoryProps: sync.jobHistoryProps,
		tokenSettingsProps: {
			providerLabel: label,
			tokenExpiresAt: connectionQuery.data?.tokenExpiresAt,
			tokenExpiryCheckedAt: connectionQuery.data?.tokenExpiryCheckedAt,
			attentionProblem: connectionQuery.data?.attentionProblem,
			isLoadingTokenMetadata: connectionQuery.isLoading,
			tokenMetadataError: connectionQuery.error,
			isSaving: replaceToken.isPending,
			error: replaceToken.error,
			onSave: handleReplaceToken,
		} satisfies ComponentProps<typeof WorkspaceScmTokenSettings>,
		repositoriesSettingsProps: {
			repositories: (repositoriesFailed ? [] : (repositoriesQuery.data ?? [])).map((repo) => ({
				nameWithOwner: repo,
			})),
			provider: kind,
			providerLabel: label,
			isLoading: repositoriesQuery.isLoading,
			error: repositoriesFailed ? repositoriesQuery.error : null,
			refreshFailure: repositoriesRetained
				? {
						error: repositoriesQuery.error,
						pending: repositoriesQuery.isFetching,
						onRetry: refetchRepositories,
					}
				: undefined,
			addRepositoryError: addRepository.error,
			isAddingRepository: addRepository.isPending,
			isRemovingRepository: removeRepository.isPending,
			onAddRepository: async (nameWithOwner: string) => {
				try {
					await addRepository.mutateAsync({ path: { workspaceSlug }, query: { nameWithOwner } });
					return true;
				} catch {
					return false;
				}
			},
			onRemoveRepository: (nameWithOwner: string) => {
				removeRepository.mutate({ path: { workspaceSlug }, query: { nameWithOwner } });
			},
			onRetry: refetchRepositories,
		} satisfies ComponentProps<typeof WorkspaceRepositoriesSettings>,
		stoppedProjectsProps: {
			projects: stoppedFailed ? [] : (stoppedQuery.data ?? []),
			isLoading: stoppedQuery.isLoading,
			error: stoppedFailed ? stoppedQuery.error : null,
			refreshFailure: stoppedRetained
				? {
						error: stoppedQuery.error,
						pending: stoppedQuery.isFetching,
						onRetry: () => {
							void stoppedQuery.refetch();
						},
					}
				: undefined,
			onRetry: () => {
				void stoppedQuery.refetch();
			},
			// The confirmation names one listed Stop; its root and receipt are sent as listed.
			onRemove: async ({ repositoryItemId, stopReceipt }: GitLabStoppedProject) => {
				await removeStoppedProject.mutateAsync({
					path: { workspaceSlug, repositoryItemId },
					query: { stopReceipt },
				});
			},
		} satisfies ComponentProps<typeof WorkspaceStoppedGitLabProjects>,
	};
}
