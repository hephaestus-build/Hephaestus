// The palette this page and its levels share is `webapp/AGENTS.md` § Practice surfaces palette.
import type { GroupRef, PracticeStanding, ProfileReviewRef } from "@/api/types.gen";
import type { LoadState } from "@/components/common/panel-state";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import {
	type FeedbackRatingProps,
	PracticeFeedbackCard,
	type PracticeFeedbackCardEntry,
	PracticeFeedbackCardSkeleton,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { countPracticeStandings } from "@/components/practice-vocabulary/standing-counts";
import {
	PRACTICE_PROFILE_TOUR,
	practiceProfileTourSections,
} from "@/components/tour/practice-profile-tour";
import { ProductTour } from "@/components/tour/ProductTour";
import {
	clearTourStart,
	markPartDone,
	markTourFinished,
	peekTourStart,
	useTourProgress,
} from "@/components/tour/tour-launcher-store";
import { TOUR_STAGES } from "@/components/tour/tour-scenes";
import { TourLauncher, TourLauncherRoom } from "@/components/tour/TourLauncher";
import { Tabs, TabsContent } from "@/components/ui/tabs";

import { useEffect, useState } from "react";

import { newestFirst } from "./practice-feedback-cards";
import { FeedbackEmpty, type FeedbackEmptyProps } from "./practice-profile-blocks";
import { FEEDBACK_TABS, type FeedbackTab, type PracticeTab } from "./practice-profile-search";
import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

const TOUR_SEEN_KEY = "practice-profile:tour-seen";
const restartTarget = () => document.querySelector("[data-tour-start]");

/** Local demo: the tour was opened once; `?tour` leaves the address so a reload does not reopen it. */
function markTourSeen() {
	const url = new URL(window.location.href);
	if (url.searchParams.has("tour")) {
		url.searchParams.delete("tour");
		window.history.replaceState(window.history.state, "", url);
	}
	try {
		window.localStorage.setItem(TOUR_SEEN_KEY, "1");
	} catch {
		// Blocked storage: the launcher opens again on the next visit.
	}
}

/** Local demo: every part of the tour, in order, across its sections. */
function tourParts() {
	return practiceProfileTourSections().flatMap((section) => section.parts);
}

/**
 * Local demo: how the page opens. A part started from another page opens the tour there at once;
 * else Heph's start card opens on the first visit and for `?tour`.
 */
function initialTour(hideTour: boolean): { start?: number; launcher: boolean; invite: boolean } {
	if (hideTour) {
		return { launcher: false, invite: false };
	}
	const requested = peekTourStart();
	const part = tourParts().find((each) => each.id === requested);
	if (part !== undefined) {
		return { start: part.first, launcher: false, invite: false };
	}
	let seen = true;
	try {
		seen = window.localStorage.getItem(TOUR_SEEN_KEY) !== null;
	} catch {
		// Blocked storage: treat the tour as seen, so it never opens by itself.
	}
	const asked = new URLSearchParams(window.location.search).has("tour");
	// The first visit opens Heph small, as an invitation; `?tour` opens the sections at once.
	return { launcher: !seen || asked, invite: !seen && !asked };
}

export interface PracticeProfilePageProps {
	/** Local demo: leaves out the tour button, as in the tour's own example of this page. */
	hideTour?: boolean;
	/** Local demo: the cards start with their details open, as the tour's example points into them. */
	cardDetailsOpen?: boolean;
	/** The review the header's latest-review chip names; absent before the first review. */
	latestReview?: ProfileReviewRef;
	/** Every practice with a standing, for the header's overview ring. */
	practices: PracticeStanding[];
	/** The groups a card's group name can open; the header counts them. */
	groups: GroupRef[];
	/** The developer's practice feedback, open and resolved alike; the tabs split it by state. */
	feedbackCards: PracticeFeedbackCardEntry[];
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be
	 * rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	onOpenGroup?: (group: GroupRef) => void;
	/**
	 * Opens a practice's detail level from a feedback card. A practice named in the text opens on the
	 * level's default tab, the feedback written about it; the card's "Learn more about this practice"
	 * asks for the Guide tab, where the practice is explained.
	 */
	onOpenPractice?: (practiceSlug: string, tab?: PracticeTab) => void;
	/** The tab over the feedback cards, from the route's `feedback` search param. */
	feedbackTab: FeedbackTab;
	onFeedbackTabChange?: (tab: FeedbackTab) => void;
	state: LoadState;
}

/** Two cards' worth of skeleton while the cards load: what fits above the fold under the header. */
const SKELETON_CARD_COUNT = 2;

const FEEDBACK_TAB_LABELS: Record<FeedbackTab, string> = {
	open: "Open",
	resolved: "Resolved and closed",
	all: "All",
};

const NO_OPEN_FEEDBACK: FeedbackEmptyProps = { title: "No open feedback yet" };

/** What a tab with no cards says where it differs from every other empty list of feedback. */
const EMPTY_TAB: Record<FeedbackTab, FeedbackEmptyProps> = {
	open: NO_OPEN_FEEDBACK,
	resolved: {
		title: "No resolved and closed feedback yet",
		description:
			"A card moves here once your work resolves it, you mark it yourself, or your workspace changes its practice.",
	},
	all: {},
};

/** The cards each tab lists, newest first. */
function feedbackCardsByTab(
	cards: PracticeFeedbackCardEntry[],
): Record<FeedbackTab, PracticeFeedbackCardEntry[]> {
	return {
		open: newestFirst(cards.filter((card) => isOpenFeedback(card.state))),
		// A withdrawn card resolved nothing, so only "All" lists it.
		resolved: newestFirst(
			cards.filter((card) => !isOpenFeedback(card.state) && card.state !== "withdrawn"),
		),
		all: newestFirst(cards),
	};
}

export function PracticeProfilePage({
	latestReview,
	practices,
	groups,
	feedbackCards,
	ratingProps,
	onOpenGroup,
	onOpenPractice,
	feedbackTab,
	onFeedbackTabChange,
	state,
	hideTour = false,
	cardDetailsOpen = false,
}: PracticeProfilePageProps) {
	const openGroupBySlug = (groupSlug: string) => {
		const group = groups.find((candidate) => candidate.slug === groupSlug);
		if (group) {
			onOpenGroup?.(group);
		}
	};
	const cardsByTab = feedbackCardsByTab(feedbackCards);
	const [initial] = useState(() => initialTour(hideTour));
	const [tourOpen, setTourOpen] = useState(initial.start !== undefined);
	const [tourStart, setTourStart] = useState(initial.start ?? 0);
	const [launcherOpen, setLauncherOpen] = useState(false);
	// The reader just finished the whole tour: the start card opens once with a word of it.
	const [tourJustFinished, setTourJustFinished] = useState(false);
	// The part the reader just finished: its tick fills in once as the start card opens.
	const [freshPart, setFreshPart] = useState<string | undefined>(undefined);
	// Heph opens by itself a moment after the page, so the reader sees it come out of its button.
	useEffect(() => {
		if (!initial.launcher) {
			return;
		}
		const timer = window.setTimeout(() => {
			setLauncherOpen(true);
			// Once shown, the invitation counts as seen, so a later visit does not open it again.
			markTourSeen();
		}, 700);
		return () => window.clearTimeout(timer);
	}, [initial.launcher]);
	const progress = useTourProgress();
	// The part another page asked for opens once; a reload shows the page as usual.
	useEffect(() => {
		if (initial.start !== undefined) {
			clearTourStart();
		}
	}, [initial.start]);
	const closeTour = () => {
		setTourOpen(false);
		markTourSeen();
	};
	const startTour = (partId?: string) => {
		setTourStart(tourParts().find((part) => part.id === partId)?.first ?? 0);
		setTourOpen(true);
		markTourSeen();
	};

	// The alert alone, as `profile/ProfilePage` does: the header's "No practices set up yet" and a
	// tab's "No feedback yet" are claims about the workspace, and a failed load has none to make.
	if (state.status === "error") {
		return (
			<div className="mx-auto w-full max-w-xl">
				<QueryErrorAlert
					error={state.error}
					title="We could not load your practices"
					onRetry={state.onRetry}
				/>
			</div>
		);
	}
	const isLoading = state.status === "loading";

	return (
		<div className="relative mx-auto flex w-full max-w-6xl flex-col gap-6">
			{/* Local demo: the guided tour starts only when the reader asks for it, from Heph bottom right. */}
			{!hideTour && (
				<TourLauncher
					sections={practiceProfileTourSections()}
					done={progress.done}
					finished={
						progress.finished || tourParts().every((part) => progress.done.includes(part.id))
					}
					open={launcherOpen}
					invite={initial.invite}
					justFinished={tourJustFinished}
					fresh={freshPart}
					onOpenChange={(next) => {
						setLauncherOpen(next);
						if (!next) {
							setTourJustFinished(false);
							setFreshPart(undefined);
							markTourSeen();
						}
					}}
					onStart={startTour}
				/>
			)}
			{!hideTour && (
				<ProductTour
					steps={PRACTICE_PROFILE_TOUR}
					open={tourOpen}
					startAt={tourStart}
					onClose={closeTour}
					restartTarget={restartTarget}
					stages={TOUR_STAGES}
					parts={tourParts()}
					onPartDone={markPartDone}
					onFinish={markTourFinished}
					onBackToOverview={() => {
						closeTour();
						setLauncherOpen(true);
					}}
					onComplete={(finishedPart) => {
						closeTour();
						setFreshPart(finishedPart);
						// The part's last step showed, so its tick is in; the card opens on it with the next
						// part as its way on. With every part ticked the card says the tour is done.
						if (tourParts().every((part) => progress.done.includes(part.id))) {
							markTourFinished();
							setTourJustFinished(true);
						}
						setLauncherOpen(true);
					}}
				/>
			)}
			<PracticeProfilePageHeader
				latestReview={latestReview}
				counts={countPracticeStandings(practices)}
				practiceCount={practices.length}
				groupCount={groups.length}
				isLoading={isLoading}
			/>
			<Section size="lg" title="Your recurring feedback patterns">
				<Tabs
					value={feedbackTab}
					onValueChange={(next) => {
						const tab = FEEDBACK_TABS.find((candidate) => candidate === next);
						if (tab) {
							onFeedbackTabChange?.(tab);
						}
					}}
					className="gap-4"
				>
					<PracticeTabsRail>
						<PracticeTabsList aria-label="Feedback">
							{FEEDBACK_TABS.map((tab) => (
								<PracticeTabsTrigger
									key={tab}
									value={tab}
									count={isLoading ? undefined : cardsByTab[tab].length}
								>
									{FEEDBACK_TAB_LABELS[tab]}
								</PracticeTabsTrigger>
							))}
						</PracticeTabsList>
						<span className="pb-2 text-sm text-muted-foreground">Newest first</span>
					</PracticeTabsRail>
					<TabsContent value={feedbackTab}>
						{/* Room between the cards, so each card's own edge is what parts them. */}
						<div className="flex flex-col gap-6" aria-busy={isLoading}>
							{isLoading &&
								Array.from({ length: SKELETON_CARD_COUNT }, (_, index) => (
									<PracticeFeedbackCardSkeleton key={index} />
								))}
							{!isLoading && cardsByTab[feedbackTab].length === 0 && (
								<FeedbackEmpty {...EMPTY_TAB[feedbackTab]} />
							)}
							{!isLoading &&
								cardsByTab[feedbackTab].map((card) => (
									<PracticeFeedbackCard
										key={card.feedbackId}
										card={card}
										defaultDetailsOpen={cardDetailsOpen}
										{...ratingProps?.(card.feedbackId)}
										onLearnMore={
											onOpenPractice && (() => onOpenPractice(card.practiceSlug, "guide"))
										}
										onOpenPractice={onOpenPractice}
										onOpenGroup={onOpenGroup && openGroupBySlug}
									/>
								))}
						</div>
					</TabsContent>
				</Tabs>
			</Section>
			{!hideTour && <TourLauncherRoom />}
		</div>
	);
}
