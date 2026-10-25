import { XIcon } from "lucide-react";
import { type ReactNode, useEffect, useEffectEvent, useId, useRef, useState } from "react";
import { useMediaQuery } from "usehooks-ts";

import type {
	GroupRef,
	PracticeGuidanceDto,
	PracticeStanding,
	ReviewedPractice,
	ResultList,
	ProfileReview,
} from "@/api/types.gen";
import { ArrivalHighlight } from "@/components/common/ArrivalHighlight";
import { FilterToggle } from "@/components/common/FilterToggle";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SelectFilter } from "@/components/common/SelectFilter";
import { statusValues } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { Section } from "@/components/layout/Section";
import { PracticeGuideMarkdown } from "@/components/practice-guidance/PracticeGuideMarkdown";
import {
	type PracticeGuidanceState,
	PracticeIntro,
} from "@/components/practice-guidance/PracticeIntro";
import { REVIEW_FILTER_MAX_LENGTH } from "@/components/practice-trace/trace-format";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import { getGroupVisual, groupMark } from "@/components/practice-vocabulary/group-visuals";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	type FeedbackRatingProps,
	PracticeFeedbackCard,
	type PracticeFeedbackCardEntry,
	PracticeFeedbackCardSkeleton,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { PracticeTitlePill } from "@/components/practice-vocabulary/PracticePill";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { StandingBasisNote } from "@/components/practice-vocabulary/StandingBasisNote";
import { ReviewFeed, ReviewFeedSkeleton } from "@/components/profile/ReviewFeed";
import {
	EMPTY_REVIEW_FEED,
	type FeedbackResponseControls,
	type ReviewEntry,
	type ReviewFeedState,
} from "@/components/profile/reviews";
import { ReviewTimeline } from "@/components/profile/ReviewTimeline";
import { Button } from "@/components/ui/button";
import { DrawerBody } from "@/components/ui/drawer";
import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupInput,
} from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

import { NeverAppliedBadge } from "./FoldedPractices";
import {
	carriesObservation,
	changedObservationFilter,
	hasObservationFilter,
	hasOwnObservationFilter,
	type ObservationFilter,
	openOnArrival,
	type SelectedObservationState,
	shownObservations,
} from "./observation-filter";
import { newestFirst } from "./practice-feedback-cards";
import { NEVER_APPLIED } from "./practice-folds";
import { FeedbackEmpty, LabelledBlock } from "./practice-profile-blocks";
import {
	ALL_TIME,
	DEFAULT_PRACTICE_TAB,
	PRACTICE_TABS,
	type PracticeTab,
	REVIEW_TIMEFRAME_OPTIONS,
	reviewRangeStart,
} from "./practice-profile-search";
import { requestedReviewNote } from "./requested-review-note";

const TAB_LABELS: Record<PracticeTab, string> = {
	feedback: "Feedback",
	results: "Observations",
	guide: "Guide",
};

