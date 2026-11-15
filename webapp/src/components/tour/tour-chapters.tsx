import { Link } from "@tanstack/react-router";
import { ArrowRightIcon, RepeatIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import type { GroupRef, PracticeStanding } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { newestFirst } from "@/components/practice-profile/practice-feedback-cards";
import {
	ALL_PRACTICE_GROUPS_LEVEL,
	type PracticeTab,
	REVIEWS_LEVEL,
} from "@/components/practice-profile/practice-profile-search";
import type { PracticeProfilePageProps } from "@/components/practice-profile/PracticeProfilePage";
import { PROFILE_AUDIENCE } from "@/components/practice-profile/PracticeProfilePageHeader";
import {
	firstRunApplies,
	firstRunWords,
	ProfileFirstRun,
} from "@/components/practice-profile/ProfileFirstRun";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import { TREND_EXPLAINED } from "@/components/practice-vocabulary/practice-trend-presentation";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { reviewEventPhrases } from "@/components/practice-vocabulary/review-event-defs";
import {
	countPracticeStandings,
	summarizeStandingCounts,
} from "@/components/practice-vocabulary/standing-counts";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { ITEM_KIND, itemKindNoun, type ItemProvider } from "@/lib/item-kinds";

import type { ExampleProfile } from "./example-profile";
import { EXAMPLE_GROUPS, EXAMPLE_PRACTICES } from "./tour-example";
import {
	DestinationsScene,
	FlowOverviewScene,
	GroupStandingScene,
	GroupsScene,
	HandOverScene,
	LevelsScene,
	ObservationsScene,
	PatternScene,
	PracticesScene,
	practiceNames,
	PROFILE_PARTS,
	ProfileScene,
	STANDING_DEFS,
	StandingsScene,
	TREND_DEFS,
	WorkScene,
} from "./tour-scenes";
import { EXAMPLE_CAPTION, type ExampleView } from "./TourExampleLayer";

/** A part of a real component that a step lights. The component names it with `data-tour-slot`. */
export type TourSlot =
	| "chip"
	| "ring"
	| "see-all-groups"
	| "feedback"
	| "pattern"
	| "why"
	| "next-step"
	| "resolves"
	| "mark"
	| "rate"
	| "row"
	| "standing"
	| "trend"
	| "changes"
	| "row-link"
	| "practice-tabs"
	| "tab-results"
	| "tab-guide"
	| "focus-switch"
	| "figures"
	| "splits";

/**
 * Where a step points: a part of the reader's own page, of the example page, or of the topmost level
 * open over the example; or the strip of example page beside the open levels. `card` narrows the part
 * to one feedback card.
 */
export type TourSpot =
	| { on: "page" | "example"; slot: TourSlot; card?: string }
	| { on: "level"; slot: TourSlot }
	| { on: "beside-levels" };

export interface TourStep {
	id: string;
	title: string;
	/** Everything the step teaches, in words: a picture is out of reach of a screen reader. */
	body: ReactNode;
	/** A scene the guide shows in its own window, in the middle of the screen. */
	scene?: ReactElement;
	/** Steps with one key share one mounted scene. */
	sceneKey?: string;
	/** Says what a scene shows: the reader's own data, an example, or a schematic. */
	caption?: string;
	/** Whether the scene plays something that the reader may want to see again. */
	plays?: boolean;
	/** The scene holds real links, so the reader can reach it. Every other scene is inert. */
	live?: boolean;
	/** The part the step lights, with the guide beside it. */
	spot?: TourSpot;
	/** Room around the lit part, in pixels. */
	padding?: number;
	/** A pointer presses the lit part, for the press that Next makes here on the reader's behalf. */
	clickHint?: boolean;
	/** The example the step stands on, in place of the reader's page, under its caption. */
	example?: ExampleView;
	/** Where the step stands in the chain from work to profile. */
	station?: number;
}

export interface TourChapter {
	id: TourChapterId;
	name: string;
	question: string;
	steps: TourStep[];
}

export type TourChapterId =
	| "how-it-works"
	| "your-profile"
	| "going-deeper"
	| "across-the-workspace";

/** What the chapters read about the reader, all of it from the route that shows the page. */
export interface TourFacts {
	workspaceSlug: string;
	workspaceName: string;
	provider?: ItemProvider;
	page: PracticeProfilePageProps;
	practicesByGroup: Record<string, PracticeStanding[] | undefined>;
}

const YOUR_PAGE = "Your Practice profile, with your real data";
const SCHEMATIC = "Schematic, not the real panels";

/**
 * The card that the reader's own Your profile steps can stand on: open, with work to point at, and
 * not one whose history cannot say whether today's standard is met, since the steps speak of a found
 * pattern. Without one, the steps show the example.
 */
export function ownCardForTour(
	cards: PracticeFeedbackCardEntry[],
): PracticeFeedbackCardEntry | undefined {
	return newestFirst(cards).find(
		(card) =>
			isOpenFeedback(card.state) && card.unverifiedHistory !== true && card.reviewedWork.length > 0,
	);
}

/** The chapter that answers the reader's next question: their own card once there is one. */
export function suggestedChapter(cards: PracticeFeedbackCardEntry[]): TourChapterId {
	return cards.some((card) => isOpenFeedback(card.state)) ? "your-profile" : "how-it-works";
}

/** The ring's own legend as words, so the two say the same: "3 Needs attention, 8 Going well". */
function standingWords(practices: PracticeStanding[]): string {
	return summarizeStandingCounts(countPracticeStandings(practices))
		.map(({ label, count }) => `${count} ${label}`)
		.join(", ");
}

function plural(n: number, one: string, many: string): string {
	return `${n} ${n === 1 ? one : many}`;
}

/**
 * What "Where you are now" stands on. A card proves that a review recorded something, so a reader
 * with cards never reads "No review of your work yet" or "Your first review" beside them.
 */
function currentState({ page }: TourFacts) {
	const reviewed = page.latestReview !== undefined || page.feedbackCards.length > 0;
	return {
		reviewed,
		activeReview: reviewed ? undefined : page.firstRun?.activeReview,
		open: page.feedbackCards.filter((card) => isOpenFeedback(card.state)).length,
	};
}

/** How the chain's first link reads for this reader: what starts a review, or that none will. */
function reviewStartWords(facts: TourFacts): ReactNode {
	const readiness = facts.page.firstRun?.readiness;
	if (readiness === undefined) {
		return null;
	}
	if (readiness.blocker !== null && readiness.blocker !== "PERSON_ERASED") {
		// A review in flight speaks first on the last step, and then the reason does not appear there.
		const reasonFollows = currentState(facts).activeReview === undefined;
		return (
			<p>
				Right now, no review starts by itself on your work in {facts.workspaceName}.
				{reasonFollows && " The last step of this chapter says why."}
			</p>
		);
	}
	const phrases = reviewEventPhrases(readiness.events, facts.provider);
	if (phrases.length === 0) {
		return null;
	}
	return (
		<>
			<p>In {facts.workspaceName}, a review of your work can start when:</p>
			<ul className="list-disc pl-5">
				{phrases.map((phrase) => (
					<li key={phrase}>{phrase}</li>
				))}
			</ul>
		</>
	);
}

/** What a picture shows, for a screen reader. Sighted readers have it in the picture already. */
function MeaningsForScreenReaders({ items }: { items: readonly (readonly [string, string])[] }) {
	return (
		<ul className="sr-only">
			{items.map(([term, meaning]) => (
				<li key={`${term}:${meaning}`}>
					{term}: {meaning}
				</li>
			))}
		</ul>
	);
}

/** The chain's last word: where this reader stands now, or nothing where the server has not said. */
function whereYouAreNow(facts: TourFacts): TourStep | undefined {
	const { firstRun } = facts.page;
	if (firstRun === undefined) {
		return undefined;
	}
	const { reviewed, activeReview, open } = currentState(facts);
	const applies = firstRunApplies(firstRun.readiness, reviewed, activeReview);
	const approval = firstRun.readiness.approvalBeforePosting
		? ` In ${facts.workspaceName}, a workspace admin approves feedback before it is posted on your work.`
		: "";
	const cards = `You have ${plural(open, "open feedback card", "open feedback cards")}.`;
	const noneOpen = `A card appears only when reviews find the same problem in at least two pieces of your work.${approval}`;
	const state = applies
		? firstRunWords({ ...firstRun, activeReview, reviewed })
		: open === 0 && `No open feedback right now. ${noneOpen}`;
	return {
		id: "now",
		title: "Where you are now",
		body: (
			<>
				{open > 0 && <p>{cards}</p>}
				{typeof state === "string" && <p>{state}</p>}
				<p>
					This is your real state in {facts.workspaceName} today. It changes by itself as your work
					is reviewed.
				</p>
			</>
		),
		caption: "Your real state",
		sceneKey: "now",
		station: 4,
		live: true,
		scene: (
			<div className="flex flex-col gap-3">
				{open > 0 && <p className="text-sm font-medium">{cards}</p>}
				{applies ? (
					<ProfileFirstRun
						{...firstRun}
						activeReview={activeReview}
						reviewed={reviewed}
						tourOffer={undefined}
					/>
				) : (
					open === 0 && (
						// Never "nothing was found": feedback can wait for an admin, and a card needs a repeat.
						<Empty>
							<EmptyHeader>
								<EmptyTitle>No open feedback right now</EmptyTitle>
								<EmptyDescription>{noneOpen}</EmptyDescription>
							</EmptyHeader>
						</Empty>
					)
				)}
			</div>
		),
	};
}

function howItWorks(facts: TourFacts): TourChapter {
	const { practices, groups, firstRun } = facts.page;
	const noun = itemKindNoun(ITEM_KIND.pullRequest, 1, facts.provider);
	const real = practiceNames(practices);
	const picked = practices.find((each) => each.whyItMatters != null) ?? practices.at(0);
	const realGroups = groups
		.map((group: GroupRef) => ({
			name: group.name,
			practices: (facts.practicesByGroup[group.slug] ?? []).map((practice) => practice.name),
		}))
		.filter((group) => group.practices.length > 0);
	const approval = firstRun?.readiness.approvalBeforePosting === true;
	const now = whereYouAreNow(facts);
	return {
		id: "how-it-works",
		name: "How it works",
		question: "How does my work become feedback?",
		steps: [
			{
				id: "overview",
				title: "From your work to your profile",
				body: (
					<p>
						Hephaestus reviews your work against the practices of {facts.workspaceName}. The way has
						five stations: your work, the practices, the observations, the feedback, and your
						Practice profile. The next steps go through them one at a time.
					</p>
				),
				caption: "The five stations",
				sceneKey: "overview",
				scene: <FlowOverviewScene noun={noun} />,
				plays: true,
			},
			{
				id: "work",
				title: "It starts with your work",
				body: (
					<>
						<p>You work as usual. Hephaestus reads the work you already do, such as a {noun}.</p>
						{reviewStartWords(facts)}
					</>
				),
				caption: EXAMPLE_CAPTION,
				sceneKey: "work",
				scene: <WorkScene noun={noun} provider={facts.provider} />,
				station: 0,
			},
			{
				id: "practices",
				title: "A review looks at one practice at a time",
				body: (
					<p>
						A practice is one way of working that your workspace cares about. It says why it matters
						and what good looks like.{" "}
						{real.length > 0
							? `${facts.workspaceName} has ${plural(real.length, "practice", "practices")}.`
							: `${facts.workspaceName} has not chosen any practices yet.`}
					</p>
				),
				caption: real.length > 0 ? `The practices of ${facts.workspaceName}` : EXAMPLE_CAPTION,
				sceneKey: "practices",
				scene: (
					<PracticesScene
						names={real.length > 0 ? real : EXAMPLE_PRACTICES}
						picked={real.length > 0 ? picked : undefined}
					/>
				),
				station: 1,
				plays: true,
			},
			{
				id: "groups",
				title: "Related practices form a practice group",
				body: (
					<p>
						Groups show the bigger topics, so you can see where you stand without reading every
						practice.
					</p>
				),
				caption:
					realGroups.length > 0 ? `The practice groups of ${facts.workspaceName}` : EXAMPLE_CAPTION,
				sceneKey: "groups",
				scene: <GroupsScene groups={realGroups.length > 0 ? realGroups : EXAMPLE_GROUPS} />,
				station: 1,
				plays: true,
			},
			{
				id: "observations",
				title: "A review records observations",
				body: (
					<p>
						An observation is what the review saw for one practice, with the evidence from your
						work, so you can check it yourself. Its outcome is Met, Not met, Does not apply, or
						Could not determine.
					</p>
				),
				caption: EXAMPLE_CAPTION,
				sceneKey: "observations",
				scene: <ObservationsScene provider={facts.provider} />,
				station: 2,
				plays: true,
			},
			{
				id: "destinations",
				title: "Feedback reaches you in three places",
				body: (
					<>
						<p>
							On the work, as a comment about that one piece of work. On your Practice profile, as a
							feedback card about a repeated problem. In a conversation, Hephaestus can show
							feedback when it fits what you ask. If something is unclear, ask Hephaestus.
						</p>
						{approval && (
							<p>
								In {facts.workspaceName}, a workspace admin approves feedback before it is posted on
								your work.
							</p>
						)}
					</>
				),
				caption: "The three places, as a schematic",
				sceneKey: "destinations",
				scene: <DestinationsScene noun={noun} />,
				station: 3,
				plays: true,
			},
			{
				id: "pattern",
				title: "A repeated problem becomes a feedback card",
				body: (
					<p>
						When reviews find the same problem in at least two pieces of your work, one card
						describes the pattern. It gives one thing to try next. The card resolves when several
						pieces of your work in a row, reviewed after it, come back clean. It counts how many
						more it needs.
					</p>
				),
				caption: EXAMPLE_CAPTION,
				sceneKey: "pattern",
				scene: <PatternScene provider={facts.provider} />,
				station: 3,
				plays: true,
			},
			{
				id: "profile",
				title: "Your Practice profile brings it together",
				body: (
					<>
						<p>It is feedback for learning. Your standing is not a grade.</p>
						<p>{PROFILE_AUDIENCE}</p>
						<MeaningsForScreenReaders items={PROFILE_PARTS} />
					</>
				),
				caption: "What your Practice profile holds",
				sceneKey: "profile",
				scene: <ProfileScene />,
				station: 4,
				plays: true,
			},
			...(now === undefined ? [] : [now]),
		],
	};
}

function yourProfile(facts: TourFacts, example: ExampleProfile): TourChapter {
	const { practices, feedbackCards, latestReview, feedbackTab } = facts.page;
	const own = ownCardForTour(feedbackCards);
	// The reader's card is on their page while the tab lists open cards; on another tab, the example
	// stands in, as it does without a card.
	const ownOnPage = own !== undefined && feedbackTab !== "resolved";
	const open = feedbackCards.filter((each) => isOpenFeedback(each.state)).length;
	const words = standingWords(practices);
	const reviewInFlight =
		latestReview === undefined && facts.page.firstRun?.activeReview !== undefined;
	const noReviewWords = reviewInFlight
		? "The chip beside the title says that your first review is waiting or running. After it records something, the chip names your newest review and opens every review of your work."
		: "The chip beside the title says that no review has recorded anything about your work yet. After the first one, it names your newest review and opens every review of your work.";
	const chipWords =
		latestReview === undefined
			? noReviewWords
			: "The chip beside the title names your newest review. Select it to open every review of your work.";
	const onCard = (slot: TourSlot): Pick<TourStep, "spot" | "example" | "padding"> =>
		ownOnPage
			? { spot: { on: "page", slot, card: own.feedbackId }, padding: 6 }
			: {
					spot: { on: "example", slot, card: example.feedbackCards[0]?.feedbackId },
					example: { page: "profile" },
					padding: 6,
				};
	const nouns = itemKindNoun(ITEM_KIND.pullRequest, 2, facts.provider);
	return {
		id: "your-profile",
		name: "Your profile",
		question: "What am I looking at, and what do I do with it?",
		steps: [
			{
				id: "chip",
				title: "Your newest review",
				body: <p>{chipWords}</p>,
				spot: { on: "page", slot: "chip" },
				padding: 6,
			},
			{
				id: "ring",
				title: "Where you stand",
				body: (
					<>
						<p>
							The ring counts your practices by standing. Your standing comes from your recent
							reviewed work, and it is not a grade. Gray means there is nothing to judge yet. The
							(i) beside the count says the same, whenever you need it.
						</p>
						{words !== "" && <p>Your practices now: {words}.</p>}
					</>
				),
				spot: { on: "page", slot: "ring" },
				padding: 6,
			},
			{
				id: "feedback",
				title: "Your feedback",
				body: (
					<p>
						{open > 0
							? `You have ${plural(open, "open card", "open cards")} here, newest first. Each card is one pattern seen across your work.`
							: "Cards appear here when reviews find the same problem in at least two pieces of your work. You have no open card now."}{" "}
						The (i) beside the heading says when a card appears and goes.
					</p>
				),
				spot: { on: "page", slot: "feedback" },
				padding: 6,
			},
			{
				...onCard("pattern"),
				id: "pattern",
				title: "One card is one pattern",
				body: (
					<p>
						{ownOnPage
							? `This is one of your open cards: “${own.headline}”. `
							: `This card is an example, not about your work. Its practice and ${nouns} are made up. `}
						The headline says in one line what keeps coming up. Above it are the practice and its
						group.
					</p>
				),
			},
			{
				...onCard("why"),
				id: "why",
				title: "Why it was raised",
				body: <p>What the reviews saw, with links to the work, so you can check it yourself.</p>,
			},
			{
				...onCard("next-step"),
				id: "next-step",
				title: "The one thing to try next",
				body: <p>One concrete thing to try in your next piece of work.</p>,
			},
			{
				...onCard("resolves"),
				id: "resolves",
				title: "How a card goes away",
				body: (
					<p>
						Your reviewed work appears along a line, and the card counts the clean pieces it still
						needs. It resolves by itself when enough pieces in a row come back clean.
					</p>
				),
			},
			{
				...onCard("mark"),
				id: "mark",
				title: "You can mark it yourself",
				body: (
					<p>
						If you fixed the problem, or it does not fit your work, say so on the card. The card
						moves to Marked by you, and your next work confirms it.
					</p>
				),
			},
			{
				...onCard("rate"),
				id: "rate",
				title: "Tell Hephaestus what you think",
				body: (
					<p>
						Helpful and Not helpful say whether the feedback helped you. If you think it is wrong,
						select Disagree. Workspace admins read your sentence.
					</p>
				),
			},
		],
	};
}

/** The example with a practice open over its group, on one of the practice's tabs. */
function inPractice(practiceTab: PracticeTab): ExampleView {
	return { page: "profile", levels: ["groups", "group", "practice"], practiceTab };
}

function goingDeeper(example: ExampleProfile): TourChapter {
	const groupPractices = example.practicesByGroup[example.group.slug] ?? [];
	const standing = example.groupStandings[example.group.slug]?.standing ?? "NOT_OBSERVED";
	const levels = [
		"Practice profile",
		"All practice groups",
		example.group.name,
		example.practice.name,
	];
	const groups = { page: "profile", levels: ["groups"] } as const;
	const inGroup = { page: "profile", levels: ["groups", "group"] } as const;
	const trendLabels = TREND_DEFS.map((def) => def.label).join(", ");
	const standingLabels = STANDING_DEFS.filter((def) => def.label !== "No standing yet")
		.map((def) => def.label)
		.join(", ");
	return {
		id: "going-deeper",
		name: "Going deeper",
		question: "Where is the detail behind this?",
		steps: [
			{
				id: "levels",
				title: "Each press opens one more panel",
				body: (
					<p>
						A panel slides in over the page. To go back, select a position on the path above its
						title, select the page beside the panels, or press Escape.
					</p>
				),
				caption: SCHEMATIC,
				sceneKey: "levels",
				scene: <LevelsScene levels={levels} />,
				plays: true,
			},
			{
				id: "see-all",
				title: "See all practice groups",
				body: (
					<p>
						From here on, the tour opens the real panels over an example profile, not over yours.
						See all practice groups opens the first panel: every group with your standing in it.
						Next opens it here.
					</p>
				),
				example: { page: "profile" },
				spot: { on: "example", slot: "see-all-groups" },
				padding: 8,
				clickHint: true,
			},
			{
				id: "groups-row",
				title: "All practice groups",
				body: <p>One row for each group. The next steps read the first row.</p>,
				example: groups,
				spot: { on: "level", slot: "row" },
				padding: 4,
			},
			{
				id: "standing",
				title: "Standing in a group",
				body: (
					<p>
						Where you stand in this group: {standingLabels}. It comes from your recent reviewed work
						in its practices. Select a label on your page to read what it means.
					</p>
				),
				example: groups,
				spot: { on: "level", slot: "standing" },
				padding: 6,
			},
			{
				id: "trend",
				title: "Trend in a group",
				body: (
					<p>
						{TREND_EXPLAINED} It reads {trendLabels}.
					</p>
				),
				example: groups,
				spot: { on: "level", slot: "trend" },
				padding: 6,
			},
			{
				id: "labels",
				title: "What each label means",
				body: (
					<>
						<p>
							Every standing and every trend, with the words its label shows when you select it.
						</p>
						<MeaningsForScreenReaders
							items={[...STANDING_DEFS, ...TREND_DEFS].map(
								(def) => [def.label, def.description] as const,
							)}
						/>
					</>
				),
				caption: "The labels of the page, with their meanings",
				sceneKey: "labels",
				scene: <StandingsScene />,
				example: groups,
			},
			{
				id: "changes",
				title: "What changed since your last review",
				body: <p>Each row says which practices moved, and where the group stands now.</p>,
				example: groups,
				spot: { on: "level", slot: "changes" },
				padding: 6,
			},
			{
				id: "adds-up",
				title: "A group adds up its practices",
				body: (
					<p>
						Each practice of a group has its own standing. The group’s ring shows them together, one
						part for each practice, and the group’s standing follows from them.
					</p>
				),
				caption: EXAMPLE_CAPTION,
				sceneKey: "adds-up",
				scene: (
					<GroupStandingScene
						groupName={example.group.name}
						practices={groupPractices}
						standing={standing}
					/>
				),
				example: groups,
			},
			{
				id: "open-group",
				title: "Open group",
				body: (
					<p>
						Open group opens the next panel: the group with all its practices. Next opens it here.
					</p>
				),
				example: groups,
				spot: { on: "level", slot: "row-link" },
				padding: 6,
				clickHint: true,
			},
			{
				id: "group",
				title: "One group and its practices",
				body: (
					<p>
						Each practice of the group, with your standing and trend in it. Open practice opens the
						detail.
					</p>
				),
				example: inGroup,
				spot: { on: "level", slot: "row" },
				padding: 4,
			},
			{
				id: "practice",
				title: "One practice has its own tabs",
				body: (
					<p>
						Feedback holds the card for this practice, if there is one. Observations and Guide
						follow, where the practice has something to explain.
					</p>
				),
				example: inPractice("feedback"),
				spot: { on: "level", slot: "practice-tabs" },
				padding: 6,
			},
			{
				id: "observations",
				title: "Observations",
				body: (
					<p>
						Every observation of this practice on your work, newest first, with its outcome. Open
						one for its evidence.
					</p>
				),
				example: inPractice("results"),
				spot: { on: "level", slot: "tab-results" },
				padding: 6,
			},
			{
				id: "guide",
				title: "Guide",
				body: (
					<p>
						Why the practice matters and what good looks like. A good place to start when a card is
						unclear.
					</p>
				),
				example: inPractice("guide"),
				spot: { on: "level", slot: "tab-guide" },
				padding: 6,
			},
			{
				id: "close",
				title: "Back to the page",
				body: (
					<p>
						A press on the page beside the panels closes them all. Escape closes one at a time. Next
						does it here.
					</p>
				),
				example: inPractice("guide"),
				spot: { on: "beside-levels" },
				padding: 0,
				clickHint: true,
			},
			{
				id: "reviews-chip",
				title: "Every review",
				body: (
					<p>
						Back on the page, the chip beside the title opens one more panel: every review of your
						work. Next opens it here.
					</p>
				),
				example: { page: "profile" },
				spot: { on: "example", slot: "chip" },
				padding: 6,
				clickHint: true,
			},
			{
				id: "reviews",
				title: "Reviews",
				body: (
					<p>
						Each reviewed piece of work and what the review recorded. Open one to see every practice
						it checked. After you change your work, you can request another review here.
					</p>
				),
				example: { page: "profile", levels: ["reviews"] },
				spot: { on: "level", slot: "row" },
				padding: 4,
			},
			{
				id: "try",
				title: "Try it on your own data",
				body: (
					<p>
						These links close the tour and open the real panel. You can continue the tour later.
					</p>
				),
				caption: "Real links to your own data",
				sceneKey: "try",
				live: true,
				scene: (
					<HandOverScene>
						<InlineLink
							render={<DetailStackLink entry={ALL_PRACTICE_GROUPS_LEVEL} />}
							className="inline-flex items-center gap-1.5 text-sm font-medium"
						>
							See all practice groups
							<ArrowRightIcon className="size-3.5" aria-hidden />
						</InlineLink>
						<InlineLink
							render={<DetailStackLink entry={REVIEWS_LEVEL} />}
							className="inline-flex items-center gap-1.5 text-sm font-medium"
						>
							See every review of your work
							<ArrowRightIcon className="size-3.5" aria-hidden />
						</InlineLink>
					</HandOverScene>
				),
			},
		],
	};
}

function acrossTheWorkspace(facts: TourFacts): TourChapter {
	const across = { page: "across" } as const;
	return {
		id: "across-the-workspace",
		name: "Across the workspace",
		question: "How does the workspace look as a whole, and who can see me?",
		steps: [
			{
				id: "figures",
				title: "Figures beside the workspace",
				body: (
					<p>
						Practices across the workspace is a page of its own, in the sidebar. On it, your own
						figures stand beside the range where most developers of the workspace are. This example
						is made up.
					</p>
				),
				example: across,
				spot: { on: "example", slot: "figures" },
				padding: 6,
			},
			{
				id: "split",
				title: "How the workspace splits on each group",
				body: (
					<p>
						The page shows how many developers have each standing, for each practice group. Your
						Practice profile never compares you with other developers. This page is where you can
						look, when you want to.
					</p>
				),
				example: across,
				spot: { on: "example", slot: "splits" },
				padding: 6,
			},
			{
				id: "counts",
				title: "Other developers appear only as counts",
				body: (
					<p>
						The page never names a developer, and everyone sees the same counts. A small count can
						still tell members where someone stands. A workspace admin can hide you from the
						workspace, and then the page does not count you.
					</p>
				),
				example: across,
				spot: { on: "example", slot: "splits" },
				padding: 6,
			},
			{
				id: "group",
				title: "One group across the workspace",
				body: <p>The split for each practice of the group. Nobody is named.</p>,
				example: { page: "across", level: true },
				spot: { on: "level", slot: "row" },
				padding: 4,
			},
			{
				id: "switch",
				title: "From the workspace to your own standing",
				body: (
					<p>
						At the foot of each panel, one press takes you to the same group on your own profile.
						From there, one press takes you back.
					</p>
				),
				example: { page: "across", level: true },
				spot: { on: "level", slot: "focus-switch" },
				padding: 6,
			},
			{
				id: "try",
				title: "See it for your workspace",
				body: <p>This link closes the tour and opens the page.</p>,
				caption: "A real link",
				sceneKey: "try-across",
				live: true,
				scene: (
					<HandOverScene>
						<InlineLink
							render={
								<Link
									to="/w/$workspaceSlug/practices-across-the-workspace"
									params={{ workspaceSlug: facts.workspaceSlug }}
								/>
							}
							className="inline-flex items-center gap-1.5 text-sm font-medium"
						>
							<RepeatIcon className="size-3.5" aria-hidden />
							Practices across the workspace
						</InlineLink>
					</HandOverScene>
				),
			},
		],
	};
}

export function tourChapters(facts: TourFacts, example: ExampleProfile): TourChapter[] {
	return [
		howItWorks(facts),
		yourProfile(facts, example),
		goingDeeper(example),
		acrossTheWorkspace(facts),
	];
}

/** The caption of a step that points at a part rather than showing a scene. */
export function spotCaption(step: TourStep): string | undefined {
	if (step.spot === undefined) {
		return step.caption;
	}
	return step.spot.on === "page" ? YOUR_PAGE : EXAMPLE_CAPTION;
}
