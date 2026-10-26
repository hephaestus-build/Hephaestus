import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useRef, useState } from "react";
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
import {
	assignableWorkspaceRoles,
	roleChangeAsksFirst,
	WORKSPACE_ROLE_LABELS,
	type WorkspaceMemberRow,
	type WorkspaceRoleChange,
	workspaceMemberRows,
} from "@/components/admin/members/workspace-members";
import { WorkspaceChangeRoleDialog } from "@/components/admin/members/WorkspaceChangeRoleDialog";
import { WorkspaceMembersPage } from "@/components/admin/members/WorkspaceMembersPage";
import type { WorkspaceMembersTableView } from "@/components/admin/members/WorkspaceMembersTable";
import { accountName } from "@/components/admin/users/account-labels";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { pageParam, useSearchState } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import type { WorkspaceRole } from "@/lib/workspace-roles";
import { useAuth } from "@/runtime/auth/AuthContext";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
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

/** The view as the URL holds it: a value at its default leaves the address. */
function membersSearch(view: WorkspaceMembersTableView) {
	return {
		q: hasText(view.q) ? view.q : undefined,
		team: view.team === "all" ? undefined : view.team,
		sort: view.sort === "name" ? undefined : view.sort,
		desc: view.desc ? true : undefined,
		page: pageParam(view.page),
		size: view.size === 10 ? undefined : view.size,
	};
}

function AdminMembersContainer() {
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const {
		adminWorkspaceSlug: workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
		refetch: refetchWorkspace,
		role: readerRole,
	} = useWorkspaceAccess();
	const { getUserId } = useAuth();
	const [roleChange, setRoleChange] = useState<WorkspaceRoleChange | null>(null);
	// The Role menu the waiting change came from. The dialog opens while focus is still in that menu's
	// closing list, so without it focus would return there.
	const roleChangeTrigger = useRef<HTMLElement>(null);

	const view: WorkspaceMembersTableView = {
		q: search.q ?? "",
		team: search.team ?? "all",
		sort: search.sort ?? "name",
		desc: search.desc ?? false,
		page: search.page ?? 0,
		size: search.size ?? 10,
	};
	const team = view.team === "all" ? undefined : view.team;
	const membersQueryOptions = listMembershipsOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
		query: {
			q: hasText(view.q.trim()) ? view.q.trim() : undefined,
			team,
			page: view.page,
			size: view.size,
			sort: [`${view.sort},${view.desc ? "desc" : "asc"}`],
		},
	});
	const membersQuery = useQuery({
		...membersQueryOptions,
		enabled: hasText(workspaceSlug),
		// A new search, sort or page of the same workspace and team keeps the rows on screen until
		// it answers; another workspace's or team's members never stand in.
		placeholderData: keepSameSubject({ workspaceSlug: workspaceSlug ?? "", team }),
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
	// `mutate` is stable and the mutation result is not. A role handler that closed over the result
	// would change every render, so the table would rebuild its columns and close an open role list.
	const { mutate: changeRole } = assignRole;
	const handleToggleHidden = (accountId: string, hidden: boolean) => {
		if (!hasText(workspaceSlug)) {
			return;
		}
		changeHidden({ path: { workspaceSlug, accountId }, body: { hidden } });
	};

	const sendRoleChange = ({ accountId, to }: WorkspaceRoleChange) => {
		if (hasText(workspaceSlug)) {
			changeRole({ path: { workspaceSlug }, body: { accountId, role: to } });
		}
	};

	const handleRoleChange = (
		member: WorkspaceMemberRow,
		role: WorkspaceRole,
		trigger: HTMLElement,
	) => {
		const accountId = member.membership.account.id;
		const change: WorkspaceRoleChange = {
			accountId,
			name: member.name,
			from: member.membership.role,
			to: role,
			own: accountId === getUserId(),
		};
		if (roleChangeAsksFirst(change)) {
			roleChangeTrigger.current = trigger;
			setRoleChange(change);
		} else {
			sendRoleChange(change);
		}
	};

	const teams = teamsQuery.isError ? [] : (teamsQuery.data ?? []);
	const members = workspaceMemberRows(membersQuery.data?.content ?? []);
	const isLoading = isWorkspaceLoading || membersQuery.isPending;

	if (!hasText(workspaceSlug) && !isWorkspaceLoading && workspaceError === undefined) {
		return <NoWorkspace />;
	}

	return (
		<>
			<WorkspaceMembersPage
				members={members}
				rowCount={membersQuery.data?.totalElements ?? 0}
				teams={teams}
				isLoading={isLoading || !hasText(workspaceSlug)}
				updating={membersQuery.isPlaceholderData}
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
				assignableRoles={assignableWorkspaceRoles(readerRole)}
				pendingRoleAccountId={
					assignRole.isPending ? assignRole.variables.body.accountId : undefined
				}
				view={view}
				onViewChange={(patch) => {
					void setSearch(
						(previous) =>
							membersSearch({
								q: "",
								team: "all",
								sort: "name" as const,
								desc: false,
								page: 0,
								size: 10,
								...previous,
								...patch,
							}),
						{ replace: true },
					);
				}}
				renderPageLink={(page, props) => (
					<Link to="." search={membersSearch({ ...view, page })} resetScroll={false} {...props} />
				)}
			/>
			<WorkspaceChangeRoleDialog
				change={roleChange}
				onConfirm={sendRoleChange}
				onClose={() => setRoleChange(null)}
				finalFocus={roleChangeTrigger}
			/>
		</>
	);
}