export interface PracticeDetailLevelProps {
	nested?: boolean;
	/**
	 * Where the level sits, from the drawer: the path is what names the group this practice is in.
	 */
	path: LevelPath;
	/** Returns to the group's level under this one; a card's group name is a link only with it. */
	onOpenGroup?: () => void;
	/** The feedback card a link asked for: the Feedback tab scrolls to it and washes it once. */
	markedFeedbackId?: string;
	/** Called as soon as that card arrives, so the address lets go of it and nothing replays it. */
	onFeedbackArrivalShown?: () => void;
	/** The practice's group, whose icon leads the heading inside its pill; without it the pill holds the name alone. */
	group?: GroupRef;
	/**
	 * The practice, as the standings carry it: the catalog's words and where the reader stands.
	 * Absent for a practice with no standing, which the level shows from its words and guidance
	 * alone.
	 */
	practice?: PracticeStanding;
	/** The name and words of a practice with no standing, from the workspace's catalog. */
	described?: Pick<ReviewedPractice, "name" | "whyItMatters" | "whatGoodLooksLike">;
	/**
	 * The practice's guidance, which loads on its own after the standing: the picture joins the
	 * introduction in the Guide tab, and a guide follows it there.
	 */
	guidance: PracticeGuidanceState;
	/**
	 * The tab asked for, from the route's `practiceTab` search param. Guide on a practice with
	 * nothing to explain shows the feedback.
	 */
	tab: PracticeTab;
	onTabChange?: (tab: PracticeTab) => void;
	feed?: ReviewFeedState;
	renderObservation?: (result: ResultList) => ReactNode;
	/** The Observations tab's filter, from the route's search; see `observation-filter.ts`. */
	observationFilter?: ObservationFilter;
	/** Writes the filter; without it the tab shows no filter bar. */
	onObservationFilterChange?: (filter: ObservationFilter) => void;
	/** The observation a link asked for, read on its own while its review's page may not be loaded. */
	selectedObservation?: SelectedObservationState;
	/** The developer's practice feedback; the level shows the cards written about this practice. */
	feedbackCards?: PracticeFeedbackCardEntry[];
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be
	 * rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	/** How many rows the feed draws while its first page loads. */
	skeletonRows: number;
	/** The reader's responses to their feedback, from the route; without them none can be given. */
	responses?: FeedbackResponseControls;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	/** Every review so far found that the practice did not apply; the level says so. */
	neverApplied?: boolean;
}

type PracticeWords = Pick<PracticeStanding, "whyItMatters" | "whatGoodLooksLike">;

/**
 * Whether the Guide tab has anything to hold: the introduction's words, a picture or a guide, or
 * the sentence of a practice that never applied. Until the guidance answers, the picture may still
 * come.
 */
function offersGuide(words: PracticeWords, guidance: PracticeGuidanceState, neverApplied: boolean) {
	if (neverApplied || hasText(words.whyItMatters) || hasText(words.whatGoodLooksLike)) {
		return true;
	}
	return guidance.status !== "ready" || guidance.visual != null || guidance.guide != null;
}

/**
 * The feedback written about one practice: the one open card it carries (a practice has one live
 * card, so the newest is the one), and the cards the work resolved, newest first.
 */
function feedbackCardsOf(cards: PracticeFeedbackCardEntry[], practiceSlug: string) {
	const own = newestFirst(cards.filter((card) => card.practiceSlug === practiceSlug));
	return {
		open: own.find((card) => isOpenFeedback(card.state)),
		resolved: own.filter((card) => !isOpenFeedback(card.state) && card.state !== "withdrawn"),
	};
}

const NO_CARDS: PracticeFeedbackCardEntry[] = [];

const NO_WORDS = { whyItMatters: null, whatGoodLooksLike: null };

const GUIDANCE_ONLY = "This practice offers guidance without automated review.";

