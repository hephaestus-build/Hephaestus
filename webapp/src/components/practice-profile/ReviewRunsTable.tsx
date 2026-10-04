import { PulseIcon } from "@primer/octicons-react";
import { PlayIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { useNow } from "@/components/common/use-now";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { count, spell } from "@/components/practice-vocabulary/feedback-text";
import { OPEN_ROW_BAR, PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import { REVIEW_RUN_STATE_DEFS } from "@/components/practice-vocabulary/review-run-state-defs";
import { StatusIcon } from "@/components/practice-vocabulary/StatusTooltip";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { artifactKindNoun, sameReviewedWork } from "@/lib/artifact-kinds";
import { formatDayTime, formatTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { REVIEWS_OF_YOUR_WORK } from "./practice-profile-search";
import { groupReviewRunsByDay } from "./review-run-groups";
import { LatestReviewTag, RequestedReviewTag, ReviewOrdinalTag } from "./review-run-tags";

export interface ReviewRunsTableProps {
	/** Newest first, as the wire orders them. */
	runs: ProfileReviewRun[];
	/**
	 * Whether a filter narrows the list: the newest row is then not the latest review, and an empty
	 * list says nothing about whether any review exists.
	 */
	filtered: boolean;
	onOpenReview: (reviewId: string) => void;
	openReviewId?: string;
	/** Offered only on the rows whose `mayRequest` allows it. */
	onReviewNow?: (work: ReviewedWorkRef) => void;
	/** The work an ask is in flight about; its rows say so rather than invite a second. */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	/** Each review's position among the reviews of its work, when every review is loaded. */
	positions?: Map<string, number>;
}

const COLUMNS = 5;

/** How many slipped practices a row names before it counts the rest. */
const NAMED_PRACTICES = 2;

/**
 * What the row leads with: the practices this review recorded a problem about, by name. A review
 * that stopped, is still going or left a practice undetermined says that instead of "Nothing to
 * improve", a claim it never made.
 */
function slippedLead(run: ProfileReviewRun): string {
	const names = run.slippedPractices.map((practice) => practice.practiceName);
	if (names.length > 0) {
		const rest = names.length - NAMED_PRACTICES;
		const named = names.slice(0, NAMED_PRACTICES).join(", ");
		return rest > 0 ? `${named} +${rest} more` : named;
	}
	if (run.status === "FAILED") {
		return "Stopped before it finished";
	}
	if (run.status === "IN_PROGRESS") {
		return "Still running";
	}
	if (run.practices.undetermined > 0) {
		return "Some practices remain undetermined";
	}
	return "Nothing to improve";
}

/**
 * "5 practices met, 9 did not apply · 16 practices reached", every number a count of practices. A
 * nought is dropped: this is a sentence about somebody's own work, and what slipped is already
 * named above it. The reach is its own clause, never a total of the outcomes: it counts every
 * practice the review measured, the outcomes only those it decided something about for the reader.
 */
function reachedPhrase(run: ProfileReviewRun): string {
	const { met, notApplicable, undetermined } = run.practices;
	const reached = run.practicesEvaluated;
	const outcomes = [
		{ n: met, verb: "met" },
		{ n: notApplicable, verb: "did not apply" },
		{ n: undetermined, verb: "undetermined" },
	].filter((outcome) => outcome.n > 0);
	if (outcomes.length === 0 && reached === undefined) {
		return run.status === "IN_PROGRESS" ? "Results appear as it finishes" : "";
	}
	// One number rule for the line, so "16 practices reached" never sits beside "five held".
	const digits = [reached ?? 0, ...outcomes.map((outcome) => outcome.n)].some((n) => n >= 10);
	const decided = outcomes
		.map(
			(outcome, index) =>
				`${index === 0 ? count(outcome.n, "practice", "practices", digits) : spell(outcome.n, digits)} ${outcome.verb}`,
		)
		.join(", ");
	const reach =
		reached === undefined ? "" : `${count(reached, "practice", "practices", digits)} reached`;
	return [decided, reach].filter((clause) => clause !== "").join(" · ");
}

/** The frame and its head; the caller draws the bodies, one per day. */
function ReviewRunsFrame({ busy = false, children }: { busy?: boolean; children: ReactNode }) {
	return (
		// `shrink-0`: an `overflow-hidden` flex item has no automatic minimum size, so the level's
		// column would squash the frame and clip its last rows.
		<div className="shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table aria-label={REVIEWS_OF_YOUR_WORK} aria-busy={busy || undefined} className="min-w-152">
				<TableHeader>
					<TableRow>
						<TableHead className="w-24 whitespace-nowrap">Reviewed</TableHead>
						<TableHead>Work</TableHead>
						<TableHead>What it saw</TableHead>
						<TableHead className="w-48">
							<span className="sr-only">Actions</span>
						</TableHead>
						<TableHead className="w-28">
							<span className="sr-only">Open</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{children}
			</Table>
		</div>
	);
}

/**
 * The reviews that recorded something about the reader's own work, one body per day under its
 * heading, so a row carries the time alone.
 */
export function ReviewRunsTable({
	runs,
	filtered,
	onOpenReview,
	openReviewId,
	onReviewNow,
	requesting,
	positions,
}: ReviewRunsTableProps) {
	const days = groupReviewRunsByDay(runs, new Date(useNow()));
	// The newest row of a narrowed list is only the newest of what the filter kept.
	const latestId = filtered ? undefined : runs[0]?.reviewId;

	return (
		<ReviewRunsFrame>
			{runs.length === 0 && (
				<TableBody>
					<TableRow variant="static">
						<TableCell colSpan={COLUMNS} className="p-4 whitespace-normal">
							{filtered ? (
								<p className="text-sm text-muted-foreground">No reviews match your filters.</p>
							) : (
								<Empty>
									<EmptyHeader>
										<EmptyMedia variant="icon">
											<PulseIcon />
										</EmptyMedia>
										<EmptyTitle>No reviews of your work yet</EmptyTitle>
										<EmptyDescription>
											A review appears here once it records something about your work: a pull or
											merge request, an issue, a conversation or a document.
										</EmptyDescription>
									</EmptyHeader>
								</Empty>
							)}
						</TableCell>
					</TableRow>
				</TableBody>
			)}
			{days.map((day) => (
				<TableBody key={day.label}>
					<TableRow variant="static">
						{/* `border-t`: a body's last row drops its border, so the line above a day is
						    the heading's; the collapsed table draws one line where it meets the head. */}
						<th
							scope="rowgroup"
							colSpan={COLUMNS}
							className="border-t bg-sidebar px-2 py-1.5 text-left text-xs font-medium text-muted-foreground"
						>
							{day.label}
						</th>
					</TableRow>
					{day.runs.map((run) => (
						<ReviewRunRow
							key={run.reviewId}
							run={run}
							isLatest={run.reviewId === latestId}
							open={run.reviewId === openReviewId}
							onOpenReview={onOpenReview}
							onReviewNow={onReviewNow}
							isRequesting={
								requesting !== undefined && sameReviewedWork(requesting, run.reviewedWork)
							}
							position={positions?.get(run.reviewId)}
						/>
					))}
				</TableBody>
			))}
		</ReviewRunsFrame>
	);
}

interface ReviewRunRowProps {
	run: ProfileReviewRun;
	isLatest: boolean;
	open: boolean;
	onOpenReview: (reviewId: string) => void;
	onReviewNow?: (work: ReviewedWorkRef) => void;
	isRequesting: boolean;
	position?: number;
}

function ReviewRunRow({
	run,
	isLatest,
	open,
	onOpenReview,
	onReviewNow,
	isRequesting,
	position,
}: ReviewRunRowProps) {
	const { reviewedWork: work, reviewedAt: at } = run;
	const today = new Date(useNow());
	const WorkIcon = reviewedWorkIcon(work.kind, work.provider);
	const reached = reachedPhrase(run);

	return (
		<PracticeTableRow
			open={open}
			link={{
				text: "Open review",
				name: `of ${work.label}, ${formatDayTime(at, today)}`,
				onOpen: () => onOpenReview(run.reviewId),
			}}
		>
			<TableCell className={cn(OPEN_ROW_BAR, "align-top whitespace-nowrap")}>
				<span className="flex min-w-0 items-center gap-2">
					{run.status !== undefined && <StatusIcon def={REVIEW_RUN_STATE_DEFS[run.status]} />}
					<time dateTime={at.toISOString()} className="font-semibold">
						{formatTime(at)}
					</time>
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1">
					<span className="flex min-w-0 items-center gap-1.5">
						<WorkIcon className="size-3.5 shrink-0 text-muted-foreground" />
						<InlineLink href={work.url} external className="truncate font-medium">
							{work.label}
						</InlineLink>
					</span>
					{hasText(work.title) && (
						<span className="max-w-xs text-xs text-muted-foreground">{work.title}</span>
					)}
					<ReviewOrdinalTag position={position} />
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1.5">
					<span className="text-sm font-semibold">{slippedLead(run)}</span>
					{reached !== "" && <span className="text-xs text-muted-foreground">{reached}</span>}
					<span className="flex flex-wrap items-center gap-1.5">
						<FeedbackOnTheWork run={run} />
						{isLatest && <LatestReviewTag />}
						{run.triggerMode === "MANUAL" && <RequestedReviewTag />}
					</span>
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				{run.mayRequest && onReviewNow && (
					<Button
						type="button"
						variant="outline"
						size="xs"
						className={ROW_ACTION_PRESSED}
						disabled={isRequesting}
						onClick={() => onReviewNow(work)}
						aria-label={`${isRequesting ? "Requesting review…" : "Review this now"}: ${work.label}`}
					>
						<PlayIcon aria-hidden data-icon="inline-start" />
						{isRequesting ? "Requesting review…" : "Review this now"}
					</Button>
				)}
			</TableCell>
		</PracticeTableRow>
	);
}

/**
 * The row's own control under the pointer or focus, in the primary ground: a press anywhere else on
 * the row opens the review, and the accent is spent on one control per surface, never on a row.
 */
const ROW_ACTION_PRESSED =
	"hover:bg-primary hover:text-primary-foreground focus-visible:bg-primary focus-visible:text-primary-foreground dark:hover:bg-primary dark:hover:text-primary-foreground";

/**
 * The feedback this review left on the work, linked where it was left. A comment the provider cannot
 * address stays a count: a link that scrolls nowhere is a promise the row did not keep.
 */
function FeedbackOnTheWork({ run }: { run: ProfileReviewRun }) {
	const { feedbackDelivered, feedbackUrl, reviewedWork } = run;
	if (feedbackDelivered === 0) {
		return null;
	}
	if (!hasText(feedbackUrl)) {
		return (
			<span className="text-xs text-muted-foreground">
				{count(feedbackDelivered, "piece", "pieces")} of feedback
			</span>
		);
	}
	const noun = artifactKindNoun(reviewedWork.kind, 1, reviewedWork.provider);
	return (
		<InlineLink
			href={feedbackUrl}
			external
			className="text-xs"
			aria-label={`Read the feedback on the ${noun} ${reviewedWork.label}`}
		>
			Read the feedback on the {noun}
		</InlineLink>
	);
}

export function ReviewRunsTableSkeleton({ rows }: { rows: number }) {
	return (
		<ReviewRunsFrame busy>
			<TableBody>
				{Array.from({ length: rows }, (_, index) => (
					<TableRow key={index} variant="static" aria-hidden>
						<TableCell className="align-top">
							<Skeleton className="h-4 w-16" />
						</TableCell>
						<TableCell className="align-top">
							<Skeleton className="h-4 w-40" />
						</TableCell>
						<TableCell className="align-top">
							<Skeleton className="h-4 w-48" />
						</TableCell>
						<TableCell />
						<TableCell />
					</TableRow>
				))}
			</TableBody>
		</ReviewRunsFrame>
	);
}
