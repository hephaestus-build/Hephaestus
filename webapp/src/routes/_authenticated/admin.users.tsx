import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ShieldCheck, ShieldOff, UserCog, Users } from "lucide-react";
import { useDeferredValue, useEffect, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import {
	adminListUsersInfiniteOptions,
	adminListUsersQueryKey,
	adminRevokeUserSessionsMutation,
	adminUpdateUserMutation,
	impersonateMutation,
} from "@/api/@tanstack/react-query.gen";
import type { AdminAccountView } from "@/api/types.gen";
import { AdminUsersTable } from "@/components/admin/users/AdminUsersTable";
import { ChangeRoleDialog } from "@/components/admin/users/ChangeRoleDialog";
import { ImpersonateDialog } from "@/components/admin/users/ImpersonateDialog";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { useAuth } from "@/integrations/auth/AuthContext";
import { loadedPages } from "@/integrations/tanstack-query/spring-page";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";

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
	const currentUserId = userId != null ? Number(userId) : undefined;

	const deferredSearch = useDeferredValue(search);

	const [roleTarget, setRoleTarget] = useState<DialogTarget>(null);
	const [impersonateTarget, setImpersonateTarget] = useState<DialogTarget>(null);
	const [signOutTarget, setSignOutTarget] = useState<DialogTarget>(null);

	const listQuery = useInfiniteQuery({
		...adminListUsersInfiniteOptions({ query: { size: PAGE_SIZE } }),
		initialPageParam: 0,
		getNextPageParam: (lastPage: AdminAccountView[], allPages: AdminAccountView[][]) =>
			lastPage.length === PAGE_SIZE ? allPages.length : undefined,
	});

	const allUsers: AdminAccountView[] = loadedPages(listQuery.data).flat();

	const { hasNextPage, isFetchingNextPage, fetchNextPage } = listQuery;
	useEffect(() => {
		if (deferredSearch.trim() && hasNextPage && !isFetchingNextPage) {
			void fetchNextPage();
		}
	}, [deferredSearch, hasNextPage, isFetchingNextPage, fetchNextPage]);

	const term = deferredSearch.trim().toLowerCase();
	const filteredUsers = term
		? allUsers.filter((u) =>
				[u.displayName, u.primaryEmail, u.appRole, u.status, String(u.id ?? "")]
					.filter(Boolean)
					.some((field) => field?.toLowerCase().includes(term)),
			)
		: allUsers;

	const invalidateList = () =>
		queryClient.invalidateQueries({
			queryKey: adminListUsersQueryKey({ query: { size: PAGE_SIZE } }),
		});

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	/**
	 * A refusal that asks for a fresh sign-in replaces the dialog the action was started from, so the
	 * ask never lands on top of a second modal focus trap. It reports `true` when it took the error,
	 * leaving the caller to handle the refusals an operator can actually read.
	 */
	const openConfirmAccess = (error: unknown): boolean => {
		const stepUp = stepUpChallengeOf(error);
		if (!stepUp) return false;
		setRoleTarget(null);
		setImpersonateTarget(null);
		setSignOutTarget(null);
		setChallenge(stepUp);
		return true;
	};

	const updateRole = useMutation({
		...adminUpdateUserMutation(),
		onSuccess: async (_data, variables) => {
			await invalidateList();
			toast.success(`Role updated to ${variables.body.appRole}.`);
			setRoleTarget(null);
		},
		onError: openConfirmAccess,
	});

	const impersonate = useMutation({
		...impersonateMutation(),
		onError: openConfirmAccess,
	});

	const forceSignOut = useMutation({
		...adminRevokeUserSessionsMutation(),
		onSuccess: (data) => {
			const count = data.revoked;
			toast.success(
				count === 0
					? "No active sessions to sign out."
					: `Signed out — revoked ${count} session${count === 1 ? "" : "s"}.`,
			);
			setSignOutTarget(null);
		},
		onError: (error) => {
			if (openConfirmAccess(error)) return;
			toast.error(problemDetailOf(error, "Couldn't sign the user out."));
			setSignOutTarget(null);
		},
	});

	const handleConfirmSignOut = () => {
		const id = signOutTarget?.user.id;
		if (id == null) return;
		forceSignOut.mutate({ path: { id } });
	};

	const handleConfirmRole = (user: AdminAccountView, nextRole: string) => {
		if (user.id == null) return;
		updateRole.mutate({ path: { id: user.id }, body: { appRole: nextRole } });
	};

	const handleConfirmImpersonate = (user: AdminAccountView, reason: string) => {
		if (user.id == null) return;
		impersonate.mutate(
			{ body: { targetAccountId: user.id, reason } },
			{
				onSuccess: () => {
					setImpersonateTarget(null);
					window.location.assign("/");
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

			<div className="relative w-full sm:max-w-sm">
				<Label htmlFor="admin-users-search" className="sr-only">
					Search users
				</Label>
				<Users className="absolute left-3 top-2.5 size-4 text-muted-foreground" aria-hidden />
				<Input
					id="admin-users-search"
					type="search"
					placeholder="Search by name, email, role, or status…"
					value={search}
					onChange={(event) =>
						void navigate({
							search: { q: event.target.value || undefined },
							replace: true,
						})
					}
					className="pl-9"
				/>
			</div>

			<AdminUsersTable
				users={filteredUsers}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				hasSearch={deferredSearch.trim().length > 0}
				totalLoaded={allUsers.length}
				currentUserId={currentUserId}
				hasNextPage={listQuery.hasNextPage}
				isFetchingNextPage={listQuery.isFetchingNextPage}
				onLoadMore={() => void listQuery.fetchNextPage()}
				onChangeRole={(user) => {
					updateRole.reset();
					setRoleTarget({ user });
				}}
				onImpersonate={(user) => {
					impersonate.reset();
					setImpersonateTarget({ user });
				}}
				onForceSignOut={(user) => setSignOutTarget({ user })}
			/>

			<ChangeRoleDialog
				icon={roleTarget?.user.appRole === "APP_ADMIN" ? ShieldOff : ShieldCheck}
				user={roleTarget?.user ?? null}
				isPending={updateRole.isPending}
				errorMessage={
					updateRole.isError
						? problemDetailOf(updateRole.error, "Couldn't update the role.")
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

			<ImpersonateDialog
				user={impersonateTarget?.user ?? null}
				isPending={impersonate.isPending}
				errorMessage={
					impersonate.isError
						? problemDetailOf(impersonate.error, "Couldn't start impersonation.")
						: undefined
				}
				onOpenChange={(open) => {
					if (!open) {
						setImpersonateTarget(null);
						impersonate.reset();
					}
				}}
				onConfirm={handleConfirmImpersonate}
			/>

			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) setChallenge(undefined);
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
							Force sign-out {signOutTarget?.user.displayName ?? "this user"}?
						</AlertDialogTitle>
						<AlertDialogDescription>
							This revokes all of the account's active sessions immediately — they'll have to sign
							in again, and any in-progress impersonation of this account ends. This can't be
							undone.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={forceSignOut.isPending}
							onClick={handleConfirmSignOut}
						>
							{forceSignOut.isPending ? "Signing out…" : "Force sign-out"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</PageLayout>
	);
}