export function PracticeDetailLevel({
	nested,
	path,
	onOpenGroup,
	markedFeedbackId,
	onFeedbackArrivalShown,
	group,
	practice,
	described,
	guidance,
	tab,
	onTabChange,
	feed = EMPTY_REVIEW_FEED,
	renderObservation,
	observationFilter = NO_FILTER,
	onObservationFilterChange,
	selectedObservation,
	feedbackCards = NO_CARDS,
	ratingProps,
	skeletonRows,
	responses,
	isLoading,
	error,
	onRetry,
	neverApplied = false,
}: PracticeDetailLevelProps) {
	const reviews =
		feed.status === "ready" && practice
			? feed.reviews
					.map((review) => ({
						...review,
						results: review.results.filter((result) => result.practiceSlug === practice.slug),
					}))
					.filter((review) => review.results.length > 0)
			: [];
	const now = useNow();
	const keptCard = useKeptCard(markedFeedbackId);
	const feedback = withinTimeframe(
		practice ? feedbackCardsOf(feedbackCards, practice.slug) : { open: undefined, resolved: [] },
		observationFilter.since === undefined
			? undefined
			: reviewRangeStart(now, observationFilter.since),
		keptCard,
	);
	const feedbackCount = (feedback.open ? 1 : 0) + feedback.resolved.length;
	// Each tab counts what it shows, its filters and the timeframe applied, so the number beside a
	// tab's name is the one count. Feedback arrives whole; paged results cannot supply an exact count.
	const counts: Partial<Record<PracticeTab, number>> = {
		results:
			feed.status === "ready" && !feed.hasMore
				? observationCount(
						shownObservations(
							reviews,
							observationFilter,
							selectedObservation?.status === "ready" ? selectedObservation.result : undefined,
						),
					)
				: undefined,
		feedback: feedbackCount,
	};
	const guide = guidance.status === "ready" ? (guidance.guide ?? undefined) : undefined;
	const words = practice ?? described ?? NO_WORDS;
	// The Guide tab holds the introduction, its picture and any guide, so the feedback and the
	// observations each lead their own tab. A practice with none of them has no Guide tab, and a link
	// to it lands on the feedback.
	const hasGuide = offersGuide(words, guidance, neverApplied);
	const tabs = PRACTICE_TABS.filter((candidate) => candidate !== "guide" || hasGuide);
	const shownTab = tabs.includes(tab) ? tab : DEFAULT_PRACTICE_TAB;

	let body: ReactNode;
	if (isLoading) {
		body = (
			// The tabs and the one they open on, as they will be laid out.
			<div className="flex flex-col gap-4">
				<PracticeTabsSkeleton tabs={tabs.length} />
				{shownTab === "results" ? (
					<ReviewFeedSkeleton rows={skeletonRows} />
				) : (
					<div className="flex flex-col gap-2.5" aria-busy="true">
						<span className="sr-only">Loading feedback…</span>
						<PracticeFeedbackCardSkeleton />
					</div>
				)}
			</div>
		);
	} else if (error != null) {
		body = (
			<QueryErrorAlert
				error={error}
				title={
					practice
						? `We could not load your standing for ${practice.name}`
						: "We could not load this practice"
				}
				onRetry={onRetry}
			/>
		);
	} else if (practice || observationFilter.observationId !== undefined) {
		const requestedNote = practice ? requestedReviewNote(practice, reviews) : undefined;
		body = (
			<Tabs
				value={shownTab}
				onValueChange={(next) => {
					const chosen = tabs.find((candidate) => candidate === next);
					if (chosen) {
						onTabChange?.(chosen);
					}
				}}
				className="gap-4"
			>
				<PracticeTabsRail>
					<PracticeTabsList aria-label="Practice">
						{tabs.map((candidate) => (
							<PracticeTabsTrigger key={candidate} value={candidate} count={counts[candidate]}>
								{TAB_LABELS[candidate]}
							</PracticeTabsTrigger>
						))}
					</PracticeTabsList>
					{/* Right of the tabs, the span the level shows: it narrows the Feedback tab's cards and the
					    Observations tab alike, as the reviews list's timeframe narrows its list. */}
					{onObservationFilterChange !== undefined && (
						<div className="pb-1.5">
							<FilterToggle
								label="Timeframe"
								options={REVIEW_TIMEFRAME_OPTIONS}
								value={observationFilter.since ?? ALL_TIME}
								onChange={(next) =>
									onObservationFilterChange(
										changedObservationFilter(observationFilter, {
											since: next === ALL_TIME ? undefined : next,
										}),
									)
								}
							/>
						</div>
					)}
				</PracticeTabsRail>
				<TabsContent value="feedback" className="min-w-0">
					<PracticeFeedbackSection
						feedback={feedback}
						ratingProps={ratingProps}
						onOpenGroup={onOpenGroup}
						markedFeedbackId={markedFeedbackId}
						onFeedbackArrivalShown={onFeedbackArrivalShown}
					/>
				</TabsContent>
				<TabsContent value="results" className="min-w-0">
					<Section
						size="lg"
						title="Observations"
						description="Reviews of your work that reached this practice, newest first. Open one to see its problems, each with its next step."
					>
						{hasText(requestedNote) && <p className="max-w-2xl text-sm">{requestedNote}</p>}
						<ObservationHistory
							feed={feed}
							reviews={reviews}
							filter={observationFilter}
							onFilterChange={onObservationFilterChange}
							selected={selectedObservation}
							renderObservation={renderObservation}
							skeletonRows={skeletonRows}
							responses={responses}
						/>
					</Section>
				</TabsContent>
				{hasGuide && (
					<TabsContent value="guide" className="min-w-0">
						<PracticeGuidance
							words={words}
							guidance={guidance}
							guide={guide}
							neverApplied={neverApplied}
						/>
					</TabsContent>
				)}
			</Tabs>
		);
	} else if (guidance.status === "ready") {
		body = (
			<>
				<p className="max-w-2xl text-sm">{GUIDANCE_ONLY}</p>
				<PracticeGuidance words={words} guidance={guidance} guide={guide} neverApplied={false} />
			</>
		);
	} else if (guidance.status === "loading") {
		body = (
			<div className="flex max-w-2xl flex-col gap-2" aria-busy="true">
				<span className="sr-only">Loading this practice…</span>
				<Skeleton className="h-5 w-full" />
				<Skeleton className="h-5 w-3/4" />
			</div>
		);
	} else if (problemStatusOf(guidance.error) === 404) {
		body = (
			<p className="text-sm text-muted-foreground">
				We could not find this practice. Check the link, or ask a workspace admin whether this
				workspace reviews it.
			</p>
		);
	} else {
		// Any other failure says nothing about whether the practice exists, so it offers the retry.
		body = (
			<QueryErrorAlert
				error={guidance.error}
				title="We could not load this practice"
				onRetry={guidance.onRetry}
			/>
		);
	}

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current="Practice"
				// As the practice is named in its group's table: the gray pill with the group's icon
				// inside, at the heading's size.
				title={
					<PracticeTitlePill
						name={practice?.name ?? described?.name ?? "Practice"}
						group={
							group === undefined ? undefined : groupMark(getGroupVisual(group.icon, group.color))
						}
					/>
				}
				chips={
					practice && <PracticeStandingChips practice={practice} neverApplied={neverApplied} />
				}
			/>
			<DrawerBody className="flex flex-col gap-6 pt-2">{body}</DrawerBody>
		</>
	);
}

