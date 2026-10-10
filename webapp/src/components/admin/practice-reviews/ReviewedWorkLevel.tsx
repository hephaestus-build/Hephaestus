import { FileQuestionIcon, PlayIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type {
	ArtifactTrace,
	Practice,
	PracticeGroup,
	ReviewedWorkRef,
	ReviewFeedback,
	ReviewObservation,
} from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import {
	type ReviewRunPracticeFilters,
	ReviewRunPracticeTable,
	ReviewRunPracticeTableSkeleton,
} from "@/components/practice-trace/ReviewRunPracticeTable";
import { TraceSignalTimeline } from "@/components/practice-trace/TraceSignalTimeline";
import { useOccurrenceJump } from "@/components/practice-trace/use-occurrence-jump";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import {
	ARTIFACT_KIND,
	artifactKindLabel,
	artifactKindNoun,
	type KnownArtifactKind,
	reviewedWorkName,
} from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { reviewLevel } from "./review-levels";
import type { ReviewSectionState } from "./review-states";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewOutputSections } from "./ReviewOutputSections";

/**
 * The kinds the request endpoint accepts. A conversation and a document are reviewed on the occasion
 * their source produces, and asking for one by hand is refused. Nothing on the wire says which kinds
 * have a front door; being wrong in this direction costs a missing button rather than a broken one.
 */
const REVIEWABLE_ON_DEMAND: ReadonlySet<string> = new Set([
	ARTIFACT_KIND.pullRequest,
	ARTIFACT_KIND.issue,
]);

const WORK_TABS = ["output", "practices", "noticed"] as const;

type WorkTab = (typeof WORK_TABS)[number];

const TAB_LABELS: Record<WorkTab, string> = {
	output: "Observations and feedback",
	practices: "Every practice",
	noticed: "What we noticed",
};

/**
 * Every practice's latest answer on the work, and everything recorded about it — or `none`, when
 * nothing about the work was ever recorded, as for work from before Hephaestus kept that record.
 */
export type ReviewedWorkTraceState = PanelState<{ trace: ArtifactTrace }> | { status: "none" };

export interface ReviewedWorkLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	artifactKind: KnownArtifactKind;
	artifactId: number;
	feedback: ReviewSectionState<ReviewFeedback>;
	observations: ReviewSectionState<ReviewObservation>;
	/** The workspace's practices, for the hover card on each observation's practice. */
	practices: Practice[] | undefined;
	trace: ReviewedWorkTraceState;
	/** For the icon and colour a group's name is drawn in, in the practice table. */
	groups: PracticeGroup[];
	/** Opens a practice's own level from its pill in the practice table. */
	onOpenPractice: (practiceSlug: string) => void;
	/** Offered only for the kinds a person can ask to have reviewed. */
	onReviewNow?: () => void;
	/** An ask about this work is in flight. */
	requesting?: boolean;
	/** Why the last ask from this level started nothing, drawn by the route. */
	refusal?: ReactNode;
}

const itemsOf = <T,>(state: ReviewSectionState<T>): T[] =>
	state.status === "ready" ? state.items : [];

const SKELETON_ROWS = 4;

/**
 * Everything recorded about one piece of work, across every review of it: what the reviews said,
 * every practice's answer — the quiet ones included — and each occurrence with what it led to. The
 * level knows the work only by kind and id until a read answers, so the title is read off whichever
 * answered first, and is the kind of work until one does.
 */
