import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	getUserPracticeViewOptions,
	getUserViewConversationOptions,
	getUserViewObservationOptions,
	getUserViewTrendOptions,
	getWorkspaceOptions,
	listUserViewConversationsOptions,
	listUserViewRunsInfiniteOptions,
	listUserViewUsersOptions,
} from "@/api/@tanstack/react-query.gen";
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
import { loadedPages } from "@/integrations/tanstack-query/spring-page";
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
	const query = useQuery({
		...getUserPracticeViewOptions(privateRequest(viewed)),
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
	const observationId = selection?.observationId;
	const trend = useQuery({
		...getUserViewTrendOptions({ ...request, path: { ...request.path, groupSlug } }),
		...PRIVATE_READ,
		enabled: selection !== undefined,
	});
	const runs = useInfiniteQuery({
		...listUserViewRunsInfiniteOptions({
			...request,
			path: { ...request.path, groupSlug },
			query: { size: USER_VIEW_RUNS_PAGE_SIZE, practiceSlug: selection?.practiceSlug },
		}),
		...PRIVATE_READ,
		enabled: selection !== undefined,
		initialPageParam: 0,
		getNextPageParam: (last) => (last.hasNext ? (last.page ?? 0) + 1 : undefined),
	});
	const observation = useQuery({
		...getUserViewObservationOptions({
			...request,
			path: { ...request.path, observationId: observationId ?? "" },
		}),
		...PRIVATE_READ,
		enabled: selection !== undefined && observationId !== undefined,
	});

	const refused = [trend.error, runs.error, observation.error].find(
		(error) => stepUpChallengeOf(error) !== undefined,
	);
	if (refused !== undefined || trend.isError) {
		return {
			status: "error",
			error: refused ?? trend.error,
			onRetry: () => {
				if (trend.isError) void trend.refetch();
				if (runs.isError) void runs.refetch();
				if (observation.isError) void observation.refetch();
			},
		};
	}
	if (trend.isPending) return { status: "loading" };
	return {
		status: "ready",
		trend: trend.data,
		feed: runs.isError
			? { status: "error", error: runs.error, onRetry: () => void runs.refetch() }
			: runs.isPending
				? { status: "loading" }
				: {
						status: "ready",
						runs: loadedPages(runs.data).flatMap((page) => page.content),
						hasMore: runs.hasNextPage,
						isLoadingMore: runs.isFetchingNextPage,
						onLoadMore: () => void runs.fetchNextPage(),
					},
		observationDetail: observationId
			? {
					isLoading: observation.isPending,
					detail: observation.data,
					error: observation.error ?? undefined,
				}
			: undefined,
	};
}

export function useUserViewConversations(
	viewed: ViewedUser,
	page: number,
	enabled: boolean,
): UserViewConversationsState {
	const query = useQuery({
		...listUserViewConversationsOptions({
			...privateRequest(viewed),
			query: { page, size: USER_VIEW_PAGE_SIZE },
		}),
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
	const query = useQuery({
		...getUserViewConversationOptions({
			...request,
			path: { ...request.path, threadId: threadId ?? "" },
		}),
		...PRIVATE_READ,
		enabled: threadId !== undefined,
	});
	return panelState(query, (thread) => {
		const messages = parseThreadMessages(thread.messages);
		return messages ? { status: "ready", messages } : { status: "unreadable" };
	});
}