/** Where the reader stands on the practice, under the level's title. */
function PracticeStandingChips({
	practice,
	neverApplied,
}: {
	practice: PracticeStanding;
	neverApplied: boolean;
}) {
	// A practice that never applied has no trend, as its folded row shows.
	if (neverApplied) {
		return <NeverAppliedBadge scope="practice" />;
	}
	return (
		<>
			<StandingBadge
				standing={practice.standing}
				scope="practice"
				support={practice.trendSupport ?? undefined}
			/>
			<TrendNote
				direction={practice.direction ?? undefined}
				support={practice.trendSupport ?? undefined}
				scope="practice"
			/>
			<StandingBasisNote basis={practice.basis} />
		</>
	);
}

/**
 * What explains the practice: why it matters with its picture, then its guide. The Guide tab holds
 * it, so it never stands between the tab rail and the feedback or an observation a link opened.
 */
function PracticeGuidance({
	words,
	guidance,
	guide,
	neverApplied,
}: {
	words: PracticeWords;
	guidance: PracticeGuidanceState;
	guide?: PracticeGuidanceDto["guide"];
	neverApplied: boolean;
}) {
	return (
		<div className="flex min-w-0 flex-col gap-6">
			{neverApplied && <p className="max-w-2xl text-sm">{NEVER_APPLIED.description}</p>}
			<PracticeIntro practice={words} guidance={guidance} />
			{guide && (
				// `h3`: the guide's own headings are `h4`, and the outline may not skip a level.
				<Section size="lg" level={3} title="Practice guide">
					<PracticeGuideMarkdown guide={guide} />
				</Section>
			)}
		</div>
	);
}

