import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { Fragment, type ReactNode } from "react";

import type {
	FeedbackCheck,
	ReviewDetail,
	ReviewJob,
	ReviewPractice,
	ReviewResultCounts,
	ReviewUsage,
	ReviewUsageAttempt,
} from "@/api/types.gen";
import { formatTokens } from "@/components/admin/usage/usage-utils";
import { accountName } from "@/components/admin/users/account-labels";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { SectionStack } from "@/components/layout/SectionStack";
import { FeedbackChecks } from "@/components/practice-trace/FeedbackChecks";
import { PracticeScriptTraceLine } from "@/components/practice-trace/PracticeScriptTraceLine";
import { FOUND_BY_DESCRIPTIONS, FOUND_BY_LABELS } from "@/components/practice-trace/trace-format";
import { OUTCOME_DEFS, type Outcome } from "@/components/practice-vocabulary/outcome-defs";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import {
	isActiveReview,
	PARTIAL_REVIEW_SCOPE,
	reviewReasonSentence,
	REVIEW_JOB_PROCESSING_DEFS,
	REVIEW_JOB_STATUS_DEFS,
	REVIEW_PRACTICE_STATUS_DEFS,
	REVIEW_STATUS_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
} from "@/components/practice-vocabulary/review-status-defs";
import { buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { durationText } from "@/lib/dates";
import { itemKindNoun, itemName } from "@/lib/item-kinds";
import { formatCostUsd } from "@/lib/money";
import { hasText } from "@/lib/text";

import { firstStartedAt, jobPhases } from "./job-utils";
import { levelFoldKey } from "./level-folds";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import {
	type SheetEvent,
	SheetHistory,
	SheetSection,
	SheetStatusLine,
	SheetTechnical,
	type TechnicalFact,
} from "./RecordSheet";
import { RequestReviewAction } from "./RequestReviewAction";
import { reviewCacheUsage } from "./review-cache-usage";
import { feedbackSlots, type ReviewListTarget } from "./review-outcomes";
import { ReviewActions } from "./ReviewActions";
import { ReviewStatusMark } from "./ReviewBadges";
import { ReviewDayTime } from "./ReviewDayTime";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewListLink } from "./ReviewListLink";
import { ReviewPracticeScriptTable } from "./ReviewPracticeScriptTable";

export interface ReviewLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	review: PanelState<{ review: ReviewDetail }>;
	eventLabel: (name: string) => string;
	feedbackChecks?: PanelState<{ checks: FeedbackCheck[] }>;
	onCancel: () => void;
	cancelPending: boolean;
	/** Asks for a new review of the same work; omitted where the work cannot be reviewed on demand. */
	onRequestReview?: () => void;
	requestingReview?: boolean;
	/** Why no review can be asked for now, such as another review of the work queued or running. */
	requestBlocked?: string;
	/**
	 * The answer to the last ask, drawn by the route: why it started nothing, or the way to the review
	 * it created. This review stays as it is either way.
	 */
	requestOutcome?: ReactNode;
}

function kindLabel(review: ReviewDetail): string {
	return review.kind === "SCAN"
		? "Scan"
		: `Review of the ${itemKindNoun(review.item.kind, 1, review.item.provider)}`;
}

export const RUNTIME_EXPIRED = "Timing details are no longer available.";

/** "9 min" between two times, or undefined while either is missing or they are out of order. */
function between(from: Date | null | undefined, to: Date | null | undefined): string | undefined {
	return from === null ||
		from === undefined ||
		to === null ||
		to === undefined ||
		to.getTime() < from.getTime()
		? undefined
		: durationText((to.getTime() - from.getTime()) / 1000);
}

/** Attempts count from 0 on the wire; people count from 1. */
function attemptLabel(attempt: number): string {
	return `Attempt ${attempt + 1}`;
}

function reviewNow(review: ReviewDetail): string | undefined {
	if (review.status === "PARTIAL") {
		return PARTIAL_REVIEW_SCOPE[review.kind];
	}
	// The sentence names results, so a feedback count alone does not earn it.
	if ((review.status === "FAILED" || review.status === "CANCELLED") && hasResults(review.results)) {
		return "Observations recorded before it stopped remain in this review.";
	}
	return undefined;
}

