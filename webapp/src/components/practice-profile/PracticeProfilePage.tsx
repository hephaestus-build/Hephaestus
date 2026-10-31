// The palette this page and its levels share is `webapp/DESIGN.md` § Practice surfaces palette.
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
import { Tabs, TabsContent } from "@/components/ui/tabs";

import { newestFirst } from "./practice-feedback-cards";
import { FeedbackEmpty, type FeedbackEmptyProps } from "./practice-profile-blocks";
import { FEEDBACK_TABS, type FeedbackTab, type PracticeTab } from "./practice-profile-search";
import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";
import { firstRunApplies, ProfileFirstRun, type ProfileFirstRunProps } from "./ProfileFirstRun";

export interface PracticeProfilePageProps {
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
	/**
	 * What the page says while the reader has no feedback at all: why no review can run, a first
	 * review under way, or what starts one. Absent where the server's word is not known, such as
	 * before the overview loads; the plain empty tab speaks then.
	 */
	firstRun?: Omit<ProfileFirstRunProps, "reviewed">;
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
	firstRun,
	state,
}: PracticeProfilePageProps) {
	const openGroupBySlug = (groupSlug: string) => {
		const group = groups.find((candidate) => candidate.slug === groupSlug);
		if (group) {
			onOpenGroup?.(group);
		}
	};
	const cardsByTab = feedbackCardsByTab(feedbackCards);
	const reviewed = latestReview !== undefined;
	const showFirstRun =
		firstRun !== undefined &&
		feedbackCards.length === 0 &&
		firstRunApplies(firstRun.readiness, reviewed, firstRun.activeReview);

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
		<div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
			<PracticeProfilePageHeader
				latestReview={latestReview}
				activeReview={firstRun?.activeReview}
				counts={countPracticeStandings(practices)}
				practiceCount={practices.length}
				groupCount={groups.length}
				isLoading={isLoading}
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
							{!isLoading &&
								cardsByTab[feedbackTab].length === 0 &&
								(showFirstRun ? (
									<ProfileFirstRun {...firstRun} reviewed={reviewed} />
								) : (
									<FeedbackEmpty {...EMPTY_TAB[feedbackTab]} />
								))}
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
