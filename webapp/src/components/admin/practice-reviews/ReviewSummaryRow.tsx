import type { ReviewSummary } from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { type ReviewColumn, ReviewItemCell } from "@/components/practice-trace/ReviewDayTable";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import { RecordTimeCell } from "@/components/practice-vocabulary/RecordTable";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import {
	REVIEW_STATUS_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
	REVIEW_JOB_QUEUED_DEF,
	reviewJobQueued,
} from "@/components/practice-vocabulary/review-status-defs";
import { ReviewStatusBadge } from "@/components/practice-vocabulary/ReviewStatusBadge";
import { TableCell } from "@/components/ui/table";
import { formatDayTime } from "@/lib/dates";

import { RecordRowActions, ROW_OPEN_COLUMN, rowActionsColumn } from "./RecordRowActions";
import type { RowReviewRequest } from "./RequestReviewAction";
import { requesterAsPerson } from "./review-format";
import { reviewLevel } from "./review-levels";
import { ReviewPerson } from "./ReviewPerson";
import { useTracked } from "./use-tracked";

export const REVIEW_SUMMARY_COLUMNS: readonly ReviewColumn[] = [
	{ label: "Started by", className: "w-44" },
	rowActionsColumn({ request: true }),
	ROW_OPEN_COLUMN,
];

export interface ReviewSummaryRowProps {
	review: ReviewSummary;
	open?: boolean;
	request?: RowReviewRequest;
}

export function ReviewSummaryRow({ review, open = false, request }: ReviewSummaryRowProps) {
	const today = new Date(useNow());
	const name = review.item.label;
	const when = formatDayTime(review.createdAt, today);
	const tracked = useTracked({ kind: "review", id: review.id });
	return (
		<PracticeTableRow
			open={open}
			link={{
				text: "Open review",
				name: `of ${name}, ${when}`,
				render: <DetailStackLink entry={reviewLevel(review.id)} single />,
			}}
			variant={tracked ? "highlighted" : undefined}
			action={
				<RecordRowActions
					record={{ kind: "review", id: review.id }}
					name={`review of ${name}, ${when}`}
					request={request}
				/>
			}
		>
			<RecordTimeCell
				status={REVIEW_STATUS_DEFS[review.status]}
				at={review.createdAt}
				statusTooltip={false}
			/>
			<ReviewItemCell item={review.item} linked={false}>
				<ReviewMarkers review={review} />
			</ReviewItemCell>
			<TableCell className="align-top whitespace-normal">
				<StartedBy review={review} />
			</TableCell>
		</PracticeTableRow>
	);
}

export function reviewMarkers(
	review: Pick<ReviewSummary, "status" | "activeJobCount" | "queuedJobCount">,
): StatusDef[] {
	if (review.status === "FAILED" || review.status === "PARTIAL") {
		return [REVIEW_STATUS_DEFS[review.status]];
	}
	return reviewJobQueued(review) ? [REVIEW_JOB_QUEUED_DEF] : [];
}

function ReviewMarkers({ review }: { review: ReviewSummary }) {
	const reason =
		review.stopReason === null ? undefined : REVIEW_STOP_REASON_SENTENCES[review.stopReason];
	return reviewMarkers(review).map((def) => (
		<ReviewStatusBadge key={def.label} def={def} reason={reason} />
	));
}

/** A requested review names its requester as the result tables name a developer; any other its origin. */
function StartedBy({ review }: { review: ReviewSummary }) {
	// One line height for both, so a face (24 px) and a badge (20 px) center on the same line.
	return (
		<span className="flex min-h-6 items-center">
			{review.origin === "REQUEST" && review.requestedBy ? (
				<ReviewPerson person={requesterAsPerson(review.requestedBy)} />
			) : (
				<StatusBadge def={REVIEW_ORIGIN_DEFS[review.origin]} />
			)}
		</span>
	);
}
