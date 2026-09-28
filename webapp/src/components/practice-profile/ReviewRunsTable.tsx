import { PulseIcon } from "@primer/octicons-react";
import { PlayIcon } from "lucide-react";
import { Fragment } from "react";

import { cn } from "cn";
import type { ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { OPEN_ROW_BAR, PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	REVIEW_RUN_STATE_DEFS,
	runStateSpinClass,
} from "@/components/practice-vocabulary/review-run-state-defs";
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
import { useInView } from "@/hooks/use-in-view";
import { artifactKindNoun, sameReviewedWork } from "@/lib/artifact-kinds";
import { asDate, formatDayTime, formatTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { reachedPhrase, reviewRunCounts } from "./review-run-counts";
import {
	groupReviewRunsByDay,
	NOT_DATED,
	reviewOrdinalLabel,
	type RunPosition,
} from "./review-run-groups";
import { LatestRunTag, RequestedByHandTag, ReviewOrdinalTag } from "./review-run-tags";

export interface ReviewRunsTableProps {
	/** The runs as the wire ordered them: newest first. */
	runs: ProfileReviewRun[];
	/** Opens one run as the level over the list; without it a row is not a control. */
	onOpenRun?: (reviewId: string) => void;
	/** The run whose level is open over the list; its row keeps a bar on its leading edge. */
	openReviewId?: string;
	/**
	 * Asks for a review of one piece of work now. Offered only on the rows whose `mayRequest` says
	 * the request would be accepted; without it no row offers it.
	 */
	onReviewNow?: (run: ProfileReviewRun) => void;
	/**
	 * The work an ask is in flight about: the rows on that work say so rather than inviting a
	 * second, and every other row keeps its own control.
	 */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	/**
	 * Which run of its work each row is, by review id. Absent while the list does not hold every run
	 * of the work it would be counting, so a row says nothing rather than a position off a page.
	 */
	positions?: Map<string, RunPosition>;
	/** The runs before these and how the list reaches them; without it the list ends where it ends. */
	earlier?: EarlierRuns;
}

/** The pages still to come, as the end of the list reads them. */
export interface EarlierRuns {
	/** Earlier runs exist beyond the pages loaded so far. */
	hasMore: boolean;
	isLoading: boolean;
	/** Why the last page did not arrive; the rows already loaded stand and the end says so. */
	error?: unknown;
	onLoadMore: () => void;
}

/**
 * What slipped, which is what the row leads with: the practices this run recorded a problem about,
 * by name, because a name is the thing a reader can act on and a count of problems is not. Two of
 * them are named and the rest counted, since a row is one line wide and the run's own level lists
 * every one.
 *
 * A run with nothing against the reader says so plainly. A run that stopped early or is still going
 * says that instead: "Nothing to improve" about a review that never finished would be a claim the
 * review never made.
 */
function slippedLead(run: ProfileReviewRun): string {
	const names = run.slippedPractices.map((practice) => practice.practiceName);
	const shown = names.slice(0, LEAD_PRACTICES);
	if (shown.length > 0) {
		const rest = names.length - shown.length;
		return rest > 0 ? `${shown.join(", ")} +${rest} more` : shown.join(", ");
	}
	if (run.status === "FAILED") {
		return "Stopped before it finished";
	}
	if (run.status === "IN_PROGRESS") {
		return "Still running";
	}
	return "Nothing to improve";
}

/** How many slipped practices a row names before it counts the rest. */
const LEAD_PRACTICES = 2;

/**
 * What the run got through, under what it found: the counts the run's own head and practice table
 * are built from too ({@link reachedPhrase}). A run still going says what is coming rather than a
 * tally it has not made.
 */
function checkedPhrase(run: ProfileReviewRun): string {
	const phrase = reachedPhrase(reviewRunCounts(run));
	if (phrase === "" && run.status === "IN_PROGRESS") {
		return "Results appear as it finishes";
	}
	return phrase;
}

/**
 * Every review of the reader's own work as a table: the run's outcome, when it ran, what it ran on,
 * what slipped, and the way into it. Headings group the rows by the day they fall on and stay in
 * view while the rows under them scroll, so a row carries the time alone.
 */
export function ReviewRunsTable({
	runs,
	onOpenRun,
	openReviewId,
	onReviewNow,
	requesting,
	positions,
	earlier,
}: ReviewRunsTableProps) {
	const now = new Date(useNow());
	const days = groupReviewRunsByDay(runs, now);
	const newestId = runs[0]?.reviewId;

	return (
		// `shrink-0`: the frame is `overflow-hidden`, which zeroes a flex item's automatic minimum
		// size, so in the level's column it would otherwise be squashed to fit and clip its last rows
		// with nothing to scroll. The body above it is what scrolls.
		<div className="shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table aria-label="Reviews of your work" className="min-w-152">
				<TableHeader sticky>
					<TableRow>
						{/* Fixed and unwrapping: the column holds one time, and letting "11:13 am" break
						    over two lines would set the height of every row beside it. */}
						<TableHead className="w-24 whitespace-nowrap">Reviewed</TableHead>
						<TableHead>Work</TableHead>
						<TableHead>What it found</TableHead>
						<TableHead className="w-48">
							<span className="sr-only">Actions</span>
						</TableHead>
						<TableHead className="w-28">
							<span className="sr-only">Open</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{runs.length === 0 && (
						<TableRow variant="static">
							<TableCell colSpan={5} className="p-4 whitespace-normal">
								<Empty>
									<EmptyHeader>
										<EmptyMedia variant="icon">
											<PulseIcon />
										</EmptyMedia>
										<EmptyTitle>No review has run on your work yet.</EmptyTitle>
										<EmptyDescription>
											As soon as a review runs on a pull request, an issue or a thread of yours, it
											appears here — including the ones that found nothing to say.
										</EmptyDescription>
									</EmptyHeader>
								</Empty>
							</TableCell>
						</TableRow>
					)}
					{days.map((day) => (
						<Fragment key={day.label}>
							<TableRow variant="static">
								{/* A heading over the rows it covers, scoped to the group so a screen reader
								    reads it before each of them, and pinned under the column heads while they
								    scroll. */}
								<th
									scope="colgroup"
									colSpan={5}
									className="sticky top-10 z-10 bg-sidebar px-2 py-1.5 text-left text-xs font-medium text-muted-foreground"
								>
									{day.label}
								</th>
							</TableRow>
							{day.runs.map((run) => (
								<ReviewRunRow
									key={run.reviewId}
									run={run}
									isNewest={run.reviewId === newestId}
									open={run.reviewId === openReviewId}
									onOpenRun={onOpenRun}
									onReviewNow={onReviewNow}
									isRequesting={
										requesting !== undefined && sameReviewedWork(requesting, run.reviewedWork)
									}
									position={positions?.get(run.reviewId)}
								/>
							))}
						</Fragment>
					))}
					{earlier && <EarlierRunsRow {...earlier} />}
				</TableBody>
			</Table>
		</div>
	);
}

interface ReviewRunRowProps {
	run: ProfileReviewRun;
	isNewest: boolean;
	open: boolean;
	onOpenRun?: (reviewId: string) => void;
	onReviewNow?: (run: ProfileReviewRun) => void;
	isRequesting: boolean;
	position?: RunPosition;
}

function ReviewRunRow({
	run,
	isNewest,
	open,
	onOpenRun,
	onReviewNow,
	isRequesting,
	position,
}: ReviewRunRowProps) {
	const at = asDate(run.reviewedAt);
	const state = run.status === undefined ? undefined : REVIEW_RUN_STATE_DEFS[run.status];
	const WorkIcon = reviewedWorkIcon(run.reviewedWork.kind, run.reviewedWork.provider);
	const checked = checkedPhrase(run);
	const ordinal = position && reviewOrdinalLabel(position);

	return (
		<PracticeTableRow
			open={open}
			link={
				onOpenRun && {
					text: "Open run",
					name: at ? formatDayTime(at) : run.reviewedWork.label,
					onOpen: () => onOpenRun(run.reviewId),
				}
			}
		>
			<TableCell className={cn(OPEN_ROW_BAR, "align-top whitespace-nowrap")}>
				<span className="flex min-w-0 items-start gap-2">
					{state !== undefined && (
						<state.icon
							className={cn(
								"mt-0.5 size-4 shrink-0",
								statusToneClass(state.badgeVariant),
								runStateSpinClass(run.status),
							)}
							aria-label={state.label}
						/>
					)}
					{at ? (
						// The day is the heading over the row; the row says the time of it.
						<time dateTime={at.toISOString()} className="font-semibold">
							{formatTime(at)}
						</time>
					) : (
						<span className="text-muted-foreground">{NOT_DATED}</span>
					)}
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1">
					<span className="flex min-w-0 items-center gap-1.5">
						<WorkIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
						{/* The row is not itself a link — its opener is the "Open run" cell — so the work's
						    own address can be a link here without competing with it. */}
						{hasText(run.reviewedWork.url) ? (
							<InlineLink href={run.reviewedWork.url} external className="truncate font-medium">
								{run.reviewedWork.label}
							</InlineLink>
						) : (
							<span className="truncate font-medium">{run.reviewedWork.label}</span>
						)}
					</span>
					{hasText(run.reviewedWork.title) && (
						<span className="max-w-xs text-xs text-muted-foreground">{run.reviewedWork.title}</span>
					)}
					{/* Under the work, because it is a fact about the work rather than about the run:
					    two reviews of one pull request are two rows here, and the second of them says
					    so where a reader is already reading which work it is. */}
					{ordinal !== undefined && <ReviewOrdinalTag label={ordinal} size="xs" />}
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1.5">
					<span className="text-sm font-semibold">{slippedLead(run)}</span>
					{checked !== "" && <span className="text-xs text-muted-foreground">{checked}</span>}
					<span className="flex flex-wrap items-center gap-1.5">
						<FeedbackOnTheWork run={run} />
						{isNewest && <LatestRunTag />}
						{run.triggerMode === "MANUAL" && <RequestedByHandTag />}
					</span>
				</span>
			</TableCell>
			<TableCell className="align-top whitespace-normal">
				<RunActions
					run={run}
					onReviewNow={onReviewNow}
					isRequesting={isRequesting}
					label={run.reviewedWork.label}
				/>
			</TableCell>
		</PracticeTableRow>
	);
}

interface RunActionsProps {
	run: ProfileReviewRun;
	onReviewNow?: (run: ProfileReviewRun) => void;
	isRequesting: boolean;
	/** What the work is called, so the control's name says which row it belongs to. */
	label: string;
}

/**
 * The one thing a reader can do from a row about the work itself rather than about the run. The
 * work's own page is already a link in the cell beside it, and the feedback the run left on it is a
 * link under what it found, so a button that only repeated one of them would be a third way to the
 * same two places.
 *
 * "Review this now" appears only where the request would be accepted — the reader wrote the work,
 * it is assigned to them, or they administer the workspace — because a control that always refuses
 * is worse than none (`webapp/AGENTS.md` § Role-based gating).
 */
function RunActions({ run, onReviewNow, isRequesting, label }: RunActionsProps) {
	if (!run.mayRequest || onReviewNow === undefined) {
		return null;
	}
	return (
		<Button
			type="button"
			variant="outline"
			size="xs"
			className={ROW_ACTION_PRESSED}
			disabled={isRequesting}
			onClick={() => onReviewNow(run)}
			aria-label={`Review ${label} now`}
		>
			<PlayIcon aria-hidden data-icon="inline-start" />
			{isRequesting ? "Asking…" : "Review this now"}
		</Button>
	);
}

/**
 * The row's one control under the pointer or with focus: the `default` variant's own tone, so the
 * thing the press will hit is unambiguous on a row where a press anywhere opens the run. The tone
 * is the primary ground rather than an accent, which a practice surface spends on one control per
 * surface and never on a row.
 */
const ROW_ACTION_PRESSED =
	"hover:bg-primary hover:text-primary-foreground focus-visible:bg-primary focus-visible:text-primary-foreground dark:hover:bg-primary dark:hover:text-primary-foreground";

/**
 * The feedback this run left on the work, as the way to read it where it was left. A count alone is
 * a fact the reader can do nothing with; the same count as a link is the words themselves, one
 * press away, on the pull request or the issue they were written under.
 *
 * Where the provider's comment cannot be addressed the count stays a count, because a link that
 * lands on the work's own page and scrolls nowhere is a promise the row did not keep.
 */
function FeedbackOnTheWork({ run }: { run: ProfileReviewRun }) {
	const { feedbackDelivered, feedbackUrl, reviewedWork } = run;
	if (feedbackDelivered === 0) {
		return null;
	}
	const noun = artifactKindNoun(reviewedWork.kind, 1, reviewedWork.provider);
	// The feedback sits on the work itself, so the work's own page is an honest destination when
	// the comment's address is not known; the count stays only when even that page is missing.
	const href = hasText(feedbackUrl) ? feedbackUrl : reviewedWork.url;
	if (!hasText(href)) {
		return (
			<span className="text-xs text-muted-foreground">
				{feedbackDelivered} {feedbackDelivered === 1 ? "piece" : "pieces"} of feedback
			</span>
		);
	}
	return (
		<InlineLink
			href={href}
			external
			className="text-xs"
			aria-label={`Read the feedback on the ${noun} ${reviewedWork.label}`}
		>
			Read the feedback on the {noun}
		</InlineLink>
	);
}

/** One block per row the list will show, for a caller that draws the wait outside the table. */
export function ReviewRunsTableSkeleton({ rows }: { rows: number }) {
	return (
		<div className="flex flex-col gap-2" aria-busy="true">
			<span className="sr-only">Loading reviews of your work</span>
			{Array.from({ length: rows }, (_, index) => (
				<Skeleton key={index} className="h-12 w-full" />
			))}
		</div>
	);
}

/**
 * The end of the list, which is where the runs before these are asked for. A reader who has read to
 * the bottom has said what a press would have said, so the page loads itself as this row comes into
 * view and the list keeps the runs it already has.
 *
 * A load that failed is the one case that asks: it says so where the reader is looking, politely
 * enough not to interrupt them, and offers the press back rather than retrying under them forever.
 */
function EarlierRunsRow({ hasMore, isLoading, error, onLoadMore }: EarlierRuns) {
	// Off while a page is in flight and off once a load has failed, so the sentinel cannot ask twice
	// for a page nor spin on an endpoint that is answering with an error.
	const sentinel = useInView(onLoadMore, hasMore && !isLoading && error == null);
	if (!hasMore && error == null) {
		return null;
	}
	return (
		<TableRow variant="static" ref={sentinel}>
			<TableCell colSpan={5} className="p-3 whitespace-normal">
				{error == null ? (
					<span aria-live="polite" className="text-sm text-muted-foreground">
						{isLoading ? "Loading earlier reviews…" : ""}
					</span>
				) : (
					<span className="flex flex-wrap items-center gap-2 text-sm">
						<span className="text-muted-foreground">Could not load earlier reviews.</span>
						<Button type="button" variant="outline" size="xs" onClick={onLoadMore}>
							Show earlier runs
						</Button>
					</span>
				)}
			</TableCell>
		</TableRow>
	);
}
