import { cn } from "cn";
import { ExternalLinkIcon, PlayIcon } from "lucide-react";
import type { ReactNode } from "react";

import type {
	GetArtifactTraceResponse,
	ObservationDetail,
	PracticeGroup,
	ProfileReviewRun,
	ReviewedWorkRef,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import {
	type ReviewRunPracticeFilters,
	ReviewRunPracticeTable,
	ReviewRunPracticeTableSkeleton,
	runPractices,
} from "@/components/practice-trace/ReviewRunPracticeTable";
import { TraceSignalTimeline } from "@/components/practice-trace/TraceSignalTimeline";
import { useOccurrenceJump } from "@/components/practice-trace/use-occurrence-jump";
import { count } from "@/components/practice-vocabulary/feedback-text";
import { REVIEW_RUN_STATE_DEFS } from "@/components/practice-vocabulary/review-run-state-defs";
import { Button, buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { artifactKindLabel, sameReviewedWork } from "@/lib/artifact-kinds";
import { formatDayTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { REVIEW_TABS, type ReviewTab } from "./practice-profile-search";
import { RequestedReviewTag, ReviewOrdinalTag } from "./review-run-tags";

/**
 * One review and what it observed about the reader, with this work's review activity: every
 * practice's answer, asked for this review, and every occurrence recorded about the work. The
 * activity is its own read, so the tabs wait for it rather than claim the review reached nothing.
 */
export type ProfileReviewDetailState = PanelState<{
	run: ProfileReviewRun;
	observations: ObservationDetail[];
	activity: PanelState<{ trace: GetArtifactTraceResponse }>;
}>;

export interface ProfileReviewLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: ProfileReviewDetailState;
	/** The review's position among the reviews of its work, when every review is known. */
	positionOnWork?: number;
	/** For the icon and colour a group's name is drawn in. */
	groups: PracticeGroup[];
	onOpenPractice: (practiceSlug: string) => void;
	tab: ReviewTab;
	onTabChange: (tab: ReviewTab) => void;
	filters: ReviewRunPracticeFilters;
	onFiltersChange: (filters: ReviewRunPracticeFilters) => void;
	/** Offered only where the review's `mayRequest` allows it. */
	onReviewNow?: (work: ReviewedWorkRef) => void;
	/** The work an ask is in flight about. */
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	/**
	 * Why the last ask from this level started nothing, drawn by the route with the fix only an
	 * admin can apply. It is said here rather than in a toast: it is the answer they asked for.
	 */
	refusal?: ReactNode;
	/** Where an occurrence's fix link goes, for the readers who can apply it. */
	workspaceSlug: string;
	/** Admins also read each practice's operating facts and each occurrence's fix. */
	canAdminister?: boolean;
}

const SKELETON_ROWS = 4;

const TAB_LABELS: Record<ReviewTab, string> = {
	practices: "Every practice",
	noticed: "What we noticed",
};

function LoadingBody() {
	return (
		<div className="flex flex-col gap-4">
			<PracticeTabsSkeleton tabs={REVIEW_TABS.length} />
			<ReviewRunPracticeTableSkeleton rows={SKELETON_ROWS} />
		</div>
	);
}

/**
 * One review of the reader's work as the level over the list: what it reviewed, when, and what every
 * practice made of it. Responses to feedback live on the feedback card, not here.
 */
export function ProfileReviewLevel({
	nested,
	path,
	state,
	positionOnWork,
	groups,
	onOpenPractice,
	tab,
	onTabChange,
	filters,
	onFiltersChange,
	onReviewNow,
	requesting,
	refusal,
	workspaceSlug,
	canAdminister = false,
}: ProfileReviewLevelProps) {
	const showOccurrence = useOccurrenceJump(tab === "noticed", () => onTabChange("noticed"));

	const run = state.status === "ready" ? state.run : undefined;
	const activity = state.status === "ready" ? state.activity : undefined;
	const trace = activity?.status === "ready" ? activity.trace : undefined;
	const practices = runPractices(trace?.practices ?? [], run?.reviewId);
	const signals = trace?.signals ?? [];

	let body: ReactNode;
	if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="Could not load this review"
				onRetry={state.onRetry}
			/>
		);
	} else if (state.status === "loading") {
		body = <LoadingBody />;
	} else if (state.activity.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.activity.error}
				title="Could not load this work's review activity"
				onRetry={state.activity.onRetry}
			/>
		);
	} else if (state.activity.status === "loading") {
		body = <LoadingBody />;
	} else {
		const observationsByPractice: Record<string, ObservationDetail[] | undefined> = {};
		for (const observation of state.observations) {
			const recorded = observationsByPractice[observation.practiceSlug] ?? [];
			recorded.push(observation);
			observationsByPractice[observation.practiceSlug] = recorded;
		}
		const counts: Record<ReviewTab, number> = {
			practices: practices.length,
			noticed: signals.length,
		};
		body = (
			<Tabs
				value={tab}
				onValueChange={(next) => {
					const chosen = REVIEW_TABS.find((candidate) => candidate === next);
					if (chosen) {
						onTabChange(chosen);
					}
				}}
				className="gap-4"
			>
				<PracticeTabsRail>
					<PracticeTabsList aria-label="This review">
						{REVIEW_TABS.map((candidate) => (
							<PracticeTabsTrigger key={candidate} value={candidate} count={counts[candidate]}>
								{TAB_LABELS[candidate]}
							</PracticeTabsTrigger>
						))}
					</PracticeTabsList>
				</PracticeTabsRail>
				<TabsContent value="practices" className="min-w-0">
					<ReviewRunPracticeTable
						entries={practices}
						signals={signals}
						observationsByPractice={observationsByPractice}
						groups={groups}
						filters={filters}
						onFiltersChange={onFiltersChange}
						onOpenPractice={onOpenPractice}
						onShowOccurrence={showOccurrence}
						canAdminister={canAdminister}
						emptyMessage={
							state.run.status === "IN_PROGRESS"
								? "Practices appear here as the review reaches them."
								: "This review reached no practice, so there is nothing to list here."
						}
					/>
				</TabsContent>
				<TabsContent value="noticed" className="min-w-0">
					<TraceSignalTimeline
						signals={signals}
						workspaceSlug={workspaceSlug}
						canAdminister={canAdminister}
					/>
				</TabsContent>
			</Tabs>
		);
	}

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<ReviewHead
					path={path}
					run={run}
					positionOnWork={positionOnWork}
					listed={trace === undefined ? undefined : practices.length}
					onReviewNow={onReviewNow}
					requesting={requesting}
					refusal={refusal}
				/>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-4 pt-2">{body}</DrawerBody>
		</>
	);
}

