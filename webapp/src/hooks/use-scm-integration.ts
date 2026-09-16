import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ComponentProps } from "react";
import { toast } from "sonner";

import {
	addRepositoryToMonitorMutation,
	getIntegrationCatalogOptions,
	getRepositoriesToMonitorOptions,
	getWorkspaceOptions,
	removeRepositoryToMonitorMutation,
	updateTokenMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Workspace } from "@/api/types.gen";
import { SCM_CLASS_KEYS } from "@/components/admin/integrations/SyncResourcesTable";
import type { WorkspaceRepositoriesSettings } from "@/components/admin/integrations/WorkspaceRepositoriesSettings";
import type { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { useConnectionSync } from "@/hooks/use-connection-sync";
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
	const {
		data: repositories,
		isLoading: isLoadingRepositories,
		error: repositoriesError,
		refetch: refetchRepositories,
	} = useQuery({
		...repositoriesQueryOptions,
	});

	const invalidateRepositorySet = () => {
		void queryClient.invalidateQueries({ queryKey: repositoriesQueryOptions.queryKey });
		sync.invalidateSyncActivity();
	};

	const addRepository = useMutation({
		...addRepositoryToMonitorMutation(),
		onSuccess: invalidateRepositorySet,
		onError: (e) => {
			toast.error("Failed to add repository", { description: problemDetailOf(e) });
		},
	});
	const removeRepository = useMutation({
		...removeRepositoryToMonitorMutation(),
		onSuccess: invalidateRepositorySet,
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
		syncStatusHeaderProps: sync.syncStatusHeaderProps,
		syncResourcesProps: sync.syncResourcesProps,
		jobHistoryProps: sync.jobHistoryProps,
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
		} satisfies ComponentProps<typeof WorkspaceRepositoriesSettings>,
	};
}