export function ReviewedWorkLevel({
	workspaceSlug,
	nested,
	path,
	artifactKind,
	artifactId,
	feedback,
	observations,
	practices,
	trace,
	groups,
	onOpenPractice,
	onReviewNow,
	requesting = false,
	refusal,
}: ReviewedWorkLevelProps) {
	const [chosenTab, setTab] = useState<WorkTab>();
	const [filters, setFilters] = useState<ReviewRunPracticeFilters>({});

	const feedbackItems = itemsOf(feedback);
	const observationItems = itemsOf(observations);
	const reviewedWork = feedbackItems[0]?.reviewedWork ?? observationItems[0]?.reviewedWork;
	const recorded = trace.status === "ready" ? trace.trace : undefined;
	const stillLoading =
		feedback.status === "loading" ||
		observations.status === "loading" ||
		trace.status === "loading";
	// Both sections have to have answered before "nothing here" is an honest thing to say: one of them
	// failing is not evidence that the other found nothing.
	const noOutput =
		feedback.status === "ready" &&
		observations.status === "ready" &&
		feedbackItems.length === 0 &&
		observationItems.length === 0;
	// Until the reader picks a tab, work no review said anything about opens on what was noticed,
	// which is where the reason is.
	const tab = chosenTab ?? (noOutput && recorded ? "noticed" : "output");
	const showOccurrence = useOccurrenceJump(tab === "noticed", () => setTab("noticed"));

	const work = reviewedWork ?? recorded?.reviewedWork;
	let description: ReactNode = null;
	if (work) {
		description = <WorkLink reviewedWork={work} />;
	} else if (stillLoading) {
		description = <Skeleton aria-hidden className="h-5 w-72 max-w-full" />;
	}

	const counts: Record<WorkTab, number | undefined> = {
		output: undefined,
		practices: recorded?.practices.length,
		noticed: recorded?.signals.length,
	};

	return (
		<>
			<ReviewLevelHeader
				nested={nested}
				path={path}
				kind="work"
				title={work ? (work.title ?? work.label) : artifactKindLabel(artifactKind)}
				description={description}
			/>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{refusal}
				<Tabs
					value={tab}
					onValueChange={(next) => {
						const chosen = WORK_TABS.find((candidate) => candidate === next);
						if (chosen) {
							setTab(chosen);
						}
					}}
					className="gap-4"
				>
					<PracticeTabsRail>
						<PracticeTabsList aria-label="This work">
							{WORK_TABS.map((candidate) => (
								<PracticeTabsTrigger key={candidate} value={candidate} count={counts[candidate]}>
									{TAB_LABELS[candidate]}
								</PracticeTabsTrigger>
							))}
						</PracticeTabsList>
					</PracticeTabsRail>
					<TabsContent value="output" className="min-w-0">
						<div className="flex min-w-0 flex-col gap-8">
							{noOutput ? (
								<Empty variant="outlined">
									<EmptyHeader>
										<EmptyMedia variant="icon">
											<FileQuestionIcon />
										</EmptyMedia>
										<EmptyTitle>Nothing has been reviewed on this work</EmptyTitle>
										<EmptyDescription>
											{trace.status === "none"
												? "No observations or feedback are recorded against it, and nothing else was recorded about it either."
												: "No observations or feedback are recorded against it. Open the “Every practice” or “What we noticed” tab to see why."}
										</EmptyDescription>
									</EmptyHeader>
								</Empty>
							) : (
								<ReviewOutputSections
									workspaceSlug={workspaceSlug}
									scope={{ artifactKind, artifactId }}
									feedback={feedback}
									observations={observations}
									practices={practices}
								/>
							)}
						</div>
					</TabsContent>
					<TabsContent value="practices" className="min-w-0">
						{trace.status === "ready" && (
							<ReviewRunPracticeTable
								workspaceSlug={workspaceSlug}
								entries={trace.trace.practices}
								signals={trace.trace.signals}
								groups={groups}
								filters={filters}
								onFiltersChange={setFilters}
								onOpenPractice={onOpenPractice}
								onShowOccurrence={showOccurrence}
								canAdminister
								emptyMessage={`No practice in this workspace reviews ${artifactKindNoun(artifactKind, 2, work?.provider)}.`}
								reviewLink={stackedReviewLink}
							/>
						)}
						{trace.status === "none" && (
							<Empty variant="outlined">
								<EmptyHeader>
									<EmptyTitle>No practice was asked about this work</EmptyTitle>
									<EmptyDescription>
										Nothing was recorded about it, so no practice has an answer to show.
									</EmptyDescription>
								</EmptyHeader>
							</Empty>
						)}
						{trace.status === "loading" && <ReviewRunPracticeTableSkeleton rows={SKELETON_ROWS} />}
						{trace.status === "error" && <TraceError state={trace} />}
					</TabsContent>
					<TabsContent value="noticed" className="min-w-0">
						{trace.status === "ready" && (
							<TraceSignalTimeline
								signals={trace.trace.signals}
								workspaceSlug={workspaceSlug}
								canAdminister
							/>
						)}
						{trace.status === "none" && (
							<TraceSignalTimeline signals={[]} workspaceSlug={workspaceSlug} canAdminister />
						)}
						{trace.status === "loading" && <TimelineSkeleton />}
						{trace.status === "error" && <TraceError state={trace} />}
					</TabsContent>
				</Tabs>
			</DrawerBody>
			{onReviewNow && REVIEWABLE_ON_DEMAND.has(artifactKind) && (
				<DrawerFooter>
					<Button type="button" disabled={requesting} onClick={onReviewNow}>
						<PlayIcon aria-hidden data-icon="inline-start" />
						{requesting ? "Requesting review…" : "Request review"}
					</Button>
				</DrawerFooter>
			)}
		</>
	);
}

function TraceError({ state }: { state: { error: unknown; onRetry: () => void } }) {
	return (
		<QueryErrorAlert
			error={state.error}
			title="We could not load what was recorded about this work"
			onRetry={state.onRetry}
		/>
	);
}

/** The timeline's rail and a few occurrences on it, while the trace is on its way. */
function TimelineSkeleton() {
	return (
		<div aria-hidden className="space-y-4 border-l pl-4">
			{Array.from({ length: SKELETON_ROWS }, (_, index) => (
				<div key={index} className="space-y-1.5 py-1">
					<Skeleton className="h-4 w-48" />
					<Skeleton className="h-3 w-64 max-w-full" />
				</div>
			))}
		</div>
	);
}

/** A review opens over this work's level, so closing it comes back here. */
function stackedReviewLink(reviewId: string, label: ReactNode) {
	return (
		<InlineLink
			className="inline-flex items-center gap-1 font-medium"
			render={<DetailStackLink entry={reviewLevel(reviewId)} />}
		>
			{label}
		</InlineLink>
	);
}

/**
 * The work named the way its provider writes it, "Pull request #1423 · acme/api", opening
 * it at the provider where there is a page to open.
 */
function WorkLink({ reviewedWork }: { reviewedWork: ReviewedWorkRef }) {
	const Icon = reviewedWorkIcon(reviewedWork.kind, reviewedWork.provider);
	const name = (
		<>
			<Icon className="size-3.5 shrink-0" />
			<span className="min-w-0 break-words">
				{[reviewedWorkName(reviewedWork), reviewedWork.container].filter(hasText).join(" · ")}
			</span>
		</>
	);
	if (!hasText(reviewedWork.url)) {
		return <span className="inline-flex max-w-full min-w-0 items-center gap-1.5">{name}</span>;
	}
	return (
		<InlineLink
			href={reviewedWork.url}
			external
			className="relative inline-flex max-w-full min-w-0 items-center gap-1.5"
		>
			{name}
		</InlineLink>
	);
}
