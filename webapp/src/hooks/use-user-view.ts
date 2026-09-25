import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	getUserPracticeViewQueryKey,
	getUserViewConversationQueryKey,
	getUserViewTrendQueryKey,
	getWorkspaceOptions,
	listUserViewConversationsQueryKey,
	listUserViewRunsInfiniteQueryKey,
	listUserViewUsersOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	getUserPracticeView,
	getUserViewConversation,
	getUserViewTrend,
	listUserViewConversations,
	listUserViewRuns,
} from "@/api/sdk.gen";
import type { Workspace } from "@/api/types.gen";
import type { UserViewConversationsState } from "@/components/admin/users/UserViewConversations";
import type { UserViewConversationState } from "@/components/admin/users/UserViewConversationThread";
import type {
	UserPracticeViewState,
	UserViewGroupSelection,
	UserViewGroupState,
} from "@/components/admin/users/UserViewPractices";
import type { UserViewUsersState } from "@/components/admin/users/UserViewUsersTable";
import { type PanelState, panelState } from "@/components/common/panel-state";
import {
	nextReviewRunPage,
	type ReviewRunFeedState,
	reviewRunFeedState,
} from "@/components/profile/review-runs";
import { parseThreadMessages } from "@/lib/chat-validation";
import { stepUpChallengeOf } from "@/lib/problem-detail";

export const USER_VIEW_PAGE_SIZE = 25;
export const USER_VIEW_RUNS_PAGE_SIZE = 10;

// Private data is discarded when unused; automatic retries must not duplicate refused audit attempts.
const PRIVATE_READ = { gcTime: 0, staleTime: 0, retry: false } as const;

export interface ViewedUser {
	workspaceSlug: string;
	userId: number;
	reason: string;
}

function privateRequest({ workspaceSlug, userId, reason }: ViewedUser) {
	return {
		path: { workspaceSlug, userId },
		headers: { "X-User-View-Reason": encodeURIComponent(reason) },
	};
}

function privateCacheIdentity<T extends ReturnType<typeof privateRequest>>(request: T) {
	// The audit reason belongs in the request, not in a serializable query key.
	return { ...request, headers: { "X-User-View-Reason": "" } };
}

export type UserViewWorkspaceState = PanelState<
	Pick<Workspace, "displayName" | "practicesEnabled" | "mentorEnabled">
>;

export function useUserViewWorkspace(workspaceSlug: string): UserViewWorkspaceState {
	const query = useQuery(getWorkspaceOptions({ path: { workspaceSlug } }));
	return panelState(query, (workspace) => ({
		status: "ready",
		displayName: workspace.displayName,
		practicesEnabled: workspace.practicesEnabled,
		mentorEnabled: workspace.mentorEnabled,
	}));
}

export function useUserViewUsers(workspaceSlug: string, page: number): UserViewUsersState {
	const query = useQuery(
		listUserViewUsersOptions({
			path: { workspaceSlug },
			query: { page, size: USER_VIEW_PAGE_SIZE },
		}),
	);
	return panelState(query, (data) => ({
		status: "ready",
		users: data.content ?? [],
		totalPages: data.totalPages ?? 0,
	}));
}

export function useUserPracticeView(viewed: ViewedUser): UserPracticeViewState {
	const request = privateRequest(viewed);
	const query = useQuery({
		queryKey: getUserPracticeViewQueryKey(privateCacheIdentity(request)),
		queryFn: async ({ signal }) => {
			const { data } = await getUserPracticeView({ ...request, signal, throwOnError: true });
			return data;
		},
		...PRIVATE_READ,
	});
	return panelState(query, (summary) => ({ status: "ready", summary }));
}

export function useUserViewGroup(
	viewed: ViewedUser,
	selection: UserViewGroupSelection | undefined,
): UserViewGroupState {
	const request = privateRequest(viewed);
	const groupSlug = selection?.groupSlug ?? "";
	const groupPath = { ...request.path, groupSlug };
	const runsQuery = { size: USER_VIEW_RUNS_PAGE_SIZE, practiceSlug: selection?.practiceSlug };
	const trend = useQuery({
		queryKey: getUserViewTrendQueryKey(privateCacheIdentity({ ...request, path: groupPath })),
		queryFn: async ({ signal }) => {
			const { data } = await getUserViewTrend({
				...request,
				path: groupPath,
				signal,
				throwOnError: true,
			});
			return data;
		},
		...PRIVATE_READ,
		enabled: selection !== undefined,
	});
	const runs = useInfiniteQuery({
		queryKey: listUserViewRunsInfiniteQueryKey(
			privateCacheIdentity({ ...request, path: groupPath, query: runsQuery }),
		),
		queryFn: async ({ pageParam, signal }) => {
			const { data } = await listUserViewRuns({
				...request,
				path: groupPath,
				query: { ...runsQuery, page: pageParam },
				signal,
				throwOnError: true,
			});
			return data;
		},
		...PRIVATE_READ,
		enabled: selection !== undefined,
		initialPageParam: 0,
		getNextPageParam: nextReviewRunPage,
	});

	const refused = [trend.error, runs.error].find((error) => stepUpChallengeOf(error) !== undefined);
	if (refused !== undefined || trend.isError) {
		return {
			status: "error",
			error: refused ?? trend.error,
			onRetry: () => {
				if (trend.isError) {
					void trend.refetch();
				}
				if (runs.isError) {
					void runs.refetch();
				}
			},
		};
	}
	if (trend.isPending) {
		return { status: "loading" };
	}
	const feed: ReviewRunFeedState = reviewRunFeedState(runs);
	return {
		status: "ready",
		trend: trend.data,
		feed,
	};
}

export function useUserViewConversations(
	viewed: ViewedUser,
	page: number,
	enabled: boolean,
): UserViewConversationsState {
	const request = privateRequest(viewed);
	const conversationsQuery = { page, size: USER_VIEW_PAGE_SIZE };
	const query = useQuery({
		queryKey: listUserViewConversationsQueryKey(
			privateCacheIdentity({ ...request, query: conversationsQuery }),
		),
		queryFn: async ({ signal }) => {
			const { data } = await listUserViewConversations({
				...request,
				query: conversationsQuery,
				signal,
				throwOnError: true,
			});
			return data;
		},
		...PRIVATE_READ,
		enabled,
	});
	return panelState(query, (data) => ({
		status: "ready",
		threads: data.content ?? [],
		totalPages: data.totalPages ?? 0,
	}));
}

export function useUserViewConversation(
	viewed: ViewedUser,
	threadId: string | undefined,
): UserViewConversationState {
	const request = privateRequest(viewed);
	const conversationPath = { ...request.path, threadId: threadId ?? "" };
	const query = useQuery({
		queryKey: getUserViewConversationQueryKey(
			privateCacheIdentity({ ...request, path: conversationPath }),
		),
		queryFn: async ({ signal }) => {
			const { data } = await getUserViewConversation({
				...request,
				path: conversationPath,
				signal,
				throwOnError: true,
			});
			return data;
		},
		...PRIVATE_READ,
		enabled: threadId !== undefined,
	});
	return panelState(query, (thread) => {
		const messages = parseThreadMessages(thread.messages);
		return messages ? { status: "ready", messages } : { status: "unreadable" };
	});
}
