import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import { getWorkspaceOptions, listAgentsOptions } from "@/api/@tanstack/react-query.gen";
import type { ListAgentsResponse } from "@/api/types.gen";
import {
	availableReviewBinding,
	type ReviewModelState,
	type ReviewRunningState,
} from "@/components/admin/practices/review/review-readiness";

/**
 * Whether practice reviews can run in the workspace: switched on, and a review model ready.
 * Undefined until the workspace is in.
 */
export function useReviewRunning(workspaceSlug: string): ReviewRunningState | undefined {
	const workspaceQuery = useQuery({ ...getWorkspaceOptions({ path: { workspaceSlug } }) });
	const bindingsQuery = useQuery({ ...listAgentsOptions({ path: { workspaceSlug } }) });
	return (
		workspaceQuery.data && {
			enabled: workspaceQuery.data.practicesEnabled,
			model: reviewModelOf(bindingsQuery),
		}
	);
}

/** The review model's readiness, read off the workspace's agent bindings. */
export function reviewModelOf(bindingsQuery: UseQueryResult<ListAgentsResponse>): ReviewModelState {
	if (bindingsQuery.isPending) {
		return { status: "loading" };
	}
	if (bindingsQuery.isError) {
		return { status: "error" };
	}
	return { status: "ready", binding: availableReviewBinding(bindingsQuery.data) };
}
