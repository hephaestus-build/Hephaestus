import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { listPracticesOptions } from "@/api/@tanstack/react-query.gen";
import { rangeStart } from "@/components/activity/activity-range";
import { PracticeReviewOverviewPage } from "@/components/admin/practice-reviews/PracticeReviewOverviewPage";
import { rangeScope } from "@/components/admin/practice-reviews/review-outcomes";
import { reviewRunningTone } from "@/components/admin/practices/review/review-readiness";
import { ReviewRunningBanner } from "@/components/admin/practices/review/ReviewRunningBanner";
import { InlineLink } from "@/components/common/InlineLink";
import { useNow } from "@/components/common/use-now";
import {
	usePracticeReviewOverview,
	usePreviousReviewPeriod,
	useReviewAttention,
} from "@/hooks/use-practice-review-overview";
import { useReviewRunning } from "@/hooks/use-review-running";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/reviews/")({
	component: PracticeReviewOverviewRoute,
});

function PracticeReviewOverviewRoute() {
	const { workspaceSlug } = Route.useParams();
	const { range } = Route.useSearch();
	const setSearch = useSearchState();
	const nowMs = useNow();
	const from = rangeStart(nowMs, range);
	const scope = rangeScope(from, nowMs);
	const overview = usePracticeReviewOverview(workspaceSlug, from);
	const previousPeriod = usePreviousReviewPeriod(workspaceSlug, from, range);
	const attention = useReviewAttention(workspaceSlug, scope, overview);
	const running = useReviewRunning(workspaceSlug);
	const practices = useQuery(listPracticesOptions({ path: { workspaceSlug } })).data;
	// Stated only while reviews are not known to run: a working workspace is not told so on every visit.
	const stopped =
		running !== undefined && !["running", "checking"].includes(reviewRunningTone(running));

	return (
		<PracticeReviewOverviewPage
			workspaceSlug={workspaceSlug}
			range={range}
			onRangeChange={(next) => {
				void setSearch((previous) => ({ ...previous, range: next }), {
					state: true,
					replace: true,
				});
			}}
			scope={scope}
			overview={overview}
			previous={previousPeriod}
			attention={attention}
			practices={practices}
			banner={
				stopped && (
					<ReviewRunningBanner running={running}>
						<InlineLink
							render={
								<Link to="/w/$workspaceSlug/admin/practices/review" params={{ workspaceSlug }} />
							}
						>
							Open Review settings
						</InlineLink>
					</ReviewRunningBanner>
				)
			}
		/>
	);
}
