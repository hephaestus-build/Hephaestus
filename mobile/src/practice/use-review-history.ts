import { useInfiniteQuery } from "@tanstack/react-query";

import {
	listPracticeGroupReviewRunsInfiniteOptions,
	listPracticeGroupReviewRunsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { useWorkspace } from "@/workspace/workspace-context";

/** How many review runs one page brings, as on the web. */
const PAGE_SIZE = 10;

/**
 * The developer's review runs in one group, newest first, a page at a time, narrowed to one practice
 * when a practice is given. The group page, the practice page and an observation opened from either
 * share these pages, so an observation reads its run from the page it was opened from. With `load`
 * false it reads only what is already loaded.
 */
export function useReviewHistory(groupSlug: string, practiceSlug?: string, load = true) {
	const { workspaceSlug } = useWorkspace();
	return useInfiniteQuery({
		...listPracticeGroupReviewRunsInfiniteOptions({
			path: { workspaceSlug, groupSlug },
			query: { size: PAGE_SIZE, ...(practiceSlug === undefined ? {} : { practiceSlug }) },
		}),
		initialPageParam: 0,
		getNextPageParam: (last) => (last.hasNext === true ? (last.page ?? 0) + 1 : undefined),
		enabled: load,
	});
}

/** The key under which every page of one group's history is kept, whichever practice narrows it. */
export function reviewHistoryKey(workspaceSlug: string, groupSlug: string) {
	return listPracticeGroupReviewRunsInfiniteQueryKey({ path: { workspaceSlug, groupSlug } });
}
