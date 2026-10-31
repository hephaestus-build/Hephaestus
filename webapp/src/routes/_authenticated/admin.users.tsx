import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ShieldCheck, ShieldOff, UserCog, Users } from "lucide-react";
import {
	useDeferredValue,
	useEffect,
	useEffectEvent,
	useLayoutEffect,
	useRef,
	useState,
} from "react";
import { z } from "zod";
import { toast } from "@/lib/toast";

import {
	adminListUsersInfiniteOptions,
	adminRevokeUserSessionsMutation,
	adminUpdateUserMutation,
} from "@/api/@tanstack/react-query.gen";
import type { AdminAccountView } from "@/api/types.gen";
import {
	ACCOUNT_STATUS_LABELS,
	APP_ROLE_LABELS,
	type AppRole,
} from "@/components/admin/users/account-labels";
import { AdminUsersTable } from "@/components/admin/users/AdminUsersTable";
import { ChangeRoleDialog } from "@/components/admin/users/ChangeRoleDialog";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { useAuth } from "@/runtime/auth/AuthContext";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const PAGE_SIZE = 25;

export const Route = createFileRoute("/_authenticated/admin/users")({
	head: instanceAdminHead("Users"),
	validateSearch: z.object({ q: z.string().max(200).optional().catch(undefined) }),
	component: AdminUsersPage,
});

type DialogTarget = { user: AdminAccountView } | null;