export function ReviewLevel({
	workspaceSlug,
	nested,
	path,
	review: state,
	eventLabel,
	feedbackChecks,
	onCancel,
	cancelPending,
	onRequestReview,
	requestingReview = false,
	requestBlocked,
	requestOutcome,
}: ReviewLevelProps) {
	const review = state.status === "ready" ? state.review : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="review"
			loading={state.status === "loading"}
			chips={review && <ReviewStatusLine review={review} />}
			title={review && (review.item.title ?? itemName(review.item))}
			description={review && <ReviewItemLink item={review.item} />}
		/>
	);
	if (review === undefined) {
		return (
			<>
				{header}
				<DrawerBody className="flex flex-col gap-8 pt-2">
					{state.status === "error" ? (
						<>
							{/* A read that fails after an ask still leaves its answer, and the way to its review. */}
							{requestOutcome}
							<QueryErrorAlert
								error={state.error}
								title="We could not load this review"
								onRetry={state.onRetry}
							/>
						</>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}
	const cancellable = isActiveReview(review.status);
	const scripted = review.practices.filter((practice) => practice.script !== null);
	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{requestOutcome}
				<SectionStack>
					<SheetSection part="what" foldKey={levelFoldKey("review", "what")}>
						<ReviewWhat workspaceSlug={workspaceSlug} review={review} eventLabel={eventLabel} />
					</SheetSection>
					<SheetSection part="state" foldKey={levelFoldKey("review", "state")}>
						<ReviewHistory review={review} />
					</SheetSection>
					<SheetSection
						part="details"
						title="Every practice"
						foldKey={levelFoldKey("review", "practices")}
					>
						<ReviewPracticeList workspaceSlug={workspaceSlug} review={review} />
					</SheetSection>
					{feedbackChecks !== undefined && <ReviewFeedbackChecks state={feedbackChecks} />}
					{scripted.length > 0 && (
						<SheetSection
							part="details"
							title="Practice scripts"
							description="How each practice script ran in this review."
							foldKey={levelFoldKey("review", "practice-scripts")}
						>
							<ReviewPracticeScriptTable workspaceSlug={workspaceSlug} practices={scripted} />
							<p className="text-xs text-muted-foreground">
								Spend is on{" "}
								<InlineLink
									render={<Link to="/w/$workspaceSlug/admin/usage" params={{ workspaceSlug }} />}
								>
									AI usage
								</InlineLink>
								.
							</p>
						</SheetSection>
					)}
					<SheetTechnical
						facts={technicalFacts(review)}
						foldKey={levelFoldKey("review", "technical")}
					>
						{review.usage.attempts.length > 1 && <UsageAttempts attempts={review.usage.attempts} />}
						<ReviewJobs jobs={review.runtime?.jobs} />
						<GeneratedPaths review={review} />
					</SheetTechnical>
				</SectionStack>
			</DrawerBody>
			{(cancellable || onRequestReview !== undefined) && (
				<DrawerFooter>
					{cancellable && <ReviewActions isCancelling={cancelPending} onCancel={onCancel} />}
					{/* While this review still runs, another would only be coalesced into it. */}
					{!cancellable && onRequestReview !== undefined && (
						<RequestReviewAction
							label="Request another review"
							request={{
								work: itemName(review.item),
								onRequest: onRequestReview,
								requesting: requestingReview,
								blocked: requestBlocked,
							}}
						/>
					)}
				</DrawerFooter>
			)}
		</>
	);
}

/**
 * The review's own figure is its time from request to finish. Only that interval survives every
 * attempt; a run time would leave out the attempts before the latest.
 */
function ReviewStatusLine({ review }: { review: ReviewDetail }) {
	const started = firstStartedAt(review.runtime?.jobs ?? []);
	let at: { label: string; date: Date } = { label: "asked for", date: review.createdAt };
	if (review.completedAt !== null) {
		at = { label: "finished", date: review.completedAt };
	} else if (started !== undefined) {
		at = { label: "started", date: started };
	}
	const elapsed = between(review.createdAt, review.completedAt);
	return (
		<SheetStatusLine
			status={<ReviewStatusMark def={REVIEW_STATUS_DEFS[review.status]} />}
			figure={elapsed === undefined ? undefined : `${elapsed} from request`}
			at={at}
		/>
	);
}

const RESULT_OUTCOMES = [
	["MET", "met"],
	["NOT_MET", "notMet"],
	["DOES_NOT_APPLY", "doesNotApply"],
	["COULD_NOT_DETERMINE", "couldNotDetermine"],
] as const satisfies readonly (readonly [Outcome, keyof ReviewResultCounts])[];

function hasResults(counts: ReviewResultCounts): boolean {
	return RESULT_OUTCOMES.some(([, key]) => counts[key] > 0);
}

/**
 * One count, marked with its registry's icon, that opens a list at exactly the rows it counts. The
 * icon only repeats the word beside it.
 */
function CountLink({
	workspaceSlug,
	def,
	text,
	name,
	destination,
}: {
	workspaceSlug: string;
	def: StatusDef;
	text: string;
	/** Set where the same count can stand on several rows; it starts with `text`. */
	name: string | undefined;
	destination: ReviewListTarget;
}) {
	const Icon = def.icon;
	return (
		<span className="inline-flex items-center gap-1 whitespace-nowrap">
			<Icon aria-hidden className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))} />
			<InlineLink
				tone="count"
				aria-label={name}
				render={<ReviewListLink workspaceSlug={workspaceSlug} destination={destination} />}
			>
				{text}
			</InlineLink>
		</span>
	);
}

