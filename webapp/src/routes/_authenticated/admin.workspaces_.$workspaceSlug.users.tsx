import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon, Users } from "lucide-react";
import { useState } from "react";
import { z } from "zod";

import { getUserViewUser } from "@/api/sdk.gen";
import { UserViewDialog } from "@/components/admin/users/UserViewDialog";
import { UserViewUsersTable } from "@/components/admin/users/UserViewUsersTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { useUserViewUsers, useUserViewWorkspace } from "@/hooks/use-user-view";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, stepUpChallengeOf } from "@/lib/problem-detail";
import { pageParam, useSearchPatch } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";
import { startUserView } from "@/runtime/user-view/session";

const searchSchema = z.object({
	page: z.number().int().nonnegative().optional().catch(undefined),
	user: z.number().int().positive().optional().catch(undefined),
});
type Search = z.infer<typeof searchSchema>;

export const Route = createFileRoute("/_authenticated/admin/workspaces_/$workspaceSlug/users")({
	head: instanceAdminHead("View as user"),
	validateSearch: searchSchema,
	component: WorkspaceUsersRoute,
});

function WorkspaceUsersRoute() {
	const { getUserId } = useAuth();
	const { workspaceSlug } = Route.useParams();
	const { page: searchPage, user: selectedId } = Route.useSearch();
	const updateSearch = useSearchPatch<Search>();
	const [viewError, setViewError] = useState<unknown>();
	const [pending, setPending] = useState(false);
	const page = searchPage ?? 0;
	const workspace = useUserViewWorkspace(workspaceSlug);
	const users = useUserViewUsers(workspaceSlug, page);
	const target =
		users.status === "ready" ? users.users.find((user) => user.userId === selectedId) : undefined;
	const challenge = stepUpChallengeOf(viewError);
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const openView = async (reason: string) => {
		if (!target) {
			return;
		}
		setPending(true);
		setViewError(undefined);
		try {
			const { data } = await getUserViewUser({
				path: { workspaceSlug, userId: target.userId },
				headers: { "X-User-View-Reason": encodeURIComponent(reason) },
				throwOnError: true,
			});
			startUserView({
				operatorAccountId: Number(getUserId()),
				workspaceSlug,
				userId: data.userId,
				login: data.login,
				name: data.name ?? data.login,
				hasAccount: data.accountId != null,
				reason,
			});
		} catch (error) {
			setViewError(error);
			setPending(false);
		}
	};

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
				onView={(user) => updateSearch({ user: user.userId })}
			/>
			{target && (
				<UserViewDialog
					name={target.name ?? target.login}
					isPending={pending}
					error={
						challenge === undefined && viewError != null ? problemDetailOf(viewError) : undefined
					}
					onClose={() => {
						setViewError(undefined);
						updateSearch({ user: undefined });
					}}
					onConfirm={(reason) => {
						void openView(reason);
					}}
				/>
			)}
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setViewError(undefined);
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</PageLayout>
	);
}