function AdminUsersPage() {
	const queryClient = useQueryClient();
	const { getUserId } = useAuth();
	const navigate = useNavigate({ from: Route.fullPath });
	const search = Route.useSearch().q ?? "";
	const userId = getUserId();
	const currentUserId = userId == null ? undefined : Number(userId);

	const deferredSearch = useDeferredValue(search);

	const [roleTarget, setRoleTarget] = useState<DialogTarget>(null);
	const [signOutTarget, setSignOutTarget] = useState<DialogTarget>(null);
	// The role dialog on screen, read when a change settles (ADR 0027). A layout effect updates it in
	// the commit itself, so a change that settles right after a dismissal does not see the old one.
	const roleOnScreen = useRef<DialogTarget>(null);
	useLayoutEffect(() => {
		roleOnScreen.current = roleTarget;
	}, [roleTarget]);

	const listOptions = adminListUsersInfiniteOptions({ query: { size: PAGE_SIZE } });
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: 0,
		getNextPageParam: (lastPage: AdminAccountView[], allPages: AdminAccountView[][]) =>
			lastPage.length === PAGE_SIZE ? allPages.length : undefined,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const list = infiniteListState(listQuery, (pages) => ({ users: pages.flat() }));
	const loaded = list.status === "ready" ? list : undefined;
	const allUsers = loaded?.users ?? [];

	// A refused read takes back the accounts it listed, so an open action on one of them goes with
	// them. Clearing the targets, rather than hiding them, keeps a later read from bringing them back.
	if (
		list.status === "error" &&
		!queryErrorIsRetryable(list.error) &&
		(roleTarget !== null || signOutTarget !== null)
	) {
		setRoleTarget(null);
		setSignOutTarget(null);
	}

	const term = deferredSearch.trim().toLowerCase();

	// Search filters loaded rows, so it reads every page. It stops at a failure until the reader
	// retries, and waits for a refresh of the loaded pages to finish.
	const searchContinues =
		term !== "" &&
		loaded !== undefined &&
		loaded.hasMore &&
		!loaded.isLoadingMore &&
		loaded.isRefreshing !== true &&
		loaded.loadMoreError === undefined &&
		loaded.refreshFailure === undefined;
	const continueSearch = useEffectEvent(() => loaded?.onLoadMore());
	useEffect(() => {
		if (searchContinues) {
			continueSearch();
		}
	}, [searchContinues]);

	const filteredUsers = term
		? allUsers.filter((u) =>
				[
					u.displayName,
					u.primaryEmail,
					APP_ROLE_LABELS[u.appRole],
					ACCOUNT_STATUS_LABELS[u.status],
					String(u.id ?? ""),
				]
					.filter(Boolean)
					.some((field) => field?.toLowerCase().includes(term) === true),
			)
		: allUsers;

	const invalidateList = async () =>
		queryClient.invalidateQueries({
			queryKey: listOptions.queryKey,
			exact: true,
		});

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	// Close the initiating dialog before step-up to avoid stacking modal focus traps.
	const openConfirmAccess = (error: unknown): boolean => {
		const stepUp = stepUpChallengeOf(error);
		if (!stepUp) {
			return false;
		}
		setRoleTarget(null);
		setSignOutTarget(null);
		setChallenge(stepUp);
		return true;
	};

	const updateRole = useMutation({
		...adminUpdateUserMutation(),
		onSuccess: async () => {
			await invalidateList();
			setRoleTarget(null);
		},
		onError: (error, variables) => {
			if (openConfirmAccess(error)) {
				return;
			}
			// An open dialog shows the refusal itself; one the reader closed while it saved cannot.
			if (roleOnScreen.current?.user.id !== variables.path.id) {
				toast.error(problemDetailOf(error, "We could not update the role. Try again."));
			}
		},
	});

	const forceSignOut = useMutation({
		...adminRevokeUserSessionsMutation(),
		onSuccess: (data) => {
			const count = data.revoked;
			toast.success(
				count === 0
					? "No active sessions to sign out."
					: `Revoked ${count} session${count === 1 ? "" : "s"}.`,
			);
			setSignOutTarget(null);
		},
		onError: (error) => {
			if (openConfirmAccess(error)) {
				return;
			}
			toast.error(problemDetailOf(error, "We could not sign the user out. Try again."));
			setSignOutTarget(null);
		},
	});

	const handleConfirmSignOut = () => {
		const id = signOutTarget?.user.id;
		if (id == null) {
			return;
		}
		forceSignOut.mutate({ path: { id } });
	};

	const handleConfirmRole = (user: AdminAccountView, nextRole: AppRole) => {
		if (user.id == null) {
			return;
		}
		updateRole.mutate(
			{ path: { id: user.id }, body: { appRole: nextRole } },
			{
				onSuccess: () => {
					toast.success(`Role updated to ${APP_ROLE_LABELS[nextRole]}`);
				},
			},
		);
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<UserCog />}
				title="Users"
				description="Manage application accounts, roles, sessions, and support access."
			/>

			<InputGroup className="w-full sm:max-w-sm">
				<Label htmlFor="admin-users-search" className="sr-only">
					Search users
				</Label>
				<InputGroupAddon>
					<Users aria-hidden />
				</InputGroupAddon>
				<InputGroupInput
					id="admin-users-search"
					type="search"
					placeholder="Search by name, email, role, or status…"
					value={search}
					onChange={(event) => {
						void navigate({
							search: { q: event.target.value || undefined },
							replace: true,
						});
					}}
				/>
			</InputGroup>

			<AdminUsersTable
				users={filteredUsers}
				isLoading={list.status === "loading"}
				loadFailure={
					list.status === "error"
						? { error: list.error, onRetry: list.onRetry, pending: listQuery.isFetching }
						: undefined
				}
				hasSearch={term !== ""}
				totalLoaded={allUsers.length}
				currentUserId={currentUserId}
				hasNextPage={loaded?.hasMore ?? false}
				isFetchingNextPage={loaded?.isLoadingMore ?? false}
				loadMoreError={loaded?.loadMoreError}
				refreshFailure={loaded?.refreshFailure}
				onLoadMore={() => loaded?.onLoadMore()}
				onChangeRole={(user) => {
					updateRole.reset();
					setRoleTarget({ user });
				}}
				onForceSignOut={(user) => setSignOutTarget({ user })}
			/>

			<ChangeRoleDialog
				icon={roleTarget?.user.appRole === "APP_ADMIN" ? ShieldOff : ShieldCheck}
				user={roleTarget?.user ?? null}
				isPending={updateRole.isPending}
				errorMessage={
					updateRole.isError
						? problemDetailOf(updateRole.error, "We could not update the role. Try again.")
						: undefined
				}
				onOpenChange={(open) => {
					if (!open) {
						setRoleTarget(null);
						updateRole.reset();
					}
				}}
				onConfirm={handleConfirmRole}
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

			<AlertDialog
				open={signOutTarget !== null}
				onOpenChange={(open) => {
					if (!open) {
						setSignOutTarget(null);
						forceSignOut.reset();
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>
							Force sign out {signOutTarget?.user.displayName ?? "this user"}?
						</AlertDialogTitle>
						<AlertDialogDescription>
							This revokes all of the account’s active sessions immediately, so they will have to
							sign in again. You cannot undo this.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={forceSignOut.isPending}
							onClick={handleConfirmSignOut}
						>
							{forceSignOut.isPending ? "Signing out…" : "Force sign out"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</PageLayout>
	);
}
