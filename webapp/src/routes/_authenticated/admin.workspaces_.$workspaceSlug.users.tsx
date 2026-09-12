import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeftIcon, Users } from "lucide-react";
import { useState } from "react";
import { z } from "zod";

import type { UserViewUser } from "@/api/types.gen";
import { UserViewBanner } from "@/components/admin/users/UserViewBanner";
import { UserViewConversations } from "@/components/admin/users/UserViewConversations";
import { UserViewConversationThread } from "@/components/admin/users/UserViewConversationThread";
import { UserViewDialog } from "@/components/admin/users/UserViewDialog";
import { UserViewNotices } from "@/components/admin/users/UserViewNotices";
import { UserViewPractices } from "@/components/admin/users/UserViewPractices";
import { UserViewUsersTable } from "@/components/admin/users/UserViewUsersTable";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import {
	USER_VIEW_RUNS_PAGE_SIZE,
	type ViewedUser,
	type UserViewWorkspaceState,
	useUserPracticeView,
	useUserViewConversation,
	useUserViewConversations,
	useUserViewGroup,
	useUserViewUsers,
	useUserViewWorkspace,
} from "@/hooks/use-user-view";
import { instanceAdminHead } from "@/lib/page-title";
import { stepUpChallengeOf } from "@/lib/problem-detail";
import { pageParam, useSearchPatch } from "@/lib/search-params";

const userViewSearchSchema = z.object({
	page: z.number().int().nonnegative().optional().catch(undefined),
	user: z.number().int().positive().optional().catch(undefined),
	section: z.enum(["practices", "conversations"]).optional().catch(undefined),
	group: z.string().optional().catch(undefined),
	practice: z.string().optional().catch(undefined),
	observation: z.string().optional().catch(undefined),
	thread: z.string().optional().catch(undefined),
	threadPage: z.number().int().nonnegative().optional().catch(undefined),
});

type UserViewSearch = z.infer<typeof userViewSearchSchema>;

const DRILL_IN_RESET: Partial<UserViewSearch> = {
	section: undefined,
	group: undefined,
	practice: undefined,
	observation: undefined,
	thread: undefined,
	threadPage: undefined,
};

export const Route = createFileRoute("/_authenticated/admin/workspaces_/$workspaceSlug/users")({
	head: instanceAdminHead("View as user"),
	validateSearch: userViewSearchSchema,
	remountDeps: ({ params, search }) => [params.workspaceSlug, search.user],
	component: WorkspaceUsersRoute,
});

interface ActiveView {
	user: UserViewUser;
	reason: string;
}

function WorkspaceUsersRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const updateSearch = useSearchPatch<UserViewSearch>();

	// Component state on purpose: the reason must not reach the URL, the history or Sentry. The
	// user id does, so a sign-in round trip lands back on them and asks for the reason again.
	const [view, setView] = useState<ActiveView>();

	const workspace = useUserViewWorkspace(workspaceSlug);
	const workspaceName = workspace.status === "ready" ? workspace.displayName : workspaceSlug;
	const page = search.page ?? 0;
	const users = useUserViewUsers(workspaceSlug, page);
	useClampedPage(page, users.status === "ready" ? users.totalPages : undefined, (next) =>
		updateSearch({ page: pageParam(next) }),
	);
	const target =
		!view && users.status === "ready"
			? users.users.find((candidate) => candidate.userId === search.user)
			: undefined;

	return (
		<PageLayout>
			<PageHeader
				icon={<Users />}
				title="View as user"
				description={`View a user's private practices and conversations in ${workspaceName}, read-only. Instance administrators only.`}
			/>
			<Link to="/admin/workspaces" className={buttonVariants({ variant: "outline" })}>
				<ArrowLeftIcon aria-hidden />
				Back to workspaces
			</Link>
			{view ? (
				<UserViewPanel
					viewed={{ workspaceSlug, userId: view.user.userId, reason: view.reason }}
					user={view.user}
					workspace={workspace}
					search={search}
					onSearchChange={updateSearch}
					onExit={() => {
						setView(undefined);
						updateSearch({ ...DRILL_IN_RESET, user: undefined });
					}}
				/>
			) : (
				<UserViewUsersTable
					state={users}
					page={page}
					onPageChange={(next) => updateSearch({ page: pageParam(next) })}
					onView={(candidate) => updateSearch({ user: candidate.userId })}
				/>
			)}
			{target && (
				<UserViewDialog
					name={target.name ?? target.login}
					onClose={() => updateSearch({ user: undefined })}
					onConfirm={(reason) => {
						setView({ user: target, reason });
						updateSearch(DRILL_IN_RESET);
					}}
				/>
			)}
		</PageLayout>
	);
}