/**
 * Results by outcome, each opening the Observations tab at exactly those observations: the review's, or one
 * of its practices'. The words name only the outcome, since the fact or the row already says whose
 * results they are. Problems sit under Not met results and are not counted here: the link opens
 * them without a figure the sheet does not hold.
 */
function ResultCountLinks({
	workspaceSlug,
	counts,
	scope,
	practiceName,
	empty,
}: {
	workspaceSlug: string;
	counts: ReviewResultCounts;
	scope: { reviewId: string; practiceSlug?: string[] };
	/** The practice the counts are of, which tells each link from the same count on another row. */
	practiceName?: string;
	/** What the counts read as when there are none. */
	empty: string;
}) {
	const counted = RESULT_OUTCOMES.filter(([, key]) => counts[key] > 0);
	if (counted.length === 0) {
		return empty;
	}
	const named = (text: string) =>
		practiceName === undefined ? undefined : `${text}, ${practiceName}`;
	return (
		// One flex row, so the counts with their icons, the commas, the dot and the link share one middle line.
		<span className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
			{counted.map(([outcome, key], index) => {
				const text = `${String(counts[key])} ${OUTCOME_DEFS[outcome].label.toLowerCase()}`;
				return (
					<span key={outcome} className="inline-flex items-center">
						<CountLink
							workspaceSlug={workspaceSlug}
							def={OUTCOME_DEFS[outcome]}
							text={text}
							name={named(text)}
							destination={{ list: "results", search: { ...scope, outcome: [outcome] } }}
						/>
						{index < counted.length - 1 && ", "}
					</span>
				);
			})}
			{counts.notMet > 0 && (
				// The dot and the link wrap together, so a dot never ends a line on its own.
				<span className="inline-flex items-center gap-x-1.5">
					<DotSeparator />
					<InlineLink
						aria-label={named("View problems")}
						render={
							<ReviewListLink
								workspaceSlug={workspaceSlug}
								destination={{ list: "problems", search: scope }}
							/>
						}
					>
						View problems
					</InlineLink>
				</span>
			)}
		</span>
	);
}

