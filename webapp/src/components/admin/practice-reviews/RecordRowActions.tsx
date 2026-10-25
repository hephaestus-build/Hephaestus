import { RouteIcon } from "lucide-react";

import { cn } from "cn";
import { ROW_CONTROL_FOCUS } from "@/components/common/focus";
import { type InfoLine, InfoLines, InfoTip } from "@/components/common/InfoTip";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import type { RecordTableColumn } from "@/components/practice-vocabulary/RecordTable";
import { buttonVariants } from "@/components/ui/button";

import {
	RequestReviewIcon,
	RequestReviewAction,
	type RowReviewRequest,
} from "./RequestReviewAction";
import { type RecordRef, recordSelection, traceLevel } from "./review-layers";
import { selectionSearch } from "./review-selection";
import { ReviewSelectToggle, TrackIcon } from "./ReviewSelectToggle";

/** Trace's icon, on the row's button and in front of Trace's line under the (i). */
const TraceIcon = RouteIcon;

/**
 * What each of a row's controls does, one line each, under the actions column's (i). Each line leads
 * with its button's icon, so the reader sees which button it explains.
 */
const ROW_ACTION_LINES: readonly InfoLine[] = [
	{
		term: "Track",
		icon: TrackIcon,
		text: "narrows every tab to the records of this record’s review or work, and highlights its row. Press it again to clear it.",
	},
	{
		term: "Trace",
		icon: TraceIcon,
		text: "shows how this record came about, from the work through the review to the feedback.",
	},
];

const REQUEST_REVIEW_LINE: InfoLine = {
	term: "Request review",
	icon: RequestReviewIcon,
	text: "asks for a new review of the row’s work now. It asks you to confirm first.",
};

/**
 * The head over a record table's actions column, which `PracticeTableRow` draws from its `action`:
 * the row's controls stand in a column of their own, apart from the facts the row opens on, wide
 * enough for Track, Trace and Request review side by side. Its (i) says what each does; `request`
 * adds Request review's line, for a table whose rows offer it.
 */
export function rowActionsColumn({
	request = false,
}: { request?: boolean } = {}): RecordTableColumn {
	return {
		label: "Actions",
		className: "w-36",
		info: (
			<InfoTip label="About the row actions">
				<InfoLines
					lines={request ? [...ROW_ACTION_LINES, REQUEST_REVIEW_LINE] : ROW_ACTION_LINES}
				/>
			</InfoTip>
		),
	};
}

/** The head over the row's own "Open …" link, after the actions. */
export const ROW_OPEN_COLUMN: RecordTableColumn = {
	label: "Open",
	srOnly: true,
	className: "w-36",
};

export interface RecordRowActionsProps {
	/** The row's record, which Track makes the selection and Trace opens the trace of. */
	record: RecordRef;
	/** The review the record came from, where the row knows it: tracking narrows to it at once. */
	reviewId?: string;
	resultId?: string;
	/** The record, after "Track" and "Trace" in the accessible names, so every row's pair differs. */
	name: string;
	/** A new review of the row's work, requested: the third action, on rows that name a piece of work. */
	request?: RowReviewRequest;
}

/**
 * The actions every row of Practice reviews ends in, and a third where the row names work to review. Track (crosshair) makes the row's record
 * the one every table narrows to, or clears it. Trace opens the record's trace over the table and
 * tracks the record too, so the tables behind the trace show what it shows. Neither opens the row:
 * the row itself opens the record's sheet. Request review asks for a new review of the row's work.
 */
export function RecordRowActions({
	record,
	reviewId,
	resultId,
	name,
	request,
}: RecordRowActionsProps) {
	const selection = recordSelection(record, { reviewId, resultId });
	return (
		// `relative z-10` lifts them over a list row's stretched link. Each is the app's default icon
		// button, 32 px, apart enough that a press meant for one never lands on its neighbour.
		<span className="relative z-10 inline-flex items-center gap-2">
			{selection !== undefined && <ReviewSelectToggle selection={selection} name={name} />}
			<DetailStackLink
				entry={traceLevel(record)}
				single
				levelSearch={{ ...selectionSearch(selection), queue: undefined }}
				aria-label={`Trace ${name}`}
				title="Trace"
				className={cn(buttonVariants({ variant: "outline", size: "icon" }), ROW_CONTROL_FOCUS)}
			>
				<TraceIcon aria-hidden />
			</DetailStackLink>
			{request !== undefined && <RequestReviewAction request={request} />}
		</span>
	);
}
