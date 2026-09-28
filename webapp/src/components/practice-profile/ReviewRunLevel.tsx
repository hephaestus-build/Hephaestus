import { ExternalLinkIcon, PlayIcon } from "lucide-react";
import { type ReactNode, useEffect, useRef } from "react";

import type {
	GetArtifactTraceResponse,
	ObservationDetail,
	PracticeGroup,
	ProfileReviewRun,
	ReviewRequestOutcome,
} from "@/api/types.gen";
import type { LoadState } from "@/components/common/panel-state";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { occurrenceDomId } from "@/components/practice-trace/trace-format";
import { TraceRefusalAlert } from "@/components/practice-trace/TraceRefusalAlert";
import { TraceSignalTimeline } from "@/components/practice-trace/TraceSignalTimeline";
import {
	REVIEW_RUN_STATE_DEFS,
	runStateSpinClass,
} from "@/components/practice-vocabulary/review-run-state-defs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { asDate, formatDayTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { RUN_TABS, type RunTab } from "./practice-profile-search";
import { reachedOfListedLabel, reviewRunCounts } from "./review-run-counts";
import { reviewOrdinalLabel, type RunPosition } from "./review-run-groups";
import { RequestedByHandTag, ReviewOrdinalTag } from "./review-run-tags";
import {
	type ReviewRunPracticeFilters,
	ReviewRunPracticeTable,
	runPractices,
} from "./ReviewRunPracticeTable";