interface UserViewPanelProps {
	viewed: ViewedUser;
	user: UserViewUser;
	workspace: UserViewWorkspaceState;
	search: UserViewSearch;
	onSearchChange: (patch: Partial<UserViewSearch>) => void;
	onExit: () => void;
}

function UserViewPanel({
	viewed,
	user,
	workspace,
	search,
	onSearchChange,
	onExit,
}: UserViewPanelProps) {
	const section = search.section ?? "practices";
	const selection = search.group
		? { groupSlug: search.group, practiceSlug: search.practice, observationId: search.observation }
		: undefined;
	const practices = useUserPracticeView(viewed);
	const group = useUserViewGroup(viewed, selection);
	const showConversations = section === "conversations";
	const threadPage = search.threadPage ?? 0;
	const conversations = useUserViewConversations(viewed, threadPage, showConversations);
	const conversation = useUserViewConversation(
		viewed,
		showConversations ? search.thread : undefined,
	);
	useClampedPage(
		threadPage,
		conversations.status === "ready" ? conversations.totalPages : undefined,
		(next) => onSearchChange({ threadPage: pageParam(next) }),
	);

	// Dismissing the ask is remembered per refusal, so a retry that is refused again asks again.
	const [dismissed, setDismissed] = useState<unknown>();
	const refusal = [practices, group, conversations, conversation]
		.map((state) => (state.status === "error" ? state.error : undefined))
		.find((error) => stepUpChallengeOf(error) !== undefined);
	const challenge = stepUpChallengeOf(refusal);
	const askOpen = challenge !== undefined && refusal !== dismissed;
	const confirmAccess = useConfirmAccess(askOpen);

	return (
		<div className="grid gap-6">
			<UserViewBanner
				name={user.name ?? user.login}
				workspace={workspace.status === "ready" ? workspace.displayName : viewed.workspaceSlug}
				hasAccount={user.accountId != null}
				onExit={onExit}
			/>
			<UserViewNotices state={workspace} />
			<Tabs
				className="gap-4"
				value={section}
				onValueChange={(value) =>
					onSearchChange({ section: userViewSearchSchema.shape.section.parse(value) })
				}
			>
				<TabsList className="h-10 w-full p-1 sm:w-fit">
					<TabsTrigger value="practices">Practices</TabsTrigger>
					<TabsTrigger value="conversations">Conversations</TabsTrigger>
				</TabsList>
				<TabsContent value="practices">
					<UserViewPractices
						practices={practices}
						view={selection ? { kind: "group", selection, group } : { kind: "overview" }}
						skeletonRows={USER_VIEW_RUNS_PAGE_SIZE}
						onOpenGroup={(groupSlug) => onSearchChange({ group: groupSlug })}
						onSelectPractice={(practiceSlug) =>
							onSearchChange({ practice: practiceSlug, observation: undefined })
						}
						onToggleObservation={(observationId) =>
							onSearchChange({
								observation: search.observation === observationId ? undefined : observationId,
							})
						}
						onBack={() =>
							onSearchChange({ group: undefined, practice: undefined, observation: undefined })
						}
					/>
				</TabsContent>
				<TabsContent value="conversations">
					{search.thread ? (
						<UserViewConversationThread
							state={conversation}
							onBack={() => onSearchChange({ thread: undefined })}
						/>
					) : (
						<UserViewConversations
							state={conversations}
							page={threadPage}
							onPageChange={(next) => onSearchChange({ threadPage: pageParam(next) })}
							onOpen={(threadId) => onSearchChange({ thread: threadId })}
						/>
					)}
				</TabsContent>
			</Tabs>
			<ConfirmAccessDialog
				open={askOpen}
				onOpenChange={(open) => {
					if (!open) setDismissed(refusal);
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</div>
	);
}
