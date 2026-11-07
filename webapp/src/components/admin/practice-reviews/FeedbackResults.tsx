import { MessageSquareTextIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewFeedback } from "@/api/types.gen";
import { definitionFor, statusToneClass } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewItemCell } from "@/components/practice-trace/ReviewDayTable";
import {
	deliveryOutcome,
	withdrawalMark,
} from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	RecordDayTable,
	type RecordTableColumn,
	RecordTimeCell,
} from "@/components/practice-vocabulary/RecordTable";
import { StatusLabel } from "@/components/practice-vocabulary/StatusTooltip";
import {
	WITHHOLDING_FAMILY_DEFS,
	withholdingFamily,
	withholdingReasonSentence,
} from "@/components/practice-vocabulary/withholding-defs";
import { TableCell } from "@/components/ui/table";
import { formatDayTime } from "@/lib/dates";

import { RecordResults, type RecordResultsWords } from "./RecordResults";
import { RecordRowActions, ROW_OPEN_COLUMN, rowActionsColumn } from "./RecordRowActions";
import { developerLabel } from "./review-format";
import { feedbackLevel } from "./review-levels";
import { ReviewPerson } from "./ReviewPerson";
import { useTracked } from "./use-tracked";

/** A filtered empty state has to carry the way out of itself. */
export type FeedbackResultsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty"; filtered: false }
	| { status: "empty"; filtered: true; onClearFilters: () => void }
	| { status: "ready"; feedback: ReviewFeedback[] };

export interface FeedbackResultsProps {
	state: FeedbackResultsState;
	/** Placeholder rows after the feedback while the next page loads. */
	loadingMore?: number;
}

const LIST_LABEL = "Feedback";

/** The heads of the Feedback table, which every row below fills. */
export const FEEDBACK_COLUMNS: readonly RecordTableColumn[] = [
	{ label: "Composed", className: "w-24 whitespace-nowrap" },
	{ label: "Destination", className: "w-36" },
	{ label: "Work" },
	{ label: "Developer", className: "w-44" },
	{ label: "Delivery", className: "w-56" },
	rowActionsColumn(),
	ROW_OPEN_COLUMN,
];

const FEEDBACK_WORDS: RecordResultsWords = {
	errorTitle: "We could not load feedback",
	icon: MessageSquareTextIcon,
	emptyTitle: "No feedback yet",
	filteredTitle: "No feedback matches these filters",
	emptyDescription: "Delivered and withheld feedback appears here after reviews run.",
};

/** Feedback by day, drawn as the Reviews table draws reviews. */
export function FeedbackResults({ state, loadingMore }: FeedbackResultsProps) {
	return (
		<RecordResults
			state={state}
			aria-label={LIST_LABEL}
			columns={FEEDBACK_COLUMNS}
			words={FEEDBACK_WORDS}
		>
			{(empty) => (
				<RecordDayTable
					aria-label={LIST_LABEL}
					columns={FEEDBACK_COLUMNS}
					rows={state.status === "ready" ? state.feedback : []}
					dateOf={(item) => item.createdAt}
					rowKey={(item) => item.id}
					renderRow={(item) => <FeedbackRow feedback={item} />}
					empty={empty}
					loadingMore={loadingMore}
				/>
			)}
		</RecordResults>
	);
}

export interface FeedbackRowProps {
	feedback: ReviewFeedback;
}

export function feedbackRowName(feedback: ReviewFeedback): string {
	const location = definitionFor(FEEDBACK_LOCATION_DEFS, feedback.location).label.toLowerCase();
	const on = feedback.item === null ? "" : ` on ${feedback.item.label}`;
	return `for ${developerLabel(feedback.developer)}${on}, ${location}`;
}

export function FeedbackRow({ feedback }: FeedbackRowProps) {
	const today = new Date(useNow());
	const tracked = useTracked({ kind: "feedback", id: feedback.id });
	const name = feedbackRowName(feedback);
	const when = formatDayTime(feedback.createdAt, today);
	const delivery = deliveryOutcome(feedback);
	const withdrawal = withdrawalMark(feedback);
	const status =
		withdrawal === undefined
			? delivery.label
			: `${delivery.label}, ${withdrawal.label.toLowerCase()}`;
	const location = definitionFor(FEEDBACK_LOCATION_DEFS, feedback.location);
	const LocationIcon = location.icon;
	return (
		<PracticeTableRow
			link={{
				text: "Open feedback",
				name: `Feedback ${name}, ${status}, ${when}`,
				render: <DetailStackLink entry={feedbackLevel(feedback.id)} single />,
			}}
			variant={tracked ? "highlighted" : undefined}
			action={
				<RecordRowActions
					record={{ kind: "feedback", id: feedback.id }}
					reviewId={feedback.reviewId}
					name={`feedback ${name}, ${status}, ${when}`}
				/>
			}
		>
			<RecordTimeCell at={feedback.createdAt} />
			<TableCell className="align-top text-xs whitespace-normal">
				<span className="inline-flex min-w-0 items-center gap-1.5">
					<LocationIcon
						aria-hidden
						className={cn("size-3.5 shrink-0", statusToneClass(location.badgeVariant))}
					/>
					<span className="min-w-0 break-words">{location.label}</span>
				</span>
			</TableCell>
			{feedback.item === null ? (
				<TableCell className="align-top text-xs text-muted-foreground">No reviewed work</TableCell>
			) : (
				<ReviewItemCell item={feedback.item} linked={false} />
			)}
			<TableCell className="align-top whitespace-normal">
				<ReviewPerson person={feedback.developer} />
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1">
					<StatusLabel def={delivery} className="text-xs font-medium" />
					{withdrawal !== undefined && (
						<StatusLabel def={withdrawal} className="text-xs text-muted-foreground" />
					)}
					{feedback.state === "WITHHELD" && feedback.stopReason !== null && (
						<span title={withholdingReasonSentence(feedback.stopReason)}>
							<StatusLabel
								def={definitionFor(WITHHOLDING_FAMILY_DEFS, withholdingFamily(feedback.stopReason))}
								className="text-xs text-muted-foreground"
							/>
						</span>
					)}
				</span>
			</TableCell>
		</PracticeTableRow>
	);
}