export interface ReviewRunLevelProps {
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	/** The run, once it is in; the header waits for it rather than inventing a title. */
	run?: ProfileReviewRun;
	/** Every observation the run made about the reader, as the wire ordered them. */
	observations?: ObservationDetail[];
	/**
	 * This work's whole review activity: every practice's answer, the quiet ones included, and every
	 * occurrence recorded about it. Without it the tabs have their counts but no rows.
	 */
	trace?: GetArtifactTraceResponse;
	/**
	 * The activity's own load state: it is a second read that lands after the run, so the tabs wait
	 * for it and say when it failed rather than claiming the run reached no practice. Ready when
	 * absent.
	 */
	traceState?: LoadState;
	/**
	 * Which run of its work this one is, when that is known. Absent while the list the count comes
	 * from is narrowed by a filter, loaded only in part, or still loading; the head then says nothing
	 * rather than a count it cannot stand behind.
	 */
	positionOnWork?: RunPosition;
	/** The workspace's practice groups, for the icon and colour a group's name is drawn in. */
	groups?: PracticeGroup[];
	/** Opens a practice's own level from its pill. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** The tab shown, from the route's `runTab` search param. */
	tab: RunTab;
	onTabChange?: (tab: RunTab) => void;
	/** The practice table's filters, from the level's own search params. */
	filters?: ReviewRunPracticeFilters;
	onFiltersChange?: (filters: ReviewRunPracticeFilters) => void;
	/**
	 * Asks for a review of this work now. Drawn only when the run's `mayRequest` says the request
	 * would be accepted; without it there is no button.
	 */
	onReviewNow?: () => void;
	isRequesting?: boolean;
	/** Why the last ask started nothing; a started one says so in a toast and leaves nothing here. */
	reviewRefusal?: ReviewRequestOutcome;
	/** Where a refusal's fix link goes, for the readers who can apply it. */
	workspaceSlug: string;
	/** Admins also read how much of the practice set the run reached and each practice's operating facts. */
	canAdminister?: boolean;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

const NO_OBSERVATIONS: ObservationDetail[] = [];
const NO_GROUPS: PracticeGroup[] = [];
const NO_FILTERS: ReviewRunPracticeFilters = {};
const READY: LoadState = { status: "ready" };

const TAB_LABELS: Record<RunTab, string> = {
	practices: "Every practice",
	noticed: "What we noticed",
};

/**
 * One review run as the level over the list: what it ran on, when, and what every practice made of
 * it — the ones that said something about the reader and the ones that stayed quiet, in one table —
 * with everything recorded about the work beside it. The responses to a piece of feedback are
 * deliberately not here: a response belongs to the feedback, and its home is the card on the page
 * below, which "Read the feedback" goes to.
 */
export function ReviewRunLevel({
	nested,
	path,
	run,
	observations = NO_OBSERVATIONS,
	trace,
	traceState = READY,
	positionOnWork,
	groups = NO_GROUPS,
	onOpenPractice,
	tab,
	onTabChange,
	filters = NO_FILTERS,
	onFiltersChange,
	onReviewNow,
	isRequesting = false,
	reviewRefusal,
	workspaceSlug,
	canAdminister = false,
	isLoading,
	error,
	onRetry,
}: ReviewRunLevelProps) {
	const practices = runPractices(trace?.practices ?? [], run?.reviewId);
	const signals = trace?.signals ?? [];
	// Every observation of a practice, not the last one to be seen: one run can record several about
	// one practice, and a cell that kept one of them would drop what the others said.
	const observationsByPractice: Record<string, ObservationDetail[] | undefined> = {};
	for (const observation of observations) {
		const recorded = observationsByPractice[observation.practiceSlug] ?? [];
		recorded.push(observation);
		observationsByPractice[observation.practiceSlug] = recorded;
	}
	// An occurrence asked for from the practice table lives on the other tab, so the jump is two
	// steps: open that tab, then land on the occurrence once the timeline that draws it exists.
	const pendingOccurrence = useRef<string>(undefined);
	useEffect(() => {
		const id = pendingOccurrence.current;
		if (id === undefined || tab !== "noticed") {
			return;
		}
		pendingOccurrence.current = undefined;
		document.getElementById(occurrenceDomId(id))?.focus();
	}, [tab]);

	// The activity is the tabs' rows; until it is in, neither tab can say what the run reached.
	let traceBody: ReactNode;
	if (traceState.status === "error") {
		traceBody = (
			<QueryErrorAlert
				error={traceState.error}
				title="Could not load this work's review activity"
				onRetry={traceState.onRetry}
			/>
		);
	} else if (traceState.status === "loading") {
		traceBody = (
			<div className="flex flex-col gap-3" aria-busy="true">
				<span className="sr-only">Loading the review activity of this work</span>
				<Skeleton className="h-24 w-full" />
				<Skeleton className="h-24 w-full" />
			</div>
		);
	}

	// A tab counts its rows once it has them; before that a nought would be a claim.
	const counts: Record<RunTab, number | undefined> =
		traceState.status === "ready"
			? { practices: practices.length, noticed: signals.length }
			: { practices: undefined, noticed: undefined };

	let body: ReactNode;
	if (error != null) {
		body = (
			<QueryErrorAlert error={error} title="Could not load this review run" onRetry={onRetry} />
		);
	} else if (isLoading) {
		body = (
			<div className="flex flex-col gap-3" aria-busy="true">
				<span className="sr-only">Loading this review run</span>
				<PracticeTabsSkeleton tabs={2} />
				<Skeleton className="h-24 w-full" />
				<Skeleton className="h-24 w-full" />
			</div>
		);
	} else if (run === undefined) {
		body = (
			<p className="text-sm text-muted-foreground">
				This review run is not one of yours, or it is no longer on record.
			</p>
		);
	} else {
		body = (
			<>
				{/* No lead sentence here: the table below says what each practice made of this work,
				    per practice and in the words the review wrote, so a summary over it can only
				    restate or contradict them. `lead` stays on the wire for the list that uses it. */}
				<Tabs
					value={tab}
					onValueChange={(next) => {
						const chosen = RUN_TABS.find((candidate) => candidate === next);
						if (chosen) {
							onTabChange?.(chosen);
						}
					}}
					className="gap-4"
				>
					<PracticeTabsRail>
						<PracticeTabsList aria-label="This run">
							{RUN_TABS.map((candidate) => (
								<PracticeTabsTrigger key={candidate} value={candidate} count={counts[candidate]}>
									{TAB_LABELS[candidate]}
								</PracticeTabsTrigger>
							))}
						</PracticeTabsList>
					</PracticeTabsRail>
					<TabsContent value="practices" className="min-w-0">
						{traceBody ?? (
							<ReviewRunPracticeTable
								entries={practices}
								signals={signals}
								observationsByPractice={observationsByPractice}
								groups={groups}
								filters={filters}
								onFiltersChange={onFiltersChange}
								onOpenPractice={onOpenPractice}
								onShowOccurrence={(signalId) => {
									pendingOccurrence.current = signalId;
									onTabChange?.("noticed");
								}}
								canAdminister={canAdminister}
							/>
						)}
					</TabsContent>
					<TabsContent value="noticed" className="min-w-0">
						{traceBody ?? (
							<TraceSignalTimeline
								signals={signals}
								workspaceSlug={workspaceSlug}
								canAdminister={canAdminister}
							/>
						)}
					</TabsContent>
				</Tabs>
			</>
		);
	}

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<RunHead
					path={path}
					run={run}
					positionOnWork={positionOnWork}
					listed={traceState.status === "ready" ? practices.length : undefined}
					onReviewNow={onReviewNow}
					isRequesting={isRequesting}
					reviewRefusal={reviewRefusal}
					workspaceSlug={workspaceSlug}
					canAdminister={canAdminister}
				/>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-4 pt-2">{body}</DrawerBody>
		</>
	);
}

