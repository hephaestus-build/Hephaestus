// The palette this page and its levels share is `webapp/DESIGN.md` § Practice surfaces palette.
import { useState } from "react";

import type { GroupRef, PracticeStanding, ProfileReviewRef } from "@/api/types.gen";
import { HephMark } from "@/components/brand/HephaestusLogo";
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
import { PRACTICE_PROFILE_TOUR } from "@/components/tour/practice-profile-tour";
import { ProductTour } from "@/components/tour/ProductTour";
import { TourProfilePageContext } from "@/components/tour/tour-profile-page";
import { TOUR_STAGES } from "@/components/tour/tour-scenes";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent } from "@/components/ui/tabs";

import { newestFirst } from "./practice-feedback-cards";
import { FeedbackEmpty, type FeedbackEmptyProps } from "./practice-profile-blocks";
import { FEEDBACK_TABS, type FeedbackTab, type PracticeTab } from "./practice-profile-search";
import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

/** Set once the developer has closed the tour, so it no longer opens by itself. */
const TOUR_SEEN_KEY = "practice-profile:tour-seen";

/** Where the tour can be started again: the "Take the tour" button. */
const restartTarget = () => document.querySelector("[data-tour-start]");

/** The tour opens by itself on the first visit; `?tour` in the address opens it again. */
function tourAsked(): boolean {
	if (new URLSearchParams(window.location.search).has("tour")) {
		return true;
	}
	try {
		return window.localStorage.getItem(TOUR_SEEN_KEY) === null;
	} catch {
		// Without storage the tour cannot remember a visit, so it waits to be asked.
		return false;
	}
}

export interface PracticeProfilePageProps {
	/** Leaves out the tour, its button and "How to read this page", as the tour's own example of this page does. */
	hideTour?: boolean;
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
}: PracticeProfilePageProps) {
	const openGroupBySlug = (groupSlug: string) => {
		const group = groups.find((candidate) => candidate.slug === groupSlug);
		if (group) {
			onOpenGroup?.(group);
		}
	};
	const cardsByTab = feedbackCardsByTab(feedbackCards);
	const [tourOpen, setTourOpen] = useState(() => !hideTour && tourAsked());
	const closeTour = () => {
		setTourOpen(false);
		// `?tour` opened it; once closed, a reload should not open it again.
		const url = new URL(window.location.href);
		if (url.searchParams.has("tour")) {
			url.searchParams.delete("tour");
			window.history.replaceState(window.history.state, "", url);
		}
		try {
			window.localStorage.setItem(TOUR_SEEN_KEY, "1");
		} catch {
			// Without storage the visit is not remembered; the tour still closes.
		}
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
			{/* The guided tour, told by Heph, and the button that starts it again. */}
			{!hideTour && (
				<Button
					variant="outline"
					size="sm"
					data-tour-start=""
					className="absolute top-0 right-0"
					onClick={() => setTourOpen(true)}
				>
					<HephMark className="mr-0.5 size-4" />
					Take the tour
				</Button>
			)}
			{!hideTour && (
				<TourProfilePageContext value={PracticeProfilePage}>
					<ProductTour
						steps={PRACTICE_PROFILE_TOUR}
						open={tourOpen}
						onClose={closeTour}
						restartTarget={restartTarget}
						stages={TOUR_STAGES}
					/>
				</TourProfilePageContext>
			)}
			<PracticeProfilePageHeader
				latestReview={latestReview}
				counts={countPracticeStandings(practices)}
				practiceCount={practices.length}
				groupCount={groups.length}
				isLoading={isLoading}
				hideGuide={hideTour}
			/>
			<Section
				size="lg"
				title="Your feedback"
				description="Each card is one pattern seen more than once across your work, with the one thing to try next."
			>
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
		</div>
	);
}
