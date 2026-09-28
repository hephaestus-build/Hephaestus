import { createFileRoute, Outlet } from "@tanstack/react-router";

import { PracticeReviewsLayout } from "@/components/admin/practice-reviews/PracticeReviewsLayout";
import {
	PRACTICE_REVIEWS_SEARCH_DEFAULTS,
	type PracticeReviewsSearch,
	parsePracticeReviewLevels,
	practiceReviewsSearchSchema,
} from "@/components/admin/practice-reviews/review-levels";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { workspaceAdminHead } from "@/lib/page-title";
import { carriedSearchParams } from "@/lib/search-params";

import { PracticeReviewDrawer } from "./reviews/-PracticeReviewDrawer";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/reviews")({
	validateSearch: practiceReviewsSearchSchema,
	search: {
		middlewares: carriedSearchParams<PracticeReviewsSearch>(
			["range"],
			PRACTICE_REVIEWS_SEARCH_DEFAULTS,
		),
	},
	head: workspaceAdminHead("Practice reviews"),
	component: ReviewsLayoutRoute,
});

/** The tabs, and one stack of levels over whichever tab is open. */
function ReviewsLayoutRoute() {
	const { workspaceSlug } = Route.useParams();
	const { detail, range } = Route.useSearch();
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
			/>
		</>
	);
}