interface RunHeadProps {
	path: LevelPath;
	run: ProfileReviewRun | undefined;
	positionOnWork: RunPosition | undefined;
	/** Every practice the table below lists, so the head's ratio and the table's count agree. */
	listed: number | undefined;
	onReviewNow?: () => void;
	isRequesting: boolean;
	reviewRefusal?: ReviewRequestOutcome;
	workspaceSlug: string;
	canAdminister: boolean;
}

/**
 * What the run ran on and the two things a reader can do about the work itself. "Review this now"
 * is drawn only where the request would be accepted, which `mayRequest` on the run says, because a
 * control that always refuses is worse than none (`webapp/AGENTS.md` § Role-based gating); a
 * refusal that does come back is said here rather than in a toast, since it is the answer they
 * asked for.
 */
function RunHead({
	path,
	run,
	positionOnWork,
	listed,
	onReviewNow,
	isRequesting,
	reviewRefusal,
	workspaceSlug,
	canAdminister,
}: RunHeadProps) {
	const work = run?.reviewedWork;
	const at = run === undefined ? undefined : asDate(run.reviewedAt);
	const WorkIcon = reviewedWorkIcon(work?.kind, work?.provider);
	const state = run?.status === undefined ? undefined : REVIEW_RUN_STATE_DEFS[run.status];
	const canAsk = run?.mayRequest === true && onReviewNow !== undefined;
	const coverage = run && reachedOfListedLabel(reviewRunCounts(run, listed));
	const ordinal = positionOnWork && reviewOrdinalLabel(positionOnWork);

	return (
		<div className="flex min-w-0 flex-1 flex-col gap-2">
			<DetailPath {...path} current="Run" />
			<p className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
				<WorkIcon className="size-3.5 shrink-0" aria-hidden />
				<span className="truncate">
					{artifactKindLabel(work?.kind)}
					{hasText(work?.repositoryName) && `, ${work.repositoryName}`}
				</span>
			</p>
			<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
				{work === undefined ? "Review run" : (work.title ?? work.label)}
			</DrawerTitle>
			<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm text-muted-foreground">
				{at && (
					<span>
						Reviewed{" "}
						<time dateTime={at.toISOString()} className="text-foreground">
							{formatDayTime(at)}
						</time>
					</span>
				)}
				{canAdminister && hasText(coverage) && <span>{coverage}</span>}
				{state && (
					<Badge variant={state.badgeVariant}>
						<state.icon className={runStateSpinClass(run?.status)} aria-hidden />
						{state.label}
					</Badge>
				)}
				{run?.triggerMode === "MANUAL" && <RequestedByHandTag />}
				{hasText(ordinal) && <ReviewOrdinalTag label={ordinal} />}
			</div>
			{(hasText(work?.url) || canAsk) && (
				<div className="flex flex-wrap items-center gap-2 pt-1">
					{hasText(work?.url) && (
						<Button
							variant="outline"
							render={<a href={work.url} target="_blank" rel="noopener noreferrer" />}
						>
							<ExternalLinkIcon aria-hidden data-icon="inline-start" />
							Open the original
							<span className="sr-only"> (opens in a new tab)</span>
						</Button>
					)}
					{canAsk && (
						<Button type="button" disabled={isRequesting} onClick={onReviewNow}>
							<PlayIcon aria-hidden data-icon="inline-start" />
							{isRequesting ? "Asking…" : "Review this now"}
						</Button>
					)}
				</div>
			)}
			{reviewRefusal && (
				<TraceRefusalAlert
					refusal={reviewRefusal}
					workspaceSlug={workspaceSlug}
					canAdminister={canAdminister}
				/>
			)}
		</div>
	);
}
