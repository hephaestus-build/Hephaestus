import { createFileRoute, Outlet, stripSearchParams } from "@tanstack/react-router";

import { PracticeReviewsLayout } from "@/components/admin/practice-reviews/PracticeReviewsLayout";
import {
	PRACTICE_REVIEWS_SEARCH_DEFAULTS,
	type PracticeReviewsSearch,
	parsePracticeReviewLevels,
	practiceReviewsHead,
	practiceReviewsSearchSchema,
} from "@/components/admin/practice-reviews/review-levels";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";

import { PracticeReviewDrawer } from "./reviews/-PracticeReviewDrawer";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/reviews")({
	validateSearch: practiceReviewsSearchSchema,
	// The range is the overview's and a practice level's, not the lists': a list has its own dates,
	// so the range is not carried to the tabs, and its default stays out of every address.
	search: {
		middlewares: [
			stripSearchParams<PracticeReviewsSearch>(PRACTICE_REVIEWS_SEARCH_DEFAULTS),
			// The queue belongs to the level it was opened as, so it closes with the stack: left behind,
			// it would turn the next piece of feedback opened from a list into a queue too.
			({ search, next }) => {
				const result = next(search);
				return stackInSearch(result.detail).length > 0 ? result : { ...result, queue: undefined };
			},
		],
	},
	head: practiceReviewsHead("Practice reviews"),
	component: ReviewsLayoutRoute,
});

/** The tabs, and one stack of levels over whichever tab is open. */
function ReviewsLayoutRoute() {
	const { workspaceSlug } = Route.useParams();
	const { detail, range, queue } = Route.useSearch();
	const stack = parsePracticeReviewLevels(detail);
	const controls = useDetailStack(stack);
	return (
		<>
			<PracticeReviewsLayout workspaceSlug={workspaceSlug}>
				<Outlet />
			</PracticeReviewsLayout>
			<PracticeReviewDrawer
				workspaceSlug={workspaceSlug}
				stack={stack}
				onClose={controls.close}
				range={range}
				// With a record opened over it the footer is covered anyway; closing back to it restores it.
				approvalQueue={queue === "approvals" && stack.length === 1}
			/>
		</>
	);
}