/** The review's feedback by delivery family, each opening the Feedback tab at exactly those. */
function FeedbackCountLinks({
	workspaceSlug,
	review,
}: {
	workspaceSlug: string;
	review: ReviewDetail;
}) {
	// An empty scope still names each family's states; the review narrows them to its own feedback.
	const counted = feedbackSlots(review.feedback, {}).filter((slot) => slot.count > 0);
	if (counted.length === 0) {
		return "None available";
	}
	return (
		<span>
			{counted.map((slot, index) => (
				<Fragment key={slot.key}>
					{index > 0 && ", "}
					{slot.target?.list === "feedback" && (
						<CountLink
							workspaceSlug={workspaceSlug}
							def={slot.def}
							text={`${String(slot.count)} ${slot.label}`}
							name={undefined}
							destination={{
								list: "feedback",
								search: { ...slot.target.search, reviewId: review.id },
							}}
						/>
					)}
				</Fragment>
			))}
		</span>
	);
}

function ReviewWhat({
	workspaceSlug,
	review,
	eventLabel,
}: {
	workspaceSlug: string;
	review: ReviewDetail;
	eventLabel: (name: string) => string;
}) {
	const origin = REVIEW_ORIGIN_DEFS[review.origin];
	const event = review.startedBy;
	return (
		<ReviewFactGrid>
			<ReviewFact label="Kind">{kindLabel(review)}</ReviewFact>
			<ReviewFact label="Started by">
				<span className="space-y-0.5">
					<span className="block" title={origin.description}>
						{origin.label}
						{review.requestedBy !== null && ` by ${accountName(review.requestedBy)}`}
					</span>
					{event !== null && (
						<span className="block text-muted-foreground">
							{eventLabel(event.name)} · <ReviewDayTime at={event.occurredAt} /> ·{" "}
							<span title={FOUND_BY_DESCRIPTIONS[event.foundBy]}>
								{FOUND_BY_LABELS[event.foundBy]}
							</span>
						</span>
					)}
				</span>
			</ReviewFact>
			<ReviewFact label="Observations">
				<ResultCountLinks
					workspaceSlug={workspaceSlug}
					counts={review.results}
					scope={{ reviewId: review.id }}
					empty="None available"
				/>
			</ReviewFact>
			{review.kind !== "SCAN" && (
				<ReviewFact label="Feedback">
					<FeedbackCountLinks workspaceSlug={workspaceSlug} review={review} />
				</ReviewFact>
			)}
			{review.kind === "SCAN" && (
				<ReviewFact label="Report">
					<Link
						to="/w/$workspaceSlug/admin/practices/scans/$scanId"
						params={{ workspaceSlug, scanId: review.id }}
						className={buttonVariants({ variant: "link", size: "inline" })}
					>
						Open the scan report
					</Link>
				</ReviewFact>
			)}
		</ReviewFactGrid>
	);
}

function ReviewHistory({ review }: { review: ReviewDetail }) {
	return <SheetHistory events={reviewHistory(review)} now={reviewNow(review)} />;
}

export function reviewHistory(review: ReviewDetail): SheetEvent[] {
	const events: SheetEvent[] = [
		{
			key: "asked",
			at: review.createdAt,
			title: "Asked for",
			by: review.requestedBy === null ? undefined : accountName(review.requestedBy),
		},
	];
	const jobs = review.runtime?.jobs ?? [];
	const started = firstStartedAt(jobs);
	if (started !== undefined) {
		const queued = between(review.createdAt, started);
		events.push({
			key: "started",
			at: started,
			title: "Started",
			detail: queued === undefined ? undefined : `after ${queued} in the queue`,
		});
	}
	// A later attempt's queue runs from when that attempt became available, not from the request.
	for (const job of jobs.filter((entry) => entry.attempt > 0)) {
		if (job.startedAt !== null) {
			const queued = between(job.availableAt, job.startedAt);
			const wait = queued === undefined ? "" : `, after ${queued} in the queue`;
			events.push({
				key: `attempt-${job.id}`,
				at: job.startedAt,
				title: "Latest attempt started",
				detail: `${attemptLabel(job.attempt)}${wait}. When the review first started is no longer recorded.`,
			});
		} else if (job.status === "QUEUED") {
			events.push({
				key: `retry-${job.id}`,
				at: job.availableAt,
				title: "Retry due",
				detail: `${attemptLabel(job.attempt)} waits until then after an earlier attempt ended.`,
			});
		}
	}
	if (review.completedAt !== null) {
		const elapsed = between(review.createdAt, review.completedAt);
		events.push({
			key: "ended",
			at: review.completedAt,
			title: REVIEW_STATUS_DEFS[review.status].label,
			detail: (
				<>
					{elapsed !== undefined && <span className="block">{elapsed} after it was asked for</span>}
					{review.stopReason !== null && (
						<span className="block">{REVIEW_STOP_REASON_SENTENCES[review.stopReason]}</span>
					)}
				</>
			),
		});
	}
	return events;
}

