import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { toast } from "sonner";
import { z } from "zod";

import {
	assignMemberRoleMutation,
	getAllTeamsOptions,
	getOwnMembershipQueryKey,
	getUsersWithTeamsQueryKey,
	listMembershipsOptions,
	listMembershipsQueryKey,
	setMemberHiddenMutation,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceMembership } from "@/api/types.gen";
import {
	WORKSPACE_ROLE_LABELS,
	workspaceMemberRows,
} from "@/components/admin/members/workspace-members";
import { WorkspaceMembersPage } from "@/components/admin/members/WorkspaceMembersPage";
import type { WorkspaceMembersTableView } from "@/components/admin/members/WorkspaceMembersTable";
import { accountName } from "@/components/admin/users/account-labels";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/members")({
	head: workspaceAdminHead("Members"),
	validateSearch: z.object({
		q: z.coerce.string().max(200).optional().catch(undefined),
		team: z.coerce.string().optional().catch(undefined),
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
		adminWorkspaceSlug: workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
		refetch: refetchWorkspace,
	} = useWorkspaceAccess();

	const view: WorkspaceMembersTableView = {
		q: search.q ?? "",
		team: search.team ?? "all",
		sort: search.sort ?? "name",
		desc: search.desc ?? false,
		page: search.page ?? 0,
		size: search.size ?? 10,
	};
	const membersQueryOptions = listMembershipsOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
		query: {
			q: hasText(view.q.trim()) ? view.q.trim() : undefined,
			team: view.team === "all" ? undefined : view.team,
			page: view.page,
			size: view.size,
			sort: [`${view.sort},${view.desc ? "desc" : "asc"}`],
		},
	});
	const membersQuery = useQuery({
		...membersQueryOptions,
		enabled: hasText(workspaceSlug),
	});
	useQueryReadRefusal(membersQueryOptions.queryKey, membersQuery.isError, membersQuery.error);

	const teamsQueryOptions = getAllTeamsOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
	});
	const teamsQuery = useQuery({
		...teamsQueryOptions,
		enabled: Boolean(workspaceSlug) && (teamsQueryOptions.enabled ?? true),
	});
	useQueryReadRefusal(teamsQueryOptions.queryKey, teamsQuery.isError, teamsQuery.error);

	const queryClient = useQueryClient();
	const membersKey = listMembershipsQueryKey({
		path: { workspaceSlug: workspaceSlug ?? "" },
	});
	const toggleHidden = useMutation({
		...setMemberHiddenMutation(),
		onSuccess: (_data, variables) => {
			void queryClient.invalidateQueries({ queryKey: membersKey });
			void queryClient.invalidateQueries({
				queryKey: getUsersWithTeamsQueryKey({
					path: { workspaceSlug: variables.path.workspaceSlug },
				}),
			});
		},
		onError: (error) => {
			toast.error("We could not update visibility in workspace activity", {
				description: problemDetailOf(error),
			});
		},
	});
	const assignRole = useMutation({
		...assignMemberRoleMutation(),
		onSuccess: (updated) => {
			toast.success(
				`${accountName(updated.account)}’s role is now ${WORKSPACE_ROLE_LABELS[updated.role]}`,
			);
			void queryClient.invalidateQueries({ queryKey: membersKey });
			// The admin may have changed their own role, which the navigation and guards read.
			void queryClient.invalidateQueries({
				queryKey: getOwnMembershipQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
			});
		},
		onError: (error) => {
			toast.error("We could not change the role", { description: problemDetailOf(error) });
		},
	});

	const { mutate: changeHidden } = toggleHidden;
	const { mutate: changeRole } = assignRole;
	const handleToggleHidden = (accountId: string, hidden: boolean) => {
		if (!hasText(workspaceSlug)) {
			return;
		}
		changeHidden({ path: { workspaceSlug, accountId }, body: { hidden } });
	};

	const handleRoleChange = (accountId: string, role: WorkspaceMembership["role"]) => {
		if (!hasText(workspaceSlug)) {
			return;
		}
		changeRole({ path: { workspaceSlug }, body: { accountId, role } });
	};

	const teams = teamsQuery.isError ? [] : (teamsQuery.data ?? []);
	const members = workspaceMemberRows(membersQuery.data?.content ?? []);
	const isLoading = isWorkspaceLoading || membersQuery.isPending;

	if (!hasText(workspaceSlug) && !isWorkspaceLoading && workspaceError === undefined) {
		return <NoWorkspace />;
	}

	return (
		<WorkspaceMembersPage
			members={members}
			rowCount={membersQuery.data?.totalElements ?? 0}
			teams={teams}
			isLoading={isLoading || !hasText(workspaceSlug)}
			error={workspaceError ?? membersQuery.error}
			onRetry={() => {
				refetchWorkspace();
				if (hasText(workspaceSlug)) {
					void membersQuery.refetch();
					void teamsQuery.refetch();
				}
			}}
			teamsError={teamsQuery.error}
			teamsRetrying={teamsQuery.isFetching}
			onRetryTeams={() => {
				void teamsQuery.refetch();
			}}
			onToggleHidden={handleToggleHidden}
			onRoleChange={handleRoleChange}
			pendingRoleAccountId={assignRole.isPending ? assignRole.variables.body.accountId : undefined}
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