/** The Feedback tab: the practice's one open card, then the cards the work resolved. */
function PracticeFeedbackSection({
	feedback,
	ratingProps,
	onOpenGroup,
	markedFeedbackId,
	onFeedbackArrivalShown,
}: {
	feedback: ReturnType<typeof feedbackCardsOf>;
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	onOpenGroup?: () => void;
	markedFeedbackId?: string;
	onFeedbackArrivalShown?: () => void;
}) {
	// The arrival is this mount's: kept here, while the address lets go of the id at once, so a
	// later visit to the tab (Observations and back) does not play it again. A new link arrives with
	// the id again and plays it again. The card it marked keeps its wrapper after the sheen, so the
	// card is not remounted and keeps any focus inside it.
	const [seen, setSeen] = useState(markedFeedbackId);
	const [marked, setMarked] = useState(markedFeedbackId);
	const [arriving, setArriving] = useState(markedFeedbackId);
	if (markedFeedbackId !== seen) {
		setSeen(markedFeedbackId);
		if (markedFeedbackId !== undefined) {
			setMarked(markedFeedbackId);
			setArriving(markedFeedbackId);
		}
	}
	const arrived = useEffectEvent(() => onFeedbackArrivalShown?.());
	useEffect(() => {
		if (markedFeedbackId !== undefined) {
			arrived();
		}
	}, [markedFeedbackId]);
	// No "Learn more" on a card here: this level is the practice, and its Guide tab explains it.
	const feedbackCard = (card: PracticeFeedbackCardEntry) => {
		const element = (
			<PracticeFeedbackCard
				key={card.feedbackId}
				card={card}
				{...ratingProps?.(card.feedbackId)}
				onOpenGroup={onOpenGroup && (() => onOpenGroup())}
			/>
		);
		return card.feedbackId === marked ? (
			<MarkedFeedbackCard
				key={card.feedbackId}
				arriving={card.feedbackId === arriving}
				onArrivalShown={() => setArriving(undefined)}
			>
				{element}
			</MarkedFeedbackCard>
		) : (
			element
		);
	};
	return (
		<Section
			size="lg"
			title="Feedback"
			description="Feedback about patterns in your work: the open card, then the ones that resolved, newest first."
		>
			{arriving !== undefined &&
				feedback.open?.feedbackId !== arriving &&
				!feedback.resolved.some((card) => card.feedbackId === arriving) && (
					// The page lists the newest cards and drops a closed one a month after it closed: a link
					// may ask for one it no longer holds, and says so rather than mark nothing.
					<p data-marked-feedback-missing="" className="text-sm text-muted-foreground">
						The feedback you came from is no longer listed here: this tab keeps your newest feedback
						and drops closed feedback a month after it closed.
					</p>
				)}
			{feedback.open === undefined && feedback.resolved.length === 0 ? (
				<FeedbackEmpty description="Feedback appears here once a review sees the same pattern in your work more than once. Feedback posted on a single piece of work stays there. Open its observation to find recorded comment links." />
			) : (
				<div className="flex flex-col gap-6">
					{feedback.open && (
						<LabelledBlock label="Current feedback" as="h3" className="flex flex-col gap-2.5">
							{feedbackCard(feedback.open)}
						</LabelledBlock>
					)}
					{feedback.resolved.length > 0 && (
						<LabelledBlock label="Resolved feedback" as="h3" className="flex flex-col gap-2.5">
							<div className="flex flex-col gap-6">{feedback.resolved.map(feedbackCard)}</div>
						</LabelledBlock>
					)}
				</div>
			)}
		</Section>
	);
}

function observationCount(reviews: readonly ReviewEntry[]): number {
	return reviews.reduce((count, review) => count + review.results.length, 0);
}

const NO_FILTER: ObservationFilter = {};

function observationsShownText(count: number): string {
	return `${count.toLocaleString()} ${count === 1 ? "observation" : "observations"} shown.`;
}

function emptyDescriptionOf(filtered: boolean, ownFiltered: boolean): string {
	if (ownFiltered) {
		return "Clear the filters to see every observation of this practice in this timeframe.";
	}
	if (filtered) {
		return "None falls in this timeframe. Choose a longer one to see earlier observations.";
	}
	return "They appear after Hephaestus reviews your work.";
}

const OUTCOME_OPTIONS = statusValues(OUTCOME_DEFS).map((value) => ({
	value,
	label: OUTCOME_DEFS[value].label,
}));

/**
 * The practice's observations in one history, narrowed by the filter bar over it. A link to one
 * observation sets the filter to its work and opens that observation; when its review's page is not
 * loaded yet, the observation joins the history in its place by time, read on its own. Which row
 * opens on arrival is `openOnArrival`'s rule, and only its.
 */
