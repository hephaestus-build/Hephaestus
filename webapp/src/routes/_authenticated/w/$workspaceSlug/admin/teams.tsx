import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { z } from "zod";

import {
	addLabelToTeamMutation,
	getAllTeamsOptions,
	getAllTeamsQueryKey,
	removeLabelFromTeamMutation,
	updateRepositoryVisibilityMutation,
	updateTeamVisibilityMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Options } from "@/api/sdk.gen";
import type {
	TeamInfo,
	UpdateRepositoryVisibilityData,
	UpdateTeamVisibilityData,
} from "@/api/types.gen";
import { WorkspaceTeamsTable } from "@/components/admin/WorkspaceTeamsTable";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { workspaceAdminHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/teams")({
	head: workspaceAdminHead("Teams"),
	validateSearch: z.object({
		q: z.string().max(200).optional().catch(undefined),
	}),
	component: AdminTeamsContainer,
});

function AdminTeamsContainer() {
	const queryClient = useQueryClient();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const slug = workspaceSlug ?? "";
	const hasWorkspace = Boolean(workspaceSlug);

	const teamsQueryKey = getAllTeamsQueryKey({
		path: { workspaceSlug: slug },
	});
	const teamsQuery = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug: slug } }),
		enabled: hasWorkspace,
	});

	const updateTeamVisibility = useMutation({
		...updateTeamVisibilityMutation(),
		onMutate: async (vars: Options<UpdateTeamVisibilityData>) => {
			await queryClient.cancelQueries({ queryKey: teamsQueryKey });
			const prev = queryClient.getQueryData<TeamInfo[]>(teamsQueryKey);
			const teamId = vars.path.id;
			const hidden = typeof vars.body === "boolean" ? vars.body : vars.query?.hidden;
			if (prev && typeof teamId === "number" && typeof hidden === "boolean") {
				const next = prev.map((t) => (t.id === teamId ? { ...t, hidden } : t));
				queryClient.setQueryData(teamsQueryKey, next);
			}
			return { prev };
		},
		onError: (_err, _vars, ctx) => {
			if (ctx?.prev) {
				queryClient.setQueryData(teamsQueryKey, ctx.prev);
			}
		},
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: teamsQueryKey });
		},
	});

	const addLabelToTeam = useMutation({
		...addLabelToTeamMutation(),
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: teamsQueryKey });
		},
	});

	const removeLabelFromTeam = useMutation({
		...removeLabelFromTeamMutation(),
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: teamsQueryKey });
		},
	});

	const updateRepositoryVisibility = useMutation({
		...updateRepositoryVisibilityMutation(),
		onMutate: async (vars: Options<UpdateRepositoryVisibilityData>) => {
			await queryClient.cancelQueries({ queryKey: teamsQueryKey });
			const prev = queryClient.getQueryData<TeamInfo[]>(teamsQueryKey);
			const { teamId } = vars.path;
			const { repositoryId } = vars.path;
			const hidden =
				typeof vars.body === "boolean" ? vars.body : vars.query?.hiddenFromContributions;
			if (
				prev &&
				typeof teamId === "number" &&
				typeof repositoryId === "number" &&
				typeof hidden === "boolean"
			) {
				const next = prev.map((team) => {
					if (team.id !== teamId) {
						return team;
					}
					return {
						...team,
						repositories: team.repositories.map((repo) =>
							repo.id === repositoryId ? { ...repo, hiddenFromContributions: hidden } : repo,
						),
					};
				});
				queryClient.setQueryData(teamsQueryKey, next);
			}
			return { prev };
		},
		onError: (_err, _vars, ctx) => {
			if (ctx?.prev) {
				queryClient.setQueryData(teamsQueryKey, ctx.prev);
			}
		},
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: teamsQueryKey });
		},
	});

	const handleHideTeam = async (teamId: number, hidden: boolean) => {
		if (!hasWorkspace) {
			return;
		}
		await updateTeamVisibility.mutateAsync({
			path: { workspaceSlug: slug, id: teamId },
			body: hidden,
			query: { hidden },
		});
	};

	const handleAddLabelToTeam = async (teamId: number, repositoryId: number, label: string) => {
		if (!hasWorkspace) {
			return;
		}
		await addLabelToTeam.mutateAsync({
			path: { workspaceSlug: slug, teamId, repositoryId, label },
		});
	};

	const handleRemoveLabelFromTeam = async (teamId: number, labelId: number) => {
		if (!hasWorkspace) {
			return;
		}
		await removeLabelFromTeam.mutateAsync({
			path: { workspaceSlug: slug, teamId, labelId },
		});
	};

	const handleToggleRepositoryVisibility = async (
		teamId: number,
		repositoryId: number,
		hidden: boolean,
	) => {
		if (!hasWorkspace) {
			return;
		}
		await updateRepositoryVisibility.mutateAsync({
			path: { workspaceSlug: slug, teamId, repositoryId },
			body: hidden,
			query: { hiddenFromContributions: hidden },
		});
	};

	if (!hasWorkspace && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	return (
		<WorkspaceTeamsTable
			teams={teamsQuery.data ?? []}
			isLoading={isWorkspaceLoading || teamsQuery.isLoading || !hasText(workspaceSlug)}
			error={teamsQuery.error}
			search={search.q ?? ""}
			onSearchChange={(q) => {
				void navigate({ search: { q: q || undefined }, replace: true });
			}}
			onRetry={() => {
				void teamsQuery.refetch();
			}}
			onHideTeam={handleHideTeam}
			onToggleRepositoryVisibility={handleToggleRepositoryVisibility}
			onAddLabelToTeam={handleAddLabelToTeam}
			onRemoveLabelFromTeam={handleRemoveLabelFromTeam}
		/>
	);
}