function itemCountsLine(practice: ReviewPractice): string {
	const { reviewed, reused, withheld, notReviewed, listed } = practice.itemCounts;
	const counts: { count: number; label: string }[] = [
		{ count: reviewed - reused, label: "reviewed" },
		{ count: reused, label: "reused" },
		{ count: withheld, label: "withheld" },
		{ count: notReviewed, label: "not reviewed" },
		{ count: listed, label: "listed" },
	];
	return counts
		.filter(({ count }) => count > 0)
		.map(({ count, label }) => `${count} ${label}`)
		.join(", ");
}

/** Why a practice that has not finished stands where it does; a finished one lists its results. */
function practiceReason(practice: ReviewPractice): string | undefined {
	if (hasText(practice.reason)) {
		return reviewReasonSentence(practice.reason) ?? practice.reason;
	}
	// Once its script has a run, the trace line under it says how that run went.
	return practice.script === null
		? REVIEW_PRACTICE_STATUS_DEFS[practice.status].description
		: undefined;
}

function ReviewPracticeList({
	workspaceSlug,
	review,
}: {
	workspaceSlug: string;
	review: ReviewDetail;
}) {
	if (review.practices.length === 0) {
		return (
			<p className="text-sm text-muted-foreground">
				{isActiveReview(review.status)
					? "Practices appear here as the review reaches them."
					: "This review reached no practice, so there is nothing to list here."}
			</p>
		);
	}
	// The same Done on every row says nothing; the results line already shows each one finished.
	const showStatus = review.practices.some((practice) => practice.status !== "DONE");
	return (
		<ul aria-label="Every practice in this review" className="divide-y rounded-xl border">
			{review.practices.map((practice) => {
				const recorded = hasResults(practice.results);
				const reason = practiceReason(practice);
				return (
					<li
						key={practice.practiceSlug}
						data-practice-status={practice.status}
						className="@container px-3 py-2.5 text-sm"
					>
						<div
							className={cn(
								"grid min-w-0 items-start gap-x-4 gap-y-1.5",
								showStatus
									? "@lg:grid-cols-[9rem_minmax(0,1fr)_minmax(0,1fr)]"
									: "@lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]",
							)}
						>
							{showStatus && (
								<span>
									<StatusBadge def={REVIEW_PRACTICE_STATUS_DEFS[practice.status]} />
								</span>
							)}
							<span className="font-medium break-words">{practice.practiceName}</span>
							<span className="flex min-w-0 flex-col gap-1 text-pretty text-muted-foreground">
								{practice.status !== "DONE" && recorded && (
									<span>Observations recorded so far</span>
								)}
								{(practice.status === "DONE" || recorded) && (
									<span>
										<ResultCountLinks
											workspaceSlug={workspaceSlug}
											counts={practice.results}
											scope={{ reviewId: review.id, practiceSlug: [practice.practiceSlug] }}
											practiceName={practice.practiceName}
											empty="No observations"
										/>
									</span>
								)}
								{practice.status !== "DONE" &&
									(!recorded || hasText(practice.reason)) &&
									reason !== undefined && <span>{reason}</span>}
								{review.kind === "SCAN" && itemCountsLine(practice) !== "" && (
									<span className="text-xs">Files: {itemCountsLine(practice)}</span>
								)}
								{practice.script !== null && (
									<span className="text-xs">
										<PracticeScriptTraceLine
											workspaceSlug={workspaceSlug}
											practiceSlug={practice.practiceSlug}
											practiceName={practice.practiceName}
											reviewId={review.id}
											run={practice.script}
											details={false}
										/>
									</span>
								)}
							</span>
						</div>
					</li>
				);
			})}
		</ul>
	);
}

