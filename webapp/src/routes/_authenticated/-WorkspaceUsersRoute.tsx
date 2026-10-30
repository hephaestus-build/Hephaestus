import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeftIcon, Users } from "lucide-react";

import { getUserViewUser } from "@/api/sdk.gen";
import type { UserViewAccount } from "@/api/types.gen";
import { accountName } from "@/components/admin/users/account-labels";
import type { UserViewSearch } from "@/components/admin/users/user-view-search";
import { UserViewDialog } from "@/components/admin/users/UserViewDialog";
import { UserViewUsersTable } from "@/components/admin/users/UserViewUsersTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { useUserViewUsers, useUserViewWorkspace } from "@/hooks/use-user-view";
import { problemDetailOf, stepUpChallengeOf } from "@/lib/problem-detail";
import { pageParam, useSearchPatch } from "@/lib/search-params";
import { currentUserQueryOptions } from "@/runtime/auth/guard";
import { startUserView } from "@/runtime/user-view/session";

export function WorkspaceUsersRoute({
	workspaceSlug,
	search,
}: {
	workspaceSlug: string;
	search: UserViewSearch;
}) {
	const { page: searchPage, account: selectedId } = search;
	const updateSearch = useSearchPatch<UserViewSearch>();
	const page = searchPage ?? 0;
	const workspace = useUserViewWorkspace(workspaceSlug);
	const users = useUserViewUsers(workspaceSlug, page);
	const operatorAccountId = useQuery(currentUserQueryOptions()).data?.id;
	const target =
		users.status === "ready"
			? users.users.find((user) => user.accountId === selectedId)
			: undefined;

	useClampedPage(page, users.status === "ready" ? users.totalPages : undefined, (next) =>
		updateSearch({ page: pageParam(next) }),
	);

	return (
		<PageLayout>
			<PageHeader
				icon={<Users />}
				title="View as user"
				description={`Open the app as a user in ${workspace.status === "ready" ? workspace.displayName : workspaceSlug}. All supported pages are read-only.`}
			/>
			<Link to="/admin/workspaces" className={buttonVariants({ variant: "outline" })}>
				<ArrowLeftIcon aria-hidden />
				Back to workspaces
			</Link>
			<UserViewUsersTable
				state={users}
				page={page}
				onPageChange={(next) => updateSearch({ page: pageParam(next) })}
				onView={(user) => updateSearch({ account: user.accountId })}
			/>
			{target && operatorAccountId !== undefined && (
				<UserViewEntry
					key={`${workspaceSlug}:${target.accountId}`}
					workspaceSlug={workspaceSlug}
					workspaceName={workspace.status === "ready" ? workspace.displayName : workspaceSlug}
					user={target}
					operatorAccountId={operatorAccountId}
					onClose={() => updateSearch({ account: undefined })}
				/>
			)}
		</PageLayout>
	);
}

function UserViewEntry({
	workspaceSlug,
	workspaceName,
	user,
	operatorAccountId,
	onClose,
}: {
	workspaceSlug: string;
	workspaceName: string;
	user: UserViewAccount;
	operatorAccountId: number;
	onClose: () => void;
}) {
	const openView = useMutation({
		mutationFn: async (reason: string) => {
			const { data } = await getUserViewUser({
				path: { workspaceSlug, accountId: user.accountId },
				headers: { "X-User-View-Reason": encodeURIComponent(reason) },
				throwOnError: true,
			});
			return data;
		},
	});
	const challenge = stepUpChallengeOf(openView.error);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	return (
		<>
			{challenge === undefined && (
				<UserViewDialog
					name={accountName(user.account)}
					isPending={openView.isPending}
					error={openView.isError ? problemDetailOf(openView.error) : undefined}
					onClose={onClose}
					onConfirm={(reason) =>
						openView.mutate(reason, {
							onSuccess: (data) =>
								startUserView({
									operatorAccountId,
									workspaceSlug,
									workspaceName,
									accountId: data.accountId,
									providerLogin: data.providerLogin,
									name: accountName(data.account),
									unclaimed: data.account.status === "UNCLAIMED",
									reason,
								}),
						})
					}
				/>
			)}
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						openView.reset();
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
