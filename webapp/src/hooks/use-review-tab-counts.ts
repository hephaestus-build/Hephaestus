import { useQuery } from "@tanstack/react-query";

import {
	listReviewFeedbackOptions,
	listReviewProblemsOptions,
	listReviewResultsOptions,
	listReviewsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ReviewScopeSearch } from "@/components/admin/practice-reviews/review-scope";
import {
	feedbackQuery,
	problemsQuery,
	resultsQuery,
	reviewsQuery,
} from "@/components/admin/practice-reviews/review-search";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";

/** One row is the smallest page the endpoints serve; only its total is read. */
const COUNT_PAGE = 1;

/** Count reads refresh once a minute while the page is visible. */
const COUNT_REFRESH_MS = 60_000;

/**
 * What every count read shares. Counts refresh quietly as reviews progress, including after a
 * failed read exhausts its retries. A count of another scope or workspace is never shown as this one's:
 * until its own read answers, it shows nothing.
 */
const COUNT_READ = {
	retry: retryQuery,
	refetchInterval: COUNT_REFRESH_MS,
} as const;

export function useReviewTabCounts(workspaceSlug: string, search: ReviewScopeSearch) {
	const path = { workspaceSlug };
	// An order changes no count, so the counts do not read again when it changes.
	const scope = { ...search, order: undefined };
	const reviews = useQuery({
		...listReviewsOptions({ path, query: reviewsQuery(scope, COUNT_PAGE) }),
		...COUNT_READ,
		select: (page) => page.totalElements,
	});
	const results = useQuery({
		...listReviewResultsOptions({ path, query: resultsQuery(scope, COUNT_PAGE) }),
		...COUNT_READ,
		select: (page) => page.totalElements,
	});
	const problems = useQuery({
		...listReviewProblemsOptions({ path, query: problemsQuery(scope, COUNT_PAGE) }),
		...COUNT_READ,
		select: (page) => page.totalElements,
	});
	const feedback = useQuery({
		...listReviewFeedbackOptions({ path, query: feedbackQuery(scope, COUNT_PAGE) }),
		...COUNT_READ,
		select: (page) => page.totalElements,
	});
	return {
		reviews: reviews.data,
		results: results.data,
		problems: problems.data,
		feedback: feedback.data,
	};
}