interface ReviewHeadProps {
	path: LevelPath;
	run: ProfileReviewRun | undefined;
	positionOnWork: number | undefined;
	/** Every practice the table lists, so the head's ratio and the table's count agree. */
	listed: number | undefined;
	onReviewNow?: (work: ReviewedWorkRef) => void;
	requesting?: Pick<ReviewedWorkRef, "kind" | "id">;
	refusal?: ReactNode;
}

/** What the review looked at and what a reader can do about the work. */
function ReviewHead({
	path,
	run,
	positionOnWork,
	listed,
	onReviewNow,
	requesting,
	refusal,
}: ReviewHeadProps) {
	const work = run?.reviewedWork;
	const at = run?.reviewedAt;
	const WorkIcon = reviewedWorkIcon(work?.kind, work?.provider);
	const reached = run?.practicesEvaluated;
	const isRequesting =
		work !== undefined && requesting !== undefined && sameReviewedWork(requesting, work);
	const canAsk = run?.mayRequest === true && onReviewNow !== undefined;

	return (
		<div className="flex min-w-0 flex-1 flex-col gap-2">
			<DetailPath {...path} current="Review" />
			<p className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
				<WorkIcon className="size-3.5 shrink-0" aria-hidden />
				<span className="truncate">
					{artifactKindLabel(work?.kind)}
					{hasText(work?.repositoryName) && `, ${work.repositoryName}`}
				</span>
			</p>
			<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
				{hasText(work?.title) ? work.title : (work?.label ?? "Review")}
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
				{reached !== undefined && listed !== undefined && (
					<span>
						{reached} of {count(listed, "practice", "practices", true)} reached
					</span>
				)}
				{run?.status !== undefined && <StatusBadge def={REVIEW_RUN_STATE_DEFS[run.status]} />}
				{run?.triggerMode === "MANUAL" && <RequestedReviewTag />}
				<ReviewOrdinalTag position={positionOnWork} />
			</div>
			{work && (hasText(work.url) || canAsk) && (
				<div className="flex flex-wrap items-center gap-2 pt-1">
					{hasText(work.url) && (
						<a
							href={work.url}
							target="_blank"
							rel="noopener noreferrer"
							className={cn(buttonVariants({ variant: "outline" }))}
						>
							<ExternalLinkIcon aria-hidden data-icon="inline-start" />
							Open the original
							<span className="sr-only"> (opens in a new tab)</span>
						</a>
					)}
					{canAsk && (
						<Button type="button" disabled={isRequesting} onClick={() => onReviewNow(work)}>
							<PlayIcon aria-hidden data-icon="inline-start" />
							{isRequesting ? "Asking…" : "Review this now"}
						</Button>
					)}
				</div>
			)}
			{refusal}
		</div>
	);
}
