import { PulseIcon } from "@primer/octicons-react";
import { MessageSquareTextIcon } from "lucide-react";

import type { ReactNode } from "react";
import type { PracticeGroupReviewRun, PracticeStanding } from "@/api/types.gen";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SectionLabel } from "@/components/common/SectionLabel";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import { isSettledStanding } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { formatStandingBasis } from "@/components/practice-vocabulary/practice-trend-presentation";
import {
	type FeedbackRatingProps,
	newestFirst,
	PracticeFeedbackCard,
	type PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { WhereYouStand } from "@/components/practice-vocabulary/WhereYouStand";
import type { ObservationControls, ReviewRunFeedState } from "@/components/profile/review-runs";
import { ReviewRunTimeline } from "@/components/profile/ReviewRunTimeline";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { hasText } from "@/lib/text";

import { DEFAULT_PRACTICE_TAB, PRACTICE_TABS, type PracticeTab } from "./practice-profile-search";

const EMPTY_FEED: ReviewRunFeedState = {
	status: "ready",
	runs: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: () => undefined,
};

const TAB_LABELS: Record<PracticeTab, string> = {
	observations: "Observations",
	feedback: "Feedback",
	about: "About this practice",
};

export interface PracticeDetailLevelProps {
	nested?: boolean;
	/**
	 * Where the level sits, from the drawer: the path is what names the group this practice is in.
	 */
	path?: LevelPath;
	/** Returns to the group's level under this one; a card's group name is a link only with it. */
	onOpenGroup?: () => void;
	/** Opens another practice a card's text names; without it the name is a word. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** The practice, as the standings carry it: the catalog's words and where the reader stands. */
	practice?: PracticeStanding;
	/** The tab shown, from the route's `practiceTab` search param. */
	tab?: PracticeTab;
	onTabChange?: (tab: PracticeTab) => void;
	feed?: ReviewRunFeedState;
	/** The developer's practice feedback; the level shows the cards written about this practice. */
	feedbackCards?: PracticeFeedbackCardEntry[];
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be
	 * rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	skeletonRows?: number;
	/** The reader's response to an observation, from the route. */
	observations?: ObservationControls;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

/**
 * The feedback written about one practice: the one open card it carries (a practice has one live
 * card, so the newest is the one), and the cards the work resolved, newest first.
 */
function feedbackCardsOf(cards: PracticeFeedbackCardEntry[], practiceSlug: string) {
	const own = newestFirst(cards.filter((card) => card.practiceSlug === practiceSlug));
	return {
		open: own.find((card) => isOpenFeedback(card.state)),
		resolved: own.filter((card) => !isOpenFeedback(card.state)),
	};
}

/**
 * One practice as the level over its group, in three tabs: what the reviews of the reader's work
 * found, the feedback written from it, and the catalog's words on why the practice matters. It is
 * the deepest level, so it is the one that carries the observations — the newest open on arrival
 * and every earlier one a press away, each opening and closing on its own.
 */
const NO_CARDS: PracticeFeedbackCardEntry[] = [];

export function PracticeDetailLevel({
	nested,
	path,
	onOpenGroup,
	onOpenPractice,
	practice,
	tab = DEFAULT_PRACTICE_TAB,
	onTabChange,
	feed = EMPTY_FEED,
	feedbackCards = NO_CARDS,
	ratingProps,
	skeletonRows = 3,
	observations,
	isLoading,
	error,
	onRetry,
}: PracticeDetailLevelProps) {
	// The server narrows which runs reached the practice; the level narrows the same way and shows
	// only that practice's observations instead of everything the run found.
	const runs =
		feed.status === "ready" && practice
			? feed.runs
					.map((run) => ({
						...run,
						observations: run.observations.filter(
							(observation) => observation.practiceSlug === practice.slug,
						),
					}))
					.filter((run) => run.observations.length > 0)
			: [];
	const observationCount =
		feed.status === "ready"
			? runs.reduce((count, run) => count + run.observations.length, 0)
			: undefined;
	const feedback = practice
		? feedbackCardsOf(feedbackCards, practice.slug)
		: { open: undefined, resolved: [] };
	const feedbackCount = (feedback.open ? 1 : 0) + feedback.resolved.length;
	const counts: Partial<Record<PracticeTab, number>> = {
		observations: observationCount,
		feedback: feedbackCount,
	};
	// A card on its own practice's level: the words about it are the neighbouring tab.
	const feedbackCard = (card: PracticeFeedbackCardEntry) => (
		<PracticeFeedbackCard
			key={card.feedbackId}
			card={card}
			{...ratingProps?.(card.feedbackId)}
			onLearnMore={onTabChange && (() => onTabChange("about"))}
			onOpenPractice={onOpenPractice}
			onOpenGroup={onOpenGroup && (() => onOpenGroup())}
		/>
	);

	let body: ReactNode;
	if (isLoading) {
		body = (
			// The tabs and the observations they open on, as they will be laid out.
			<>
				<PracticeTabsSkeleton />
				<ReviewRunFeedSkeleton rows={skeletonRows} />
			</>
		);
	} else if (error != null) {
		body = (
			<QueryErrorAlert
				error={error}
				title={
					practice
						? `Could not load your standing for ${practice.name}`
						: "Could not load this practice"
				}
				onRetry={onRetry}
			/>
		);
	} else if (practice) {
		body = (
			<Tabs
				value={tab}
				onValueChange={(next) => {
					const chosen = PRACTICE_TABS.find((candidate) => candidate === next);
					if (chosen) {
						onTabChange?.(chosen);
					}
				}}
				className="gap-4"
			>
				<PracticeTabsRail>
					<PracticeTabsList aria-label="Practice">
						{PRACTICE_TABS.map((candidate) => (
							<PracticeTabsTrigger key={candidate} value={candidate} count={counts[candidate]}>
								{TAB_LABELS[candidate]}
							</PracticeTabsTrigger>
						))}
					</PracticeTabsList>
				</PracticeTabsRail>
				<TabsContent value="observations" className="min-w-0">
					<Section
						size="lg"
						title="Observations"
						description="Reviews of your work that reached this practice, newest first: why each was noted, the evidence, and the next step."
					>
						<ReviewRunFeed
							feed={feed}
							runs={runs}
							skeletonRows={skeletonRows}
							observations={observations}
						/>
					</Section>
				</TabsContent>
				<TabsContent value="feedback" className="min-w-0">
					<Section
						size="lg"
						title="Feedback"
						description="The feedback written about this practice: the open card, then the ones that resolved, newest first."
					>
						{feedbackCount === 0 ? (
							<Empty>
								<EmptyHeader>
									<EmptyMedia variant="icon">
										<MessageSquareTextIcon />
									</EmptyMedia>
									<EmptyTitle>No feedback yet.</EmptyTitle>
									<EmptyDescription>
										Feedback appears once the same shortcoming keeps showing up on your work.
									</EmptyDescription>
								</EmptyHeader>
							</Empty>
						) : (
							<div className="flex flex-col gap-6">
								{feedback.open && (
									<section
										className="flex flex-col gap-2.5"
										aria-labelledby="current-feedback-heading"
									>
										<SectionLabel as="h3" id="current-feedback-heading">
											Current feedback
										</SectionLabel>
										{feedbackCard(feedback.open)}
									</section>
								)}
								{feedback.resolved.length > 0 && (
									<section
										className="flex flex-col gap-2.5"
										aria-labelledby="resolved-feedback-heading"
									>
										<SectionLabel as="h3" id="resolved-feedback-heading">
											Resolved feedback
										</SectionLabel>
										{feedback.resolved.map(feedbackCard)}
									</section>
								)}
							</div>
						)}
					</Section>
				</TabsContent>
				<TabsContent value="about" className="min-w-0">
					<div className="flex flex-col gap-6">
						<WhereYouStand
							standing={practice.standing}
							// A standing no review has settled rests on nothing: no work is named under it.
							basis={
								isSettledStanding(practice.standing) && practice.trendSupport
									? formatStandingBasis(practice.trendSupport)
									: undefined
							}
							direction={practice.direction}
							support={practice.trendSupport}
							scope="practice"
						/>
						{hasText(practice.whyItMatters) && (
							<section className="flex flex-col gap-1.5" aria-labelledby="why-it-matters-heading">
								<SectionLabel as="h2" id="why-it-matters-heading">
									Why it matters
								</SectionLabel>
								<p className="max-w-2xl text-sm">{practice.whyItMatters}</p>
							</section>
						)}
						{hasText(practice.whatGoodLooksLike) && (
							<section className="flex flex-col gap-1.5" aria-labelledby="what-good-heading">
								<SectionLabel as="h2" id="what-good-heading">
									What good looks like
								</SectionLabel>
								<p className="max-w-2xl text-sm">{practice.whatGoodLooksLike}</p>
							</section>
						)}
						{!hasText(practice.whyItMatters) && !hasText(practice.whatGoodLooksLike) && (
							<section className="flex flex-col gap-1.5" aria-labelledby="about-practice-heading">
								<SectionLabel as="h2" id="about-practice-heading">
									About this practice
								</SectionLabel>
								<p className="text-sm text-muted-foreground">No description yet.</p>
							</section>
						)}
					</div>
				</TabsContent>
			</Tabs>
		);
	} else {
		body = (
			<p className="text-sm text-muted-foreground">
				This practice does not exist or is not reviewed in this group.
			</p>
		);
	}

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<DetailPath {...path} current="Practice" />
					<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
						{practice?.name ?? "Practice"}
					</DrawerTitle>
					{practice && (
						<div className="flex flex-wrap items-center gap-3">
							<StandingBadge standing={practice.standing} scope="practice" />
							<TrendNote
								direction={practice.direction}
								support={practice.trendSupport}
								scope="practice"
							/>
						</div>
					)}
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-4 pt-2">{body}</DrawerBody>
		</>
	);
}

interface ReviewRunFeedProps extends Pick<PracticeDetailLevelProps, "observations"> {
	feed: ReviewRunFeedState;
	/** The feed's runs narrowed to this practice's observations. */
	runs: PracticeGroupReviewRun[];
	skeletonRows: number;
}

function ReviewRunFeed({ feed, runs, skeletonRows, observations }: ReviewRunFeedProps) {
	if (feed.status === "error") {
		return (
			<QueryErrorAlert
				error={feed.error}
				title="Could not load review runs"
				onRetry={feed.onRetry}
			/>
		);
	}
	if (feed.status === "loading") {
		return <ReviewRunFeedSkeleton rows={skeletonRows} />;
	}
	if (runs.length === 0) {
		return (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<PulseIcon />
					</EmptyMedia>
					<EmptyTitle>No observations yet.</EmptyTitle>
					<EmptyDescription>No review has reached this practice yet.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<>
			<ReviewRunTimeline
				runs={runs}
				observations={observations}
				// The rows are this practice's own, so none repeats its name under the summary.
				showPracticeName={false}
				// Every row here reviews the same practice, so the newest is the one the reader came
				// for; the rest are its history and wait for a press.
				initiallyOpen="newest"
				continues={feed.hasMore}
			/>
			{feed.hasMore && (
				<Button
					type="button"
					variant="link"
					size="inline"
					className="w-fit text-sm"
					onClick={feed.onLoadMore}
					disabled={feed.isLoadingMore}
				>
					{feed.isLoadingMore ? "Loading…" : "View earlier reviews"}
				</Button>
			)}
		</>
	);
}

/** One block per run card the feed will show, so the level does not jump when they land. */
function ReviewRunFeedSkeleton({ rows }: { rows: number }) {
	return (
		<div className="flex flex-col gap-2.5">
			<span className="sr-only">Loading review runs</span>
			{Array.from({ length: rows }, (_, index) => (
				<Skeleton key={index} className="h-24 w-full" />
			))}
		</div>
	);
}