function ObservationHistory({
	feed,
	reviews,
	filter,
	onFilterChange,
	selected,
	renderObservation,
	skeletonRows,
	responses,
}: {
	feed: ReviewFeedState;
	reviews: ProfileReview[];
	filter: ObservationFilter;
	onFilterChange?: (filter: ObservationFilter) => void;
	selected?: SelectedObservationState;
	renderObservation?: (result: ResultList) => ReactNode;
	skeletonRows: number;
	responses?: FeedbackResponseControls;
}) {
	const queryId = useId();
	const asked = filter.observationId;
	const askedLoaded = asked !== undefined && carriesObservation(reviews, asked);
	// The asked observation is read on its own only while no loaded page carries it.
	const pending = asked !== undefined && !askedLoaded ? selected : undefined;
	const shown = shownObservations(
		reviews,
		filter,
		pending?.status === "ready" ? pending.result : undefined,
	);
	const openId = openOnArrival(shown, asked);
	const filtered = hasObservationFilter(filter) || asked !== undefined;
	// Reset and its clear-the-filters twin let go of the tab's own filters only; the timeframe has
	// its own control in the tab row.
	const ownFiltered = hasOwnObservationFilter(filter) || asked !== undefined;
	const clearOwn = () => onFilterChange?.({ since: filter.since });
	const askedAlone = pending?.status === "ready" && feed.status !== "ready" && shown.length > 0;
	const undecided =
		pending !== undefined &&
		pending.status !== "ready" &&
		feed.status === "ready" &&
		!feed.hasMore &&
		shown.length === 0;
	const showBar =
		onFilterChange !== undefined && (filtered || (feed.status === "ready" && reviews.length > 0));
	return (
		<>
			{showBar && (
				<FilterToolbar hasFilter={ownFiltered} onReset={clearOwn}>
					<div className="flex min-w-0 items-center gap-2">
						<Label htmlFor={queryId} className="shrink-0 text-muted-foreground">
							Search
						</Label>
						{/* The browser's own clear button is tinted by the platform; this one is the
						    foreground, as every other control's icon is. */}
						<InputGroup className="w-64 max-w-full">
							<InputGroupInput
								id={queryId}
								type="search"
								value={filter.query ?? ""}
								maxLength={REVIEW_FILTER_MAX_LENGTH}
								placeholder="Words or work, such as #15"
								className="[&::-webkit-search-cancel-button]:appearance-none"
								onChange={(event) =>
									onFilterChange(
										changedObservationFilter(filter, {
											query: event.target.value === "" ? undefined : event.target.value,
										}),
									)
								}
							/>
							{hasText(filter.query) && (
								<InputGroupAddon align="inline-end">
									<InputGroupButton
										size="icon-xs"
										aria-label="Clear search"
										onClick={() =>
											onFilterChange(changedObservationFilter(filter, { query: undefined }))
										}
									>
										<XIcon aria-hidden />
									</InputGroupButton>
								</InputGroupAddon>
							)}
						</InputGroup>
					</div>
					<SelectFilter
						label="Outcome"
						allLabel="All outcomes"
						options={OUTCOME_OPTIONS}
						value={filter.outcome}
						onChange={(outcome) => onFilterChange(changedObservationFilter(filter, { outcome }))}
					/>
				</FilterToolbar>
			)}
			{/* The tab's own number shows the count; a screen reader still hears it change. */}
			<p role="status" aria-live="polite" className="sr-only">
				{filtered && feed.status === "ready" && !feed.hasMore
					? observationsShownText(observationCount(shown))
					: ""}
			</p>
			{pending?.status === "loading" && (
				<div aria-busy="true" className="flex flex-col gap-2">
					<span className="sr-only">Loading the observation you opened…</span>
					<Skeleton className="h-16 w-full" />
				</div>
			)}
			{pending?.status === "error" && (
				<QueryErrorAlert
					error={pending.error}
					title="We could not load this observation"
					onRetry={pending.onRetry}
				/>
			)}
			{pending?.status === "elsewhere" && (
				<p className="text-sm">
					This observation does not belong to this practice. Open it from its review.
				</p>
			)}
			{/* The observation a link asked for, read on its own, stands while the rest of the history
			    still loads or failed: the feed's own placeholder or error follows it. */}
			{askedAlone && (
				<ReviewTimeline
					reviews={shown}
					responses={responses}
					renderObservation={renderObservation}
					showPracticeName={false}
					openOnArrival={(result) => result.id === openId}
					selectedResultId={asked}
					marksSearchMatches={hasText(filter.query)}
				/>
			)}
			{/* While the asked observation is still on its way, or could not be read, an empty history
			    proves nothing about the filter: no "No observations match" beside it. */}
			{!undecided && (
				<ReviewFeed
					feed={feed}
					reviews={shown}
					skeletonRows={skeletonRows}
					responses={responses}
					renderObservation={renderObservation}
					showPracticeName={false}
					openOnArrival={(result) => result.id === openId}
					// The one a link asked for wears the accent's bar: the current selection. So does every
					// observation the search found.
					selectedResultId={asked}
					marksSearchMatches={hasText(filter.query)}
					emptyTitle={filtered ? "No observations match your filters" : "No observations yet"}
					emptyDescription={emptyDescriptionOf(filtered, ownFiltered)}
					emptyAction={
						filtered && onFilterChange !== undefined ? (
							<Button
								variant="outline"
								size="sm"
								onClick={
									ownFiltered
										? clearOwn
										: () => onFilterChange(changedObservationFilter(filter, { since: undefined }))
								}
							>
								{ownFiltered ? "Clear filters" : "Show all time"}
							</Button>
						) : undefined
					}
				/>
			)}
		</>
	);
}

