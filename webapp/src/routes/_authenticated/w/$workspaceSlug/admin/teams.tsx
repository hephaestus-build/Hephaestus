import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { toast } from "sonner";
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
import { WorkspaceTeamsTable } from "@/components/admin/teams/WorkspaceTeamsTable";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/teams")({
	head: workspaceAdminHead("Team setup"),
	validateSearch: z.object({
		q: z.string().max(200).optional().catch(undefined),
	}),
	component: AdminTeamsContainer,
});

function AdminTeamsContainer() {
	const queryClient = useQueryClient();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const {
		adminWorkspaceSlug: workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
		refetch: refetchWorkspace,
	} = useWorkspaceAccess();
	const slug = workspaceSlug ?? "";
	const hasWorkspace = Boolean(workspaceSlug);

	const teamsOptions = getAllTeamsOptions({ path: { workspaceSlug: slug } });
	const teamsQuery = useQuery({ ...teamsOptions, enabled: hasWorkspace });
	useQueryReadRefusal(teamsOptions.queryKey, teamsQuery.isError, teamsQuery.error);

	const updateTeamVisibility = useMutation({
		...updateTeamVisibilityMutation(),
		onMutate: async (vars: Options<UpdateTeamVisibilityData>) => {
			const teamsQueryKey = getAllTeamsQueryKey({
				path: { workspaceSlug: vars.path.workspaceSlug },
			});
			await queryClient.cancelQueries({ queryKey: teamsQueryKey });
			const prev = queryClient.getQueryData<TeamInfo[]>(teamsQueryKey);
			const teamId = vars.path.id;
			const hidden = typeof vars.body === "boolean" ? vars.body : vars.query?.hidden;
			let optimistic: TeamInfo[] | undefined;
			if (prev && typeof teamId === "number" && typeof hidden === "boolean") {
				const next = prev.map((t) => (t.id === teamId ? { ...t, hidden } : t));
				optimistic = queryClient.setQueryData<TeamInfo[]>(teamsQueryKey, next);
			}
			return { teamsQueryKey, prev, optimistic };
		},
		onError: (error, variables, ctx) => {
			if (
				ctx?.prev &&
				ctx.optimistic !== undefined &&
				queryClient.getQueryData(ctx.teamsQueryKey) === ctx.optimistic
			) {
				queryClient.setQueryData(ctx.teamsQueryKey, ctx.prev);
			}
			toast.error("We could not update team visibility", {
				description: `${problemDetailOf(error)} ${inWorkspace(variables.path.workspaceSlug)}`,
			});
		},
		onSettled: (_data, _error, variables) => {
			void queryClient.invalidateQueries({
				queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: variables.path.workspaceSlug } }),
			});
		},
	});

	const addLabelToTeam = useMutation({
		...addLabelToTeamMutation(),
		onError: (error, variables) => {
			toast.error("We could not update the team’s labels", {
				description: `${problemDetailOf(error)} ${inWorkspace(variables.path.workspaceSlug)}`,
			});
		},
		onSettled: (_data, _error, variables) => {
			void queryClient.invalidateQueries({
				queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: variables.path.workspaceSlug } }),
			});
		},
	});

	const removeLabelFromTeam = useMutation({
		...removeLabelFromTeamMutation(),
		onError: (error, variables) => {
			toast.error("We could not update the team’s labels", {
				description: `${problemDetailOf(error)} ${inWorkspace(variables.path.workspaceSlug)}`,
			});
		},
		onSettled: (_data, _error, variables) => {
			void queryClient.invalidateQueries({
				queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: variables.path.workspaceSlug } }),
			});
		},
	});

	const updateRepositoryVisibility = useMutation({
		...updateRepositoryVisibilityMutation(),
		onMutate: async (vars: Options<UpdateRepositoryVisibilityData>) => {
			const teamsQueryKey = getAllTeamsQueryKey({
				path: { workspaceSlug: vars.path.workspaceSlug },
			});
			await queryClient.cancelQueries({ queryKey: teamsQueryKey });
			const prev = queryClient.getQueryData<TeamInfo[]>(teamsQueryKey);
			const { teamId } = vars.path;
			const { repositoryId } = vars.path;
			const hidden =
				typeof vars.body === "boolean" ? vars.body : vars.query?.hiddenFromContributions;
			let optimistic: TeamInfo[] | undefined;
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
				optimistic = queryClient.setQueryData<TeamInfo[]>(teamsQueryKey, next);
			}
			return { teamsQueryKey, prev, optimistic };
		},
		onError: (error, variables, ctx) => {
			if (
				ctx?.prev &&
				ctx.optimistic !== undefined &&
				queryClient.getQueryData(ctx.teamsQueryKey) === ctx.optimistic
			) {
				queryClient.setQueryData(ctx.teamsQueryKey, ctx.prev);
			}
			toast.error("We could not update repository visibility", {
				description: `${problemDetailOf(error)} ${inWorkspace(variables.path.workspaceSlug)}`,
			});
		},
		onSettled: (_data, _error, variables) => {
			void queryClient.invalidateQueries({
				queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: variables.path.workspaceSlug } }),
			});
		},
	});

	const handleHideTeam = async (teamId: number, hidden: boolean) => {
		if (!hasWorkspace) {
			return;
		}
		try {
			await updateTeamVisibility.mutateAsync({
				path: { workspaceSlug: slug, id: teamId },
				body: hidden,
				query: { hidden },
			});
		} catch {
			// onError has reported the failure for the submitted workspace.
		}
	};

	const handleAddLabelToTeam = async (teamId: number, repositoryId: number, label: string) => {
		if (!hasWorkspace) {
			return;
		}
		try {
			await addLabelToTeam.mutateAsync({
				path: { workspaceSlug: slug, teamId, repositoryId, label },
			});
		} catch {
			// onError has reported the failure for the submitted workspace.
		}
	};

	const handleRemoveLabelFromTeam = async (teamId: number, labelId: number) => {
		if (!hasWorkspace) {
			return;
		}
		try {
			await removeLabelFromTeam.mutateAsync({
				path: { workspaceSlug: slug, teamId, labelId },
			});
		} catch {
			// onError has reported the failure for the submitted workspace.
		}
	};

	const handleToggleRepositoryVisibility = async (
		teamId: number,
		repositoryId: number,
		hidden: boolean,
	) => {
		if (!hasWorkspace) {
			return;
		}
		try {
			await updateRepositoryVisibility.mutateAsync({
				path: { workspaceSlug: slug, teamId, repositoryId },
				body: hidden,
				query: { hiddenFromContributions: hidden },
			});
		} catch {
			// onError has reported the failure for the submitted workspace.
		}
	};

	if (!hasWorkspace && !isWorkspaceLoading && workspaceError === undefined) {
		return <NoWorkspace />;
	}

	const retry = () => {
		refetchWorkspace();
		if (hasWorkspace) {
			void teamsQuery.refetch();
		}
	};
	// A refresh that failed and may pass on retry keeps the teams; anything else is the read's error.
	const refreshFailed =
		teamsQuery.isError && teamsQuery.data !== undefined && queryErrorIsRetryable(teamsQuery.error);

	return (
		<WorkspaceTeamsTable
			teams={teamsQuery.data ?? []}
			isLoading={isWorkspaceLoading || teamsQuery.isLoading || !hasText(workspaceSlug)}
			error={workspaceError ?? (refreshFailed ? undefined : teamsQuery.error)}
			refreshFailure={
				refreshFailed
					? { error: teamsQuery.error, pending: teamsQuery.isFetching, onRetry: retry }
					: undefined
			}
			search={search.q ?? ""}
			onSearchChange={(q) => {
				void navigate({ search: { q: q || undefined }, replace: true });
			}}
			onRetry={retry}
			onHideTeam={handleHideTeam}
			onToggleRepositoryVisibility={handleToggleRepositoryVisibility}
			onAddLabelToTeam={handleAddLabelToTeam}
			onRemoveLabelFromTeam={handleRemoveLabelFromTeam}
		/>
	);
}

function inWorkspace(workspaceSlug: string): string {
	return `In workspace ${workspaceSlug}.`;
}