function costText(usage: ReviewUsage): string {
	const { attempts, costUsd, priced } = usage;
	if (attempts.length === 0) {
		return "No usage recorded";
	}
	if (priced && costUsd !== null) {
		const cost = formatCostUsd(Number(costUsd));
		return attempts.length > 1 ? `${cost} over ${attempts.length} attempts` : cost;
	}
	const unpriced = attempts.filter((attempt) => attempt.pricingState === "UNPRICED").length;
	return unpriced === 1
		? "Not known: 1 attempt has no price"
		: `Not known: ${unpriced} attempts have no price`;
}

function attemptCostText(attempt: ReviewUsageAttempt): string {
	if (attempt.pricingState === "NO_CHARGE") {
		return "no charge";
	}
	return attempt.costUsd === null ? "no price known" : formatCostUsd(Number(attempt.costUsd));
}

function UsageAttempts({ attempts }: { attempts: ReviewUsageAttempt[] }) {
	return (
		<Section level={4} size="sm" title="Attempts" className="space-y-2">
			<ol className="space-y-1 text-sm">
				{attempts.map((attempt) => (
					<li
						key={`${attempt.jobId}-${attempt.attempt}`}
						className="flex flex-wrap gap-x-2 tabular-nums"
					>
						<span className="font-medium">{attemptLabel(attempt.attempt)}</span>
						<span>{attemptCostText(attempt)}</span>
						<span className="text-muted-foreground">
							{formatTokens(attempt.inputTokens)} read · {formatTokens(attempt.outputTokens)}{" "}
							written · {formatTokens(attempt.calls)} calls
						</span>
					</li>
				))}
			</ol>
		</Section>
	);
}

function ReviewJobs({ jobs }: { jobs: ReviewJob[] | undefined }) {
	return (
		<Section level={4} size="sm" title="Timing" className="space-y-2">
			{jobs === undefined || jobs.length === 0 ? (
				<p className="text-sm text-muted-foreground">{RUNTIME_EXPIRED}</p>
			) : (
				<ol aria-label="Timing" className="space-y-3 text-sm">
					{jobs.map((job, index) => (
						<ReviewJobEntry
							key={job.id}
							job={job}
							position={jobs.length > 1 ? `${index + 1} of ${jobs.length} · ` : ""}
						/>
					))}
				</ol>
			)}
		</Section>
	);
}

function ReviewJobEntry({ job, position }: { job: ReviewJob; position: string }) {
	const phases = jobPhases(job);
	// The server records NONE until the job ends, so on an active job it means "not yet", not "nothing".
	const active = job.status === "QUEUED" || job.status === "RUNNING";
	return (
		<li className="min-w-0 space-y-1">
			<p className="flex flex-wrap items-center gap-2">
				<span className="font-medium">
					{position}
					{attemptLabel(job.attempt)}
				</span>
				<StatusBadge def={REVIEW_JOB_STATUS_DEFS[job.status]} />
				{(!active || job.processingStatus !== "NONE") && (
					<StatusBadge def={REVIEW_JOB_PROCESSING_DEFS[job.processingStatus]} />
				)}
			</p>
			<p className="text-muted-foreground tabular-nums">
				Wait to start: {phases.wait} · Running: {phases.execution}
				{job.exitCode !== null && ` · Exit code ${job.exitCode}`}
			</p>
			<p className="text-muted-foreground">
				Trace ID <code className="text-xs break-all">{job.traceId}</code>
			</p>
			{hasText(job.errorMessage) && (
				<pre className="max-h-48 overflow-auto rounded-md bg-muted/50 p-2 text-xs break-words whitespace-pre-wrap">
					{job.errorMessage}
				</pre>
			)}
		</li>
	);
}

const cachePercent = new Intl.NumberFormat("en-GB", {
	style: "percent",
	maximumFractionDigits: 1,
});