/**
 * The card a link from an observation asked for: scrolled into view on each arrival, and crossed
 * once by the new card's mentor sheen (`ArrivalHighlight`), then it reads as a card like the others.
 */
function MarkedFeedbackCard({
	children,
	arriving,
	onArrivalShown,
}: {
	children: ReactNode;
	/** True while the arrival plays; afterwards only the wrapper stays. */
	arriving: boolean;
	onArrivalShown?: () => void;
}) {
	const ref = useRef<HTMLDivElement>(null);
	const reduceMotion = useMediaQuery("(prefers-reduced-motion: reduce)", {
		initializeWithValue: true,
	});
	const scroll = useEffectEvent(() =>
		ref.current?.scrollIntoView({ block: "center", behavior: reduceMotion ? "auto" : "smooth" }),
	);
	useEffect(() => {
		if (arriving) {
			scroll();
		}
	}, [arriving]);
	return (
		<div
			ref={ref}
			data-marked-feedback=""
			aria-current={arriving || undefined}
			className="relative rounded-xl"
		>
			{arriving && <ArrivalHighlight onDone={onArrivalShown} />}
			{children}
		</div>
	);
}

/**
 * The card a link arrived at stays listed for the rest of this visit, whatever the timeframe: the
 * address lets go of its id at once (so the arrival sheen never replays), and the level keeps it
 * here. A new link replaces it.
 */
function useKeptCard(markedFeedbackId: string | undefined): string | undefined {
	const [keptCard, setKeptCard] = useState(markedFeedbackId);
	if (markedFeedbackId !== undefined && markedFeedbackId !== keptCard) {
		setKeptCard(markedFeedbackId);
	}
	return keptCard;
}

/**
 * The cards the level's timeframe keeps: a card by the time it shows (when it was created or last
 * moved), and the card a link arrived at whatever its time, so the arrival always lands.
 */
function withinTimeframe(
	feedback: ReturnType<typeof feedbackCardsOf>,
	since: Date | undefined,
	arrivedAt: string | undefined,
): ReturnType<typeof feedbackCardsOf> {
	if (since === undefined) {
		return feedback;
	}
	const keeps = (card: PracticeFeedbackCardEntry) =>
		card.feedbackId === arrivedAt || card.timestamp.getTime() >= since.getTime();
	return {
		...feedback,
		open: feedback.open !== undefined && keeps(feedback.open) ? feedback.open : undefined,
		resolved: feedback.resolved.filter(keeps),
	};
}
