import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link, redirect } from "@tanstack/react-router";

import { listPracticesOptions } from "@/api/@tanstack/react-query.gen";
import { PracticeReviewOverviewPage } from "@/components/admin/practice-reviews/PracticeReviewOverviewPage";
import {
	rangeToRestore,
	rememberRange,
} from "@/components/admin/practice-reviews/remembered-range";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import { rangeScope } from "@/components/admin/practice-reviews/review-outcomes";
import { ALL_TIME, reviewRangeStart } from "@/components/admin/practice-reviews/review-range";
import { clearedScope, readScope } from "@/components/admin/practice-reviews/review-scope";
import { overviewSearchSchema } from "@/components/admin/practice-reviews/review-search";
import { ReviewScopeFilters } from "@/components/admin/practice-reviews/ReviewScopeFilters";
import { reviewReadinessTone } from "@/components/admin/practices/review/review-readiness";
import { ReviewReadinessBanner } from "@/components/admin/practices/review/ReviewReadinessBanner";
import { InlineLink } from "@/components/common/InlineLink";
import { useNow } from "@/components/common/use-now";
import {
	useDeliveryBreakdown,
	usePracticeReviewOverview,
	usePracticeReviewQueue,
	usePracticeReviewTiming,
	usePreviousReviewPeriod,
	useReviewAttention,
	useSettleWhenReviewsFinish,
} from "@/hooks/use-practice-review-overview";
import { useReviewReadiness } from "@/hooks/use-review-readiness";
import { useReviewScopeSources } from "@/hooks/use-review-scope-sources";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/overview",
)({
	// The overview narrows by none of the shared scope, but keeps it for the next tab.
	validateSearch: overviewSearchSchema,
	// The range chosen on the overview is kept while the browser tab is open, so coming back from a
	// list, whose links do not carry it, restores it. An address that names a range wins.
	beforeLoad: ({ location, params, search }) => {
		const restored = rangeToRestore(params.workspaceSlug, location.searchStr, search.range);
		if (restored === undefined) {
			rememberRange(params.workspaceSlug, search.range);
			return;
		}
		throw redirect({
			to: "/w/$workspaceSlug/admin/practices/reviews/overview",
			params,
			search: (previous) => ({ ...previous, range: restored }),
			replace: true,
		});
	},
	head: practiceReviewsHead("Overview"),
	component: PracticeReviewOverviewRoute,
});

function PracticeReviewOverviewRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const { range } = search;
	const scopeSources = useReviewScopeSources(workspaceSlug);
	const setSearch = useSearchState();
	const nowMs = useNow();
	// All time has no first day: the server starts at the first review, and the lists open unbounded.
	const from = range === ALL_TIME ? undefined : reviewRangeStart(nowMs, range);
	const scope = from === undefined ? {} : rangeScope(from, nowMs);
	const overview = usePracticeReviewOverview(workspaceSlug, from);
	const previousPeriod = usePreviousReviewPeriod(workspaceSlug, from, range);
	const attention = useReviewAttention(workspaceSlug, overview);
	const delivery = useDeliveryBreakdown(workspaceSlug, scope, overview);
	const timing = usePracticeReviewTiming(workspaceSlug, from, overview);
	const queue = usePracticeReviewQueue(workspaceSlug, overview);
	useSettleWhenReviewsFinish(workspaceSlug, overview);
	const readiness = useReviewReadiness(workspaceSlug);
	const practices = useQuery(listPracticesOptions({ path: { workspaceSlug } })).data;
	// Stated only while reviews are not known to run: a working workspace is not told so on every visit.
	const stopped =
		readiness !== undefined && !["ready", "checking"].includes(reviewReadinessTone(readiness));

	return (
		<div className="space-y-4">
			<ReviewScopeFilters
				tab="overview"
				scope={readScope(search)}
				onChange={(patch) => {
					void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
				}}
				onClear={() => {
					void setSearch((previous) => ({ ...previous, ...clearedScope() }), { replace: true });
				}}
				dateTitle="Dates"
				groups={scopeSources.groups}
				practices={scopeSources.practices}
				people={scopeSources.people}
				notAppliedNote="Not applied on the overview"
			/>
			<PracticeReviewOverviewPage
				workspaceSlug={workspaceSlug}
				range={range}
				onRangeChange={(next) => {
					// Kept before the address changes, since the default leaves the address without one.
					rememberRange(workspaceSlug, next);
					void setSearch((previous) => ({ ...previous, range: next }), {
						state: true,
						replace: true,
					});
				}}
				scope={scope}
				overview={overview}
				previous={previousPeriod}
				attention={attention}
				delivery={delivery}
				timing={timing}
				queue={queue}
				practices={practices}
				practicesFilter={{
					groups: search.practicesGroup ?? [],
					showTurnedOff: search.showTurnedOff === true,
					sort: search.practicesSort ?? "ATTENTION",
					onChange: (patch) => {
						void setSearch(
							(previous) => ({
								...previous,
								...("groups" in patch && {
									practicesGroup:
										patch.groups !== undefined && patch.groups.length > 0
											? patch.groups
											: undefined,
								}),
								...("showTurnedOff" in patch && {
									showTurnedOff: patch.showTurnedOff === true ? (true as const) : undefined,
								}),
								...("sort" in patch && {
									practicesSort: patch.sort === "BUSIEST" ? ("BUSIEST" as const) : undefined,
								}),
							}),
							{ replace: true },
						);
					},
				}}
				banner={
					stopped && (
						<ReviewReadinessBanner readiness={readiness}>
							<InlineLink
								render={
									<Link to="/w/$workspaceSlug/admin/practices/review" params={{ workspaceSlug }} />
								}
							>
								Open Review settings
							</InlineLink>
						</ReviewReadinessBanner>
					)
				}
			/>
		</div>
	);
}
