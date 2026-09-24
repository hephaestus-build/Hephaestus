// The palette this page and its levels share is `webapp/AGENTS.md` § Practice surfaces palette.
import { MessageSquareTextIcon } from "lucide-react";

import type { PracticeGroup, PracticeStanding } from "@/api/types.gen";
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
	newestFirst,
	PracticeFeedbackCard,
	type PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { countPracticeStandings } from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent } from "@/components/ui/tabs";

import type { ComposedOverview } from "./compose-overview";
import {
	DEFAULT_FEEDBACK_TAB,
	FEEDBACK_TABS,
	type FeedbackTab,
	type PracticeTab,
} from "./practice-profile-search";
import { PracticeFeedbackOverview } from "./PracticeFeedbackOverview";
import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

export interface PracticeProfilePageProps {
	/**
	 * The overview composed into the page's sentences: the latest-run chip, Heph's card and its rest.
	 */
	overview: ComposedOverview;
	/** Every practice with a standing, for the header's overview ring. */
	practices: PracticeStanding[];
	/** The groups a card's group name can open; the header counts them. */
	groups: PracticeGroup[];
	/** The developer's practice feedback, open and resolved alike; the tabs split it by state. */
	feedbackCards: PracticeFeedbackCardEntry[];
	/**
	 * Wires one card's rating to wherever the ratings are kept; without it the cards cannot be
	 * rated.
	 */
	ratingProps?: (feedbackId: string) => FeedbackRatingProps;
	onOpenGroup?: (group: PracticeGroup) => void;
	/**
	 * Opens a practice's detail level from the feedback overview or a feedback card, on the tab
	 * asked for: a card's "Learn more about this practice" opens the catalog's words on it, and a
	 * practice named in the text the observations, the level's default.
	 */
	onOpenPractice?: (practiceSlug: string, tab?: PracticeTab) => void;
	/** The tab over the feedback cards, from the route's `feedback` search param. */
	feedbackTab?: FeedbackTab;
	onFeedbackTabChange?: (tab: FeedbackTab) => void;
	/** The header's "See all practices": opens the level with the table of every practice. */
	onShowAllPractices?: () => void;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

/**
 * "Newest" is the two newest open cards. Two is the maintainer's call after the user test of
 * 2026-09-17: on the wide layout two cards fit above the fold under the header, and a third
 * pushes "All" out of sight. `practice-profile-search` owns the tabs and points here for this
 * rule.
 */
const NEWEST_CARD_COUNT = 2;

const FEEDBACK_TAB_LABELS: Record<FeedbackTab, string> = {
	newest: "Newest",
	open: "Open",
	resolved: "Resolved",
	all: "All",
};

/**
 * What a tab with no cards says, in the shape every practice surface says it in: the title names
 * what is missing and the sentence says when it will show up. A practice's own level uses the
 * same block for the same reason.
 */
const APPEARS_WHEN_SEEN =
	"Feedback appears once the same shortcoming keeps showing up on your work.";
const EMPTY_TAB: Record<FeedbackTab, { title: string; description: string }> = {
	newest: { title: "No feedback yet.", description: APPEARS_WHEN_SEEN },
	open: { title: "No open feedback yet.", description: APPEARS_WHEN_SEEN },
	resolved: {
		title: "No resolved feedback yet.",
		description: "A card moves here once the work resolves it or you mark it as addressed.",
	},
	all: { title: "No feedback yet.", description: APPEARS_WHEN_SEEN },
};

/** The cards each tab lists, newest first. */
function feedbackCardsByTab(
	cards: PracticeFeedbackCardEntry[],
): Record<FeedbackTab, PracticeFeedbackCardEntry[]> {
	const open = newestFirst(cards.filter((card) => isOpenFeedback(card.state)));
	return {
		open,
		resolved: newestFirst(cards.filter((card) => !isOpenFeedback(card.state))),
		all: newestFirst(cards),
		newest: open.slice(0, NEWEST_CARD_COUNT),
	};
}

/**
 * The developer's own practice profile: the overview header, Heph's card on what held and what
 * changed, and the feedback cards under a tab row that filters them in place, with the table of
 * every practice one level over the page.
 */
export function PracticeProfilePage({
	overview,
	practices,
	groups,
	feedbackCards,
	ratingProps,
	onOpenGroup,
	onOpenPractice,
	feedbackTab = DEFAULT_FEEDBACK_TAB,
	onFeedbackTabChange,
	onShowAllPractices,
	isLoading,
	error,
	onRetry,
}: PracticeProfilePageProps) {
	const openGroupBySlug = (groupSlug: string) => {
		const group = groups.find((candidate) => candidate.slug === groupSlug);
		if (group) {
			onOpenGroup?.(group);
		}
	};
	const cardsByTab = feedbackCardsByTab(feedbackCards);

	// The alert alone, as `profile/ProfilePage` does: the header's "No practices set up yet" and a
	// tab's "No feedback yet" are claims about the workspace, and a failed load has none to make.
	if (error != null) {
		return (
			<div className="mx-auto w-full max-w-xl">
				<QueryErrorAlert error={error} title="Could not load your practices" onRetry={onRetry} />
			</div>
		);
	}

	return (
		<div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
			<PracticeProfilePageHeader
				latestRun={overview.latestRun}
				counts={countPracticeStandings(practices)}
				practiceCount={practices.length}
				groupCount={groups.length}
				onSeeAllPractices={onShowAllPractices}
				isLoading={isLoading}
			/>
			<Separator />
			<PracticeFeedbackOverview
				overview={overview}
				onOpenPractice={onOpenPractice}
				groups={groups}
				onOpenGroup={onOpenGroup && openGroupBySlug}
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
						<div className="flex flex-col gap-3">
							{isLoading &&
								// Two cards' worth, the "Newest" tab's count, so the list does not jump.
								Array.from({ length: NEWEST_CARD_COUNT }, (_, index) => (
									<Skeleton key={index} className="h-64 w-full rounded-xl" />
								))}
							{!isLoading && cardsByTab[feedbackTab].length === 0 && (
								<Empty>
									<EmptyHeader>
										<EmptyMedia variant="icon">
											<MessageSquareTextIcon />
										</EmptyMedia>
										<EmptyTitle>{EMPTY_TAB[feedbackTab].title}</EmptyTitle>
										<EmptyDescription>{EMPTY_TAB[feedbackTab].description}</EmptyDescription>
									</EmptyHeader>
								</Empty>
							)}
							{cardsByTab[feedbackTab].map((card) => (
								<PracticeFeedbackCard
									key={card.feedbackId}
									card={card}
									{...ratingProps?.(card.feedbackId)}
									onLearnMore={onOpenPractice && (() => onOpenPractice(card.practiceSlug, "about"))}
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
