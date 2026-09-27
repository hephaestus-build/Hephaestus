import { useInfiniteQuery } from "@tanstack/react-query";

import { listPracticeGroupReviewRunsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { useWorkspace } from "@/workspace/workspace-context";

/** How many review runs one page brings, as on the web. */
const PAGE_SIZE = 10;

/**
 * The developer's review runs in one group, newest first, a page at a time, narrowed to one practice
 * when a practice is given. Nothing is read while `enabled` is false: a page with nothing to show asks
 * for nothing.
 */
export function useReviewHistory(groupSlug: string, practiceSlug?: string, enabled = true) {
	const { workspaceSlug } = useWorkspace();
	return useInfiniteQuery({
		...listPracticeGroupReviewRunsInfiniteOptions({
			path: { workspaceSlug, groupSlug },
			query: { size: PAGE_SIZE, ...(practiceSlug === undefined ? {} : { practiceSlug }) },
		}),
		initialPageParam: 0,
		getNextPageParam: (last) => (last.hasNext === true ? (last.page ?? 0) + 1 : undefined),
		enabled,
	});
}
