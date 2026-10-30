import {
	createFileRoute,
	Outlet,
	stripSearchParams,
	useMatchRoute,
	useSearch,
} from "@tanstack/react-router";

import { PracticeReviewsLayout } from "@/components/admin/practice-reviews/PracticeReviewsLayout";
import { recordLevel, type RecordRef } from "@/components/admin/practice-reviews/review-layers";
import {
	PRACTICE_REVIEWS_SEARCH_DEFAULTS,
	type PracticeReviewsSearch,
	parsePracticeReviewLevels,
	practiceReviewsHead,
	practiceReviewsSearchSchema,
} from "@/components/admin/practice-reviews/review-levels";
import { parseScopeSearch } from "@/components/admin/practice-reviews/review-search";
import {
	parseSelectionSearch,
	selectionSearch,
} from "@/components/admin/practice-reviews/review-selection";
import { ReviewSelectionBar } from "@/components/admin/practice-reviews/ReviewSelectionBar";
import { detailStackKey, stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { useReviewSelection } from "@/hooks/use-review-selection";
import { useReviewTabCounts } from "@/hooks/use-review-tab-counts";
import { useSearchState } from "@/lib/search-params";

import { PracticeReviewDrawer } from "./reviews/-PracticeReviewDrawer";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/reviews")({
	validateSearch: practiceReviewsSearchSchema,
	// The range is the overview's, not the lists': a list has its own dates,
	// so the range is not carried to the tabs, and its default stays out of every address.
	search: {
		middlewares: [
			stripSearchParams<PracticeReviewsSearch>(PRACTICE_REVIEWS_SEARCH_DEFAULTS),
			// The queue belongs to the level it was opened as. Left behind under another level, it would
			// turn the next piece of feedback opened from a list into a queue too.
			({ search, next }) => {
				const result = next(search);
				return {
					...result,
					queue: stackInSearch(result.detail).length > 0 ? result.queue : undefined,
				};
			},
		],
	},
	head: practiceReviewsHead("Practice reviews"),
	component: ReviewsLayoutRoute,
});

/** The tabs, the tracked record above them, and the sheets and the trace over them. */
function ReviewsLayoutRoute() {
	const { workspaceSlug } = Route.useParams();
	const { detail, queue } = Route.useSearch();
	const stack = parsePracticeReviewLevels(detail);
	const controls = useDetailStack();
	// The selection is in every tab's own search, which this layout's schema does not declare.
	const selectionScope = useSearch({ strict: false, select: parseSelectionSearch });
	const selected = useReviewSelection(workspaceSlug, selectionScope);
	// The shared scope is in every tab's own search too; each tab's count reads what its link carries.
	const scope = useSearch({ strict: false, select: parseScopeSearch });
	const counts = useReviewTabCounts(workspaceSlug, scope);
	const setSearch = useSearchState();
	const matchRoute = useMatchRoute();
	const onOverview =
		matchRoute({ to: "/w/$workspaceSlug/admin/practices/reviews", params: { workspaceSlug } }) !==
		false;
	const openOverTrace = (record: RecordRef) => {
		const [trace, over] = stack;
		if (trace?.kind !== "trace") {
			return;
		}
		if (over === undefined) {
			void controls.push([recordLevel(record)]);
			return;
		}
		// The one level over the trace is swapped for the next record, not stacked on it: the sheet
		// over a trace is the deepest level.
		void setSearch(
			(previous) => ({
				...previous,
				detail: [detailStackKey(trace), detailStackKey(recordLevel(record))],
			}),
			{ state: true, replace: true },
		);
	};
	return (
		<>
			<PracticeReviewsLayout
				workspaceSlug={workspaceSlug}
				counts={counts}
				selection={
					selected.picked &&
					selected.selection && (
						<ReviewSelectionBar
							kind={selected.selection.kind}
							name={selected.name}
							trace={selected.trace}
							onClear={() => {
								void setSearch((previous) => ({ ...previous, ...selectionSearch(undefined) }), {
									replace: true,
								});
							}}
							note={onOverview ? "Not applied on the overview" : undefined}
						/>
					)
				}
			>
				<Outlet />
			</PracticeReviewsLayout>
			<PracticeReviewDrawer
				workspaceSlug={workspaceSlug}
				stack={stack}
				onClose={controls.close}
				onOpenOverTrace={openOverTrace}
				// With a record opened over it the footer is covered anyway; closing back to it restores it.
				approvalQueue={queue === "approvals"}
			/>
		</>
	);
}