function inputFromCacheText({
	read,
	written,
	input,
	readShare,
}: ReturnType<typeof reviewCacheUsage>): string {
	if (readShare === undefined) {
		return "No usage recorded";
	}
	if (read === 0 && written === 0) {
		return "None reported";
	}
	const percentage =
		readShare > 0 && readShare < 0.001 ? "Under 0.1%" : cachePercent.format(readShare);
	return `${percentage} of ${formatTokens(input)} tokens`;
}

function technicalFacts(review: ReviewDetail): TechnicalFact[] {
	const { usage } = review;
	const cache = reviewCacheUsage(usage.attempts);
	const usagePending = isActiveReview(review.status) && usage.attempts.length === 0;
	let reasoning = 0;
	for (const attempt of usage.attempts) {
		reasoning += attempt.reasoningTokens;
	}
	const writtenTokens =
		reasoning > 0
			? `${formatTokens(usage.outputTokens)} (${formatTokens(reasoning)} of it reasoning)`
			: formatTokens(usage.outputTokens);
	const facts: TechnicalFact[] = [
		{ label: "Review ID", value: <code>{review.id}</code>, copy: review.id },
		...(hasText(review.itemVersion)
			? [
					{
						label: "Reviewed version",
						value: <code>{review.itemVersion}</code>,
						copy: review.itemVersion,
					},
				]
			: []),
		{ label: "Cost", value: usagePending ? "Not recorded yet" : costText(usage) },
		{ label: "Model", value: hasText(review.model) ? review.model : "—" },
		// The review's own calls: a practice script's decision, embedding and reranking calls are on
		// the Practice scripts section above.
		{
			label: "Review model calls",
			value: usagePending ? "Not recorded yet" : formatTokens(usage.calls),
		},
		{
			label: "Tokens read",
			value: usagePending ? "Not recorded yet" : formatTokens(usage.inputTokens),
		},
		{
			label: "Tokens written",
			value: usagePending ? "Not recorded yet" : writtenTokens,
		},
		{
			label: "Cache tokens",
			value: usagePending
				? "Not recorded yet"
				: `${formatTokens(cache.read)} read · ${formatTokens(cache.written)} written`,
		},
		{
			label: "Input from cache",
			value: usagePending ? "Not recorded yet" : inputFromCacheText(cache),
		},
	];
	if (review.batchId !== null) {
		facts.push({ label: "Batch ID", value: <code>{review.batchId}</code>, copy: review.batchId });
	}
	return facts;
}

function GeneratedPaths({ review }: { review: ReviewDetail }) {
	const { generatedPaths } = review;
	if (generatedPaths === null) {
		return null;
	}
	return (
		<Section
			level={4}
			size="sm"
			title="Generated paths"
			description="Kept as generated output, not judged as hand-written work."
			className="space-y-2 text-sm"
		>
			{generatedPaths.withheld ? (
				<p className="text-muted-foreground">
					{hasText(generatedPaths.reason)
						? generatedPaths.reason
						: "Generated paths were not checked for this review."}
				</p>
			) : (
				<>
					<p className="break-all">
						Patterns:{" "}
						{generatedPaths.patterns.length > 0
							? generatedPaths.patterns.join(", ")
							: "None configured"}
					</p>
					{generatedPaths.excludedPaths.length > 0 ? (
						<ul className="list-inside list-disc">
							{generatedPaths.excludedPaths.map((excluded) => (
								<li key={excluded}>
									<code className="break-all">{excluded}</code>
								</li>
							))}
						</ul>
					) : (
						<p className="text-muted-foreground">No changed paths matched.</p>
					)}
				</>
			)}
		</Section>
	);
}

function ReviewFeedbackChecks({ state }: { state: PanelState<{ checks: FeedbackCheck[] }> }) {
	if (state.status === "ready" && state.checks.length === 0) {
		return null;
	}
	return (
		<Section
			level={3}
			size="lg"
			title="Feedback checks of the review"
			foldKey={state.status === "error" ? undefined : levelFoldKey("review", "feedback-checks")}
			className="min-w-0"
		>
			{state.status === "loading" && <LevelBodySkeleton />}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load the feedback checks"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && <FeedbackChecks checks={state.checks} />}
		</Section>
	);
}
