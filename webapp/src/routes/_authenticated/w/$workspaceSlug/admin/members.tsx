import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { toast } from "sonner";
import { z } from "zod";

import {
	getAllTeamsOptions,
	listMembersOptions,
	listMembersQueryKey,
	assignRoleMutation,
	removeMemberMutation,
	getCurrentUserMembershipQueryKey,
	getUsersWithTeamsOptions,
	getUsersWithTeamsQueryKey,
	updateMemberVisibilityMutation,
} from "@/api/@tanstack/react-query.gen";
import { AdminMembersPage } from "@/components/admin/AdminMembersPage";
import { adaptApiUserTeams } from "@/components/admin/types";
import type { UsersTableView } from "@/components/admin/UsersTable";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { workspaceMembershipQueryOptions } from "@/integrations/auth/guard";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/members")({
	head: workspaceAdminHead("Members"),
	validateSearch: z.object({
		q: z.string().max(200).optional().catch(undefined),
		team: z.string().optional().catch(undefined),
		sort: z.enum(["name", "username"]).optional().catch(undefined),
		desc: z
			.union([z.boolean(), z.enum(["true", "false"]).transform((value) => value === "true")])
			.optional()
			.catch(undefined),
		page: z.coerce.number().int().min(0).optional().catch(undefined),
		size: z.coerce
			.number()
			.refine((value) => [10, 20, 30, 40, 50].includes(value))
			.optional()
			.catch(undefined),
	}),
	component: AdminMembersContainer,
});

function AdminMembersContainer() {
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const {
		workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
	} = useActiveWorkspaceSlug();

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

	const teamsQueryOptions = getAllTeamsOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
	});
	const {
		data: teamsData,
		isLoading: teamsLoading,
		error: teamsError,
		refetch: refetchTeams,
	} = useQuery({
		...teamsQueryOptions,
		enabled: Boolean(workspaceSlug) && (teamsQueryOptions.enabled ?? true),
	});

	const queryClient = useQueryClient();
	const accountMembers = useQuery({
		...listMembersOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});
	const currentMembership = useQuery({
		...workspaceMembershipQueryOptions(workspaceSlug ?? ""),
		enabled: Boolean(workspaceSlug),
	});
	const refreshMemberships = async () => {
		await Promise.all([
			queryClient.invalidateQueries({
				queryKey: listMembersQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
			}),
			queryClient.invalidateQueries({
				queryKey: getCurrentUserMembershipQueryKey({
					path: { workspaceSlug: workspaceSlug ?? "" },
				}),
			}),
		]);
	};
	const assignMembership = useMutation({
		...assignRoleMutation(),
		onSuccess: refreshMemberships,
		onError: (error) => toast.error(problemDetailOf(error, "Couldn't update workspace access.")),
	});
	const suspendMembership = useMutation({
		...removeMemberMutation(),
		onSuccess: refreshMemberships,
		onError: (error) => toast.error(problemDetailOf(error, "Couldn't suspend workspace access.")),
	});
	const toggleHidden = useMutation({
		...updateMemberVisibilityMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({
				queryKey: getUsersWithTeamsQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
			});
		},
		onError: (error) => {
			toast.error(`Failed to update visibility: ${error.message}`);
		},
	});

	const handleToggleHidden = (userId: number, hidden: boolean) => {
		if (!workspaceSlug) return;
		toggleHidden.mutate({
			path: { workspaceSlug, userId },
			query: { hidden },
		});
	};

	const users = (usersData?.map(adaptApiUserTeams) ?? [])
		.map((user) => ({
			...user,
			teams: [...user.teams].sort((a, b) => a.name.localeCompare(b.name)),
		}))
		.sort((a, b) => a.user.name.localeCompare(b.user.name));
	const teams = [...(teamsData ?? [])].sort((a, b) => a.name.localeCompare(b.name));
	const isLoading = isWorkspaceLoading || usersLoading || teamsLoading;
	const selectedTeam =
		search.team && teams.some((team) => team.id.toString() === search.team) ? search.team : "all";
	const view: UsersTableView = {
		q: search.q ?? "",
		team: selectedTeam,
		sort: search.sort ?? "name",
		desc: search.desc ?? false,
		page: search.page ?? 0,
		size: search.size ?? 10,
	};

	useEffect(() => {
		if (teamsData && !teamsError && search.team && selectedTeam === "all") {
			void navigate({ search: (previous) => ({ ...previous, team: undefined }), replace: true });
		}
	}, [navigate, search.team, selectedTeam, teamsData, teamsError]);

	if (!workspaceSlug && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	return (
		<AdminMembersPage
			accountMemberships={{
				members: accountMembers.data ?? [],
				isOwner: currentMembership.data?.role === "OWNER",
				isLoading: accountMembers.isLoading,
				error: accountMembers.error,
				isSaving: assignMembership.isPending || suspendMembership.isPending,
				onRetry: () => void accountMembers.refetch(),
				onAssign: async (body) => {
					if (!workspaceSlug) return;
					await assignMembership.mutateAsync({ path: { workspaceSlug }, body });
				},
				onSuspend: (accountId) => {
					if (workspaceSlug) suspendMembership.mutate({ path: { workspaceSlug, accountId } });
				},
			}}
			users={users}
			teams={teams}
			isLoading={isLoading || !workspaceSlug}
			error={workspaceError ?? usersError ?? teamsError}
			onRetry={() => {
				void refetchUsers();
				void refetchTeams();
			}}
			onToggleHidden={handleToggleHidden}
			view={view}
			onViewChange={(patch) => {
				void navigate({
					search: (previous) => {
						const next = {
							q: "",
							team: "all",
							sort: "name" as const,
							desc: false,
							page: 0,
							size: 10,
							...previous,
							...patch,
						};
						return {
							q: next.q || undefined,
							team: next.team === "all" ? undefined : next.team,
							sort: next.sort === "name" ? undefined : next.sort,
							desc: next.desc || undefined,
							page: next.page || undefined,
							size: next.size === 10 ? undefined : next.size,
						};
					},
					replace: true,
				});
			}}
			renderPageLink={(page, props) => (
				<Link
					to="."
					search={{
						q: view.q || undefined,
						team: view.team === "all" ? undefined : view.team,
						sort: view.sort === "name" ? undefined : view.sort,
						desc: view.desc || undefined,
						page: page || undefined,
						size: view.size === 10 ? undefined : view.size,
					}}
					{...props}
				/>
			)}
		/>
	);
}
