import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { z } from "zod";
import { toast } from "@/lib/toast";

import {
	assignMemberRoleMutation,
	followProviderRoleMutation,
	getAllTeamsOptions,
	getOwnMembershipQueryKey,
	getUsersWithTeamsQueryKey,
	listMembershipsOptions,
	listMembershipsQueryKey,
	removeMemberMutation,
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
import { WorkspaceRemoveMemberDialog } from "@/components/admin/members/WorkspaceRemoveMemberDialog";
import { accountName } from "@/components/admin/users/account-labels";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { getProviderLabel } from "@/lib/provider/provider-labels";
import { pageParam, useSearchState } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import type { WorkspaceRole } from "@/lib/workspace-roles";
import { NoWorkspaceContainer } from "@/routes/-NoWorkspaceContainer";
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

const REMOVE_MEMBER_KEY = ["workspace-members", "remove"] as const;

function AdminMembersContainer() {
	const search = Route.useSearch();
	const params = Route.useParams();
	const setSearch = useSearchState();
	const {
		adminWorkspaceSlug: workspaceSlug,
		isLoading: isWorkspaceLoading,
		error: workspaceError,
		refetch: refetchWorkspace,
		role: readerRole,
		chromeWorkspace,
		administrationWorkspace,
	} = useWorkspaceAccess();
	// Only a GitHub organization or a GitLab group roster sets workspace roles; Slack and Outline set none.
	const providerType = (administrationWorkspace ?? chromeWorkspace)?.providerType;
	const providerLabel =
		providerType === "GITHUB" || providerType === "GITLAB"
			? getProviderLabel(providerType)
			: undefined;
	const { getUserId } = useAuth();
	const [roleChange, setRoleChange] = useState<WorkspaceRoleChange | null>(null);
	// The Role menu the waiting change came from. The dialog opens while focus is still in that menu's
	// closing list, so without it focus would return there.
	const roleChangeTrigger = useRef<HTMLElement>(null);
	const [removal, setRemoval] = useState<WorkspaceMemberRow | null>(null);
	// The Actions menu the removal came from, for the same reason as `roleChangeTrigger`.
	const removalTrigger = useRef<HTMLElement>(null);

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
	const members = workspaceMemberRows(membersQuery.data?.content ?? []);
	const memberOnPage = (accountId: string) =>
		members.find((row) => row.membership.account.id === accountId);

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
	// Making, unmaking or removing an owner needs a recent sign-in; the server says so instead of changing it.
	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const assignRole = useMutation({
		...assignMemberRoleMutation(),
		// A role for a member who left adds them back, so the outcome says that instead.
		onMutate: (variables) => ({
			rejoins: memberOnPage(variables.body.accountId)?.membership.leftAt != null,
		}),
		onSuccess: (updated, _variables, { rejoins }) => {
			toast.success(
				rejoins
					? `${accountName(updated.account)} is back in this workspace`
					: `${accountName(updated.account)}’s role is now ${WORKSPACE_ROLE_LABELS[updated.role]}`,
			);
			void queryClient.invalidateQueries({ queryKey: membersKey });
			if (rejoins) {
				void queryClient.invalidateQueries({
					queryKey: getUsersWithTeamsQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
				});
			}
			// The admin may have changed their own role, which the navigation and guards read.
			void queryClient.invalidateQueries({
				queryKey: getOwnMembershipQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
			});
		},
		onError: (error) => {
			const stepUp = stepUpChallengeOf(error);
			if (stepUp !== undefined) {
				setChallenge(stepUp);
				return;
			}
			toast.error("We could not change the role", { description: problemDetailOf(error) });
		},
	});

	const remove = useMutation({
		...filedUnder(REMOVE_MEMBER_KEY, removeMemberMutation()),
		// The server answers with no body, so the outcome names the member from the row it came from.
		onMutate: (variables) => ({
			name: memberOnPage(variables.path.accountId)?.name ?? "the member",
		}),
		onSuccess: (_data, _variables, { name }) => {
			toast.success(`You removed ${name} from this workspace`);
			void queryClient.invalidateQueries({ queryKey: membersKey });
			void queryClient.invalidateQueries({
				queryKey: getUsersWithTeamsQueryKey({ path: { workspaceSlug: workspaceSlug ?? "" } }),
			});
		},
		onError: (error, _variables, context) => {
			const stepUp = stepUpChallengeOf(error);
			if (stepUp !== undefined) {
				setChallenge(stepUp);
				return;
			}
			// The dialog closed on confirm, so this toast is the only report (ADR 0027).
			toast.error(`We could not remove ${context?.name ?? "the member"}`, {
				description: problemDetailOf(error),
			});
		},
	});

	const followProvider = useMutation({
		...followProviderRoleMutation(),
		onSuccess: (updated) => {
			toast.success(
				`${accountName(updated.account)}’s role now comes from ${providerLabel ?? "the provider"}`,
				{ description: "It changes at the next sync." },
			);
			void queryClient.invalidateQueries({ queryKey: membersKey });
		},
		onError: (error) => {
			toast.error("We could not change where the role comes from", {
				description: problemDetailOf(error),
			});
		},
	});

	const { mutate: changeHidden } = toggleHidden;
	const { mutate: restoreProviderRole } = followProvider;
	const { mutate: removeMember } = remove;
	// Every removal still out, not only the latest: each of those rows waits until its own settles.
	const removingAccountIds = usePendingMutationIds(REMOVE_MEMBER_KEY, (variables) =>
		pathString(variables, "accountId"),
	);
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

	const handleFollowProvider = (member: WorkspaceMemberRow) => {
		if (hasText(workspaceSlug)) {
			restoreProviderRole({ path: { workspaceSlug, accountId: member.membership.account.id } });
		}
	};

	const handleRemove = (member: WorkspaceMemberRow, trigger: HTMLElement) => {
		removalTrigger.current = trigger;
		setRemoval(member);
	};

	const confirmRemoval = (member: WorkspaceMemberRow) => {
		if (hasText(workspaceSlug)) {
			removeMember({ path: { workspaceSlug, accountId: member.membership.account.id } });
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
			rejoins: member.membership.leftAt !== null,
		};
		if (roleChangeAsksFirst(change)) {
			roleChangeTrigger.current = trigger;
			setRoleChange(change);
		} else {
			sendRoleChange(change);
		}
	};

	// Adding back gives the member the role they had, through the same checks as any role change.
	const handleAddBack = (member: WorkspaceMemberRow, trigger: HTMLElement) =>
		handleRoleChange(member, member.membership.role, trigger);

	const teams = teamsQuery.isError ? [] : (teamsQuery.data ?? []);
	const isLoading = isWorkspaceLoading || membersQuery.isPending;

	if (!hasText(workspaceSlug) && !isWorkspaceLoading && workspaceError === undefined) {
		return <NoWorkspaceContainer />;
	}

	return (
		<>
			<WorkspaceMembersPage
				workspaceSlug={params.workspaceSlug}
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
				providerLabel={providerLabel}
				onFollowProvider={handleFollowProvider}
				pendingFollowAccountId={
					followProvider.isPending ? followProvider.variables.path.accountId : undefined
				}
				onRemove={handleRemove}
				removingAccountIds={removingAccountIds}
				onAddBack={handleAddBack}
				readerAccountId={getUserId()}
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
			<WorkspaceRemoveMemberDialog
				member={removal}
				providerLabel={providerLabel}
				onConfirm={confirmRemoval}
				onClose={() => setRemoval(null)}
				finalFocus={removalTrigger}
			/>
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setChallenge(undefined);
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</>
	);
}
