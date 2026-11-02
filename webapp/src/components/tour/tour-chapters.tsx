import { Link } from "@tanstack/react-router";
import { ArrowRightIcon, RepeatIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import type { GroupRef, MemberReviewReadiness, PracticeStanding } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { newestFirst } from "@/components/practice-profile/practice-feedback-cards";
import {
	ALL_PRACTICE_GROUPS_LEVEL,
	REVIEWS_LEVEL,
} from "@/components/practice-profile/practice-profile-search";
import { PROFILE_AUDIENCE } from "@/components/practice-profile/PracticeProfileGuide";
import {
	PracticeProfilePage,
	type PracticeProfilePageProps,
} from "@/components/practice-profile/PracticeProfilePage";
import { firstRunApplies, ProfileFirstRun } from "@/components/practice-profile/ProfileFirstRun";
import { isOpenFeedback } from "@/components/practice-vocabulary/feedback-state-defs";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { reviewEventPhrases } from "@/components/practice-vocabulary/review-event-defs";
import {
	countPracticeStandings,
	summarizeStandingCounts,
} from "@/components/practice-vocabulary/standing-counts";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { ITEM_KIND, itemKindNoun, type ItemProvider } from "@/lib/item-kinds";

import { EXAMPLE_GROUPS, EXAMPLE_PRACTICES, exampleCard } from "./tour-example";
import {
	CardScene,
	DestinationsScene,
	GroupsScene,
	HandOverScene,
	LevelsScene,
	ObservationsScene,
	PatternScene,
	PracticesScene,
	PracticeTabsScene,
	practiceNames,
	ProfileScene,
	SplitScene,
	StandingsScene,
	WorkScene,
} from "./tour-scenes";

/** A part of a real component that a step highlights. The component names it with `data-tour-slot`. */
export type TourSlot =
	| "chip"
	| "ring"
	| "feedback"
	| "pattern"
	| "why"
	| "next-step"
	| "resolves"
	| "mark"
	| "rate";

export interface TourStep {
	id: string;
	title: string;
	/** Everything the step teaches, in words: the stage is a picture, out of reach of a screen reader. */
	body: ReactNode;
	stage: ReactElement;
	/** Steps with one key share one mounted stage, so the highlight moves instead of the page. */
	stageKey: string;
	/** Says what the stage shows: the reader's own data, an example, or a schematic. */
	caption: string;
	slot?: TourSlot;
	/** Where the step stands in the chain from work to profile. */
	station?: number;
	/** Whether the stage plays something that the reader may want to see again. */
	plays?: boolean;
	/** The stage holds real links, so the reader can reach it. Every other stage is inert. */
	live?: boolean;
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
	/** When this tour opened, so the example's dates are recent. */
	openedAt: Date;
}

const YOUR_PAGE = "Your Practice profile, with your real data";
const EXAMPLE = "Example, not your work";
const SCHEMATIC = "Schematic of the panels, not the real ones";

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

/** How the chain's first link reads for this reader: what starts a review, or that none will. */
function reviewStartWords(
	readiness: MemberReviewReadiness | undefined,
	workspaceName: string,
	provider?: ItemProvider,
): ReactNode {
	if (readiness === undefined) {
		return null;
	}
	if (readiness.blocker !== null && readiness.blocker !== "PERSON_ERASED") {
		return (
			<p>
				Right now, no review starts by itself on your work in {workspaceName}. The last step of this
				chapter says why.
			</p>
		);
	}
	const phrases = reviewEventPhrases(readiness.events, provider);
	if (phrases.length === 0) {
		return null;
	}
	return (
		<>
			<p>In {workspaceName}, a review of your work can start when:</p>
			<ul className="list-disc pl-5">
				{phrases.map((phrase) => (
					<li key={phrase}>{phrase}</li>
				))}
			</ul>
		</>
	);
}

/** The chain's last word: where this reader stands now, or nothing where the server has not said. */
function whereYouAreNow(facts: TourFacts): TourStep | undefined {
	const { firstRun, latestReview, feedbackCards } = facts.page;
	if (firstRun === undefined) {
		return undefined;
	}
	const reviewed = latestReview !== undefined;
	const open = feedbackCards.filter((card) => isOpenFeedback(card.state)).length;
	const applies = firstRunApplies(firstRun.readiness, reviewed, firstRun.activeReview);
	const approval = firstRun.readiness.approvalBeforePosting
		? `In ${facts.workspaceName}, a workspace admin approves feedback before it is posted on your work.`
		: undefined;
	return {
		id: "now",
		title: "Where you are now",
		body: (
			<p>
				This is your real state in {facts.workspaceName} today. It changes by itself as your work is
				reviewed.
			</p>
		),
		caption: "Your real state",
		stageKey: "now",
		station: 4,
		live: true,
		stage: (
			<div className="flex flex-col gap-3">
				{open > 0 && (
					<p className="text-sm font-medium">
						You have {plural(open, "open feedback card", "open feedback cards")}.
					</p>
				)}
				{applies ? (
					<ProfileFirstRun {...firstRun} reviewed={reviewed} tourOffer={undefined} />
				) : (
					open === 0 && (
						// Never "nothing was found": feedback can wait for an admin, and a card needs a repeat.
						<Empty>
							<EmptyHeader>
								<EmptyTitle>No open feedback right now</EmptyTitle>
								<EmptyDescription>
									A card appears only when reviews find the same problem in at least two pieces of
									your work.
									{approval !== undefined && ` ${approval}`}
								</EmptyDescription>
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
				id: "work",
				title: "It starts with your work",
				body: (
					<>
						<p>You work as usual. Hephaestus reads the work you already do, such as a {noun}.</p>
						{reviewStartWords(firstRun?.readiness, facts.workspaceName, facts.provider)}
					</>
				),
				caption: EXAMPLE,
				stageKey: "work",
				stage: <WorkScene noun={noun} />,
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
				caption: real.length > 0 ? `The practices of ${facts.workspaceName}` : EXAMPLE,
				stageKey: "practices",
				stage: <PracticesScene names={real.length > 0 ? real : EXAMPLE_PRACTICES} />,
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
				caption: realGroups.length > 0 ? `The practice groups of ${facts.workspaceName}` : EXAMPLE,
				stageKey: "groups",
				stage: <GroupsScene groups={realGroups.length > 0 ? realGroups : EXAMPLE_GROUPS} />,
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
				caption: EXAMPLE,
				stageKey: "observations",
				stage: <ObservationsScene />,
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
							feedback when it fits what you ask.
						</p>
						{approval && (
							<p>
								In {facts.workspaceName}, a workspace admin approves feedback before it is posted on
								your work.
							</p>
						)}
					</>
				),
				caption: "The three places",
				stageKey: "destinations",
				stage: <DestinationsScene />,
				station: 3,
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
				caption: EXAMPLE,
				stageKey: "pattern",
				stage: <PatternScene />,
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
					</>
				),
				caption: "What your Practice profile holds",
				stageKey: "profile",
				stage: <ProfileScene />,
				station: 4,
			},
			...(now === undefined ? [] : [now]),
		],
	};
}

function yourProfile(facts: TourFacts): TourChapter {
	const { practices, feedbackCards, latestReview, ratingProps } = facts.page;
	const own = ownCardForTour(feedbackCards);
	const card = own ?? exampleCard(facts.openedAt, facts.provider);
	const rating = own === undefined ? undefined : ratingProps?.(own.feedbackId);
	const caption = own === undefined ? EXAMPLE : "Your newest open card";
	const open = feedbackCards.filter((each) => isOpenFeedback(each.state)).length;
	const words = standingWords(practices);
	const page = (
		<PracticeProfilePage
			{...facts.page}
			feedbackTab="open"
			onFeedbackTabChange={undefined}
			onOpenGroup={undefined}
			onOpenPractice={undefined}
			ratingProps={undefined}
			firstRun={facts.page.firstRun}
		/>
	);
	const onPage = { stage: page, stageKey: "page", caption: YOUR_PAGE };
	const onCard = {
		stage: <CardScene card={card} rating={rating} />,
		stageKey: `card:${card.feedbackId}`,
		caption,
	};
	return {
		id: "your-profile",
		name: "Your profile",
		question: "What am I looking at, and what do I do with it?",
		steps: [
			{
				...onPage,
				id: "chip",
				slot: "chip",
				title: "Your newest review",
				body: (
					<p>
						{latestReview === undefined
							? "The chip beside the title says that no review has recorded anything about your work yet. After the first one, it names your newest review and opens every review of your work."
							: "The chip beside the title names your newest review. Select it on your page to open every review of your work."}
					</p>
				),
			},
			{
				...onPage,
				id: "ring",
				slot: "ring",
				title: "Where you stand",
				body: (
					<>
						<p>
							The ring counts your practices by standing. Your standing comes from your recent
							reviewed work, and it is not a grade. Gray means there is nothing to judge yet.
						</p>
						{words !== "" && <p>Your practices now: {words}.</p>}
					</>
				),
			},
			{
				...onPage,
				id: "feedback",
				slot: "feedback",
				title: "Your feedback",
				body: (
					<p>
						{open > 0
							? `You have ${plural(open, "open card", "open cards")} here, newest first. Each card is one pattern seen across your work.`
							: "Cards appear here when reviews find the same problem in at least two pieces of your work. You have no open card now."}
					</p>
				),
			},
			{
				...onCard,
				id: "pattern",
				slot: "pattern",
				title: "One card is one pattern",
				body: (
					<p>
						{own === undefined
							? "This card is an example, not about your work. Its practice and pull requests are made up. "
							: `This is your newest open card: “${own.headline}”. `}
						The headline says in one line what keeps coming up. Above it are the practice and its
						group.
					</p>
				),
			},
			{
				...onCard,
				id: "why",
				slot: "why",
				title: "Why it was raised",
				body: <p>What the reviews saw, with links to the work, so you can check it yourself.</p>,
			},
			{
				...onCard,
				id: "next-step",
				slot: "next-step",
				title: "The one thing to try next",
				body: <p>One concrete thing to try in your next piece of work.</p>,
			},
			{
				...onCard,
				id: "resolves",
				slot: "resolves",
				title: "How a card goes away",
				body: (
					<p>
						Your reviewed work appears along a line, and the card counts the clean pieces it still
						needs. It resolves by itself when enough pieces in a row come back clean.
					</p>
				),
			},
			{
				...onCard,
				id: "mark",
				slot: "mark",
				title: "You can mark it yourself",
				body: (
					<p>
						If you fixed the problem, or it does not fit your work, say so here. The card moves to
						Marked by you, and your next work confirms it.
					</p>
				),
			},
			{
				...onCard,
				id: "rate",
				slot: "rate",
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

function goingDeeper(facts: TourFacts): TourChapter {
	const { groups } = facts.page;
	const firstGroup = groups.find((group) => (facts.practicesByGroup[group.slug] ?? []).length > 0);
	const firstPractice =
		firstGroup === undefined ? undefined : facts.practicesByGroup[firstGroup.slug]?.[0];
	const levels = [
		"Practice profile",
		"All practice groups",
		firstGroup?.name ?? "One practice group",
		firstPractice?.name ?? "One practice",
	];
	return {
		id: "going-deeper",
		name: "Going deeper",
		question: "Where is the detail behind this?",
		steps: [
			{
				id: "levels",
				title: "Each click opens one more panel",
				body: (
					<p>
						A panel slides in over the page. To go back, select a position on the path above its
						title, select the page beside the panels, or press Escape.
					</p>
				),
				caption: SCHEMATIC,
				stageKey: "levels",
				stage: <LevelsScene levels={levels} />,
				plays: true,
			},
			{
				id: "standings",
				title: "All practice groups: your standing and trend",
				body: (
					<p>
						One row for each group, with your standing and your trend in it. A trend compares your
						latest reviewed work with the work before it.
					</p>
				),
				caption: "What each label means",
				stageKey: "standings",
				stage: <StandingsScene />,
			},
			{
				id: "practice",
				title: "One practice has three tabs",
				body: (
					<p>
						Feedback, Observations and Guide. The Guide is a good place to start when a card is
						unclear.
					</p>
				),
				caption: SCHEMATIC,
				stageKey: "tabs",
				stage: <PracticeTabsScene />,
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
				stageKey: "try",
				live: true,
				stage: (
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
	return {
		id: "across-the-workspace",
		name: "Across the workspace",
		question: "How does the workspace look as a whole, and who can see me?",
		steps: [
			{
				id: "split",
				title: "How the workspace splits on each group",
				body: (
					<p>
						Practices across the workspace shows how many developers have each standing, for each
						practice group. Your Practice profile never compares you with other developers. This
						page is where you can look, when you want to.
					</p>
				),
				caption: EXAMPLE,
				stageKey: "split",
				stage: <SplitScene />,
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
				caption: EXAMPLE,
				stageKey: "split",
				stage: <SplitScene />,
			},
			{
				id: "try",
				title: "See it for your workspace",
				body: (
					<p>
						This link closes the tour and opens the page. From a group there, one link takes you to
						your own standing in that group, and from your profile one link takes you back.
					</p>
				),
				caption: "A real link",
				stageKey: "try-across",
				live: true,
				stage: (
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

export function tourChapters(facts: TourFacts): TourChapter[] {
	return [howItWorks(facts), yourProfile(facts), goingDeeper(facts), acrossTheWorkspace(facts)];
}
