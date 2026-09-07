import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Navigate } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	getUsersWithTeamsOptions,
	recalculateUserAchievementsMutation,
	reloadAchievementsMutation,
} from "@/api/@tanstack/react-query.gen";
import { AdminAchievementsPage } from "@/components/admin/AdminAchievementsPage";
import { adaptApiUserTeams } from "@/components/admin/types";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useAuth } from "@/integrations/auth/AuthContext";
import { workspaceAdminHead } from "@/lib/page-title";
import { queryOperationId } from "@/lib/query-operation-id";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/achievements")({
	head: workspaceAdminHead("Achievements"),
	component: AdminAchievementsContainer,
});

function AdminAchievementsContainer() {
	const {
		workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
	} = useActiveWorkspaceSlug();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const achievementsEnabled = featureState.features?.achievementsEnabled;

	const usersQueryOptions = getUsersWithTeamsOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
	});
	const {
		data: usersData,
		isLoading: usersLoading,
		error: usersError,
		refetch: refetchUsers,
	} = useQuery({
		...usersQueryOptions,
		enabled: Boolean(workspaceSlug) && (usersQueryOptions.enabled ?? true),
	});

	const users = (usersData?.map(adaptApiUserTeams) ?? []).sort((a, b) =>
		a.user.name.localeCompare(b.user.name),
	);
	const isLoading = isWorkspaceLoading || usersLoading;

	const queryClient = useQueryClient();
	const { username } = useAuth();
	const [isRecalculatingAll, setIsRecalculatingAll] = useState(false);
	const [recalculatingUsers, setRecalculatingUsers] = useState<Set<string>>(new Set());

	const recalculateMutation = useMutation(recalculateUserAchievementsMutation());
	const reloadMutation = useMutation(reloadAchievementsMutation());

	const handleReload = () => {
		toast.promise(
			reloadMutation.mutateAsync({
				path: { workspaceSlug: workspaceSlug ?? "", login: username ?? "" },
			}),
			{
				loading: "Reloading achievement definitions...",
				success: () => {
					void queryClient.invalidateQueries({
						predicate: (query) => {
							const id = queryOperationId(query.queryKey);
							return id === "getUserAchievements" || id === "getAllAchievementDefinitions";
						},
					});
					return "Successfully reloaded achievements from YAML";
				},
				error: "Failed to reload achievement definitions",
			},
		);
	};

	const handleRecalculateAll = async () => {
		if (!users.length) return;
		setIsRecalculatingAll(true);

		const toastId = toast.loading(`Starting recalculation for ${users.length} users...`);

		try {
			const results = await Promise.allSettled(
				users.map((u) =>
					recalculateMutation.mutateAsync({
						path: { workspaceSlug: workspaceSlug ?? "", login: u.user.login },
					}),
				),
			);
			const successCount = results.filter((result) => result.status === "fulfilled").length;
			const failCount = results.length - successCount;

			if (failCount === 0) {
				toast.success(`Successfully dispatched recalculation for ${successCount} users`, {
					id: toastId,
				});
			} else {
				toast.warning(`Dispatched recalculation for ${successCount} users, ${failCount} failed`, {
					id: toastId,
				});
			}
		} catch (_error) {
			toast.error("An error occurred during bulk recalculation.", { id: toastId });
		} finally {
			setIsRecalculatingAll(false);
		}
	};

	const handleRecalculateSingle = (targetUsername: string) => {
		setRecalculatingUsers((prev) => new Set(prev).add(targetUsername));
		toast.promise(
			recalculateMutation.mutateAsync({
				path: { workspaceSlug: workspaceSlug ?? "", login: targetUsername },
			}),
			{
				loading: `Recalculating achievements for ${targetUsername}...`,
				success: `Successfully dispatched recalculation for ${targetUsername}`,
				error: `Failed to recalculate achievements for ${targetUsername}`,
				finally: () => {
					setRecalculatingUsers((prev) => {
						const newSet = new Set(prev);
						newSet.delete(targetUsername);
						return newSet;
					});
				},
			},
		);
	};

	if (!workspaceSlug && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	if (
		!featureState.isLoading &&
		!featureState.isError &&
		achievementsEnabled === false &&
		workspaceSlug
	) {
		return <Navigate to="/w/$workspaceSlug/admin/settings" params={{ workspaceSlug }} replace />;
	}

	return (
		<AdminAchievementsPage
			isReloading={reloadMutation.isPending}
			isRecalculatingAll={isRecalculatingAll}
			recalculatingUsers={recalculatingUsers}
			onReload={handleReload}
			onRecalculateAll={() => void handleRecalculateAll()}
			onRecalculate={handleRecalculateSingle}
			users={users}
			isLoading={isLoading || featureState.isLoading || achievementsEnabled !== true}
			workspaceSlug={workspaceSlug ?? ""}
			error={featureState.error ?? workspaceError ?? usersError}
			onRetry={() => {
				featureState.refetch();
				void refetchUsers();
			}}
		/>
	);
}
