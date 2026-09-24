import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { z } from "zod";

import { getUserViewUser } from "@/api/sdk.gen";
import type { UserViewUser } from "@/api/types.gen";
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
	const { workspaceSlug } = Route.useParams();
	const { page: searchPage, user: selectedId } = Route.useSearch();
	const updateSearch = useSearchPatch<Search>();
	const page = searchPage ?? 0;
	const workspace = useUserViewWorkspace(workspaceSlug);
	const users = useUserViewUsers(workspaceSlug, page);
	const target =
		users.status === "ready" ? users.users.find((user) => user.userId === selectedId) : undefined;

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
				<UserViewEntry
					key={`${workspaceSlug}:${target.userId}`}
					workspaceSlug={workspaceSlug}
					user={target}
					onClose={() => updateSearch({ user: undefined })}
				/>
			)}
		</PageLayout>
	);
}

function UserViewEntry({
	workspaceSlug,
	user,
	onClose,
}: {
	workspaceSlug: string;
	user: UserViewUser;
	onClose: () => void;
}) {
	const { getUserId } = useAuth();
	const [failure, setFailure] = useState<unknown>();
	const [pending, setPending] = useState(false);
	const request = useRef<AbortController | null>(null);
	const challenge = stepUpChallengeOf(failure);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	useEffect(() => () => request.current?.abort(), []);

	const openView = async (reason: string) => {
		setPending(true);
		setFailure(undefined);
		const controller = new AbortController();
		request.current = controller;
		try {
			const { data } = await getUserViewUser({
				path: { workspaceSlug, userId: user.userId },
				headers: { "X-User-View-Reason": encodeURIComponent(reason) },
				signal: controller.signal,
				throwOnError: true,
			});
			if (controller.signal.aborted) {
				return;
			}
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
			if (controller.signal.aborted) {
				return;
			}
			setFailure(error);
			setPending(false);
		}
	};

	return (
		<>
			{challenge === undefined && (
				<UserViewDialog
					name={user.name ?? user.login}
					isPending={pending}
					error={failure == null ? undefined : problemDetailOf(failure)}
					onClose={() => {
						request.current?.abort();
						onClose();
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
						setFailure(undefined);
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
