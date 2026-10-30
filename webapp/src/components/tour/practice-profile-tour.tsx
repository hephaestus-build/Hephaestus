import type { TourStep, TourTarget } from "./ProductTour";
import {
	AcrossPage,
	FeedbackPlacesPanel,
	FlowOverviewPanel,
	GroupPanel,
	GroupStandingPanel,
	LayersPanel,
	ObservationPanel,
	OnePracticePanel,
	PatternPanel,
	ProfileContentsPanel,
	ProfileWithLevels,
	WorkPanel,
} from "./tour-scenes";

/** The smallest element whose own text starts with `text`, inside `root`. */
function byText(text: string, root: ParentNode | null | undefined): HTMLElement | null {
	if (!root) {
		return null;
	}
	const candidates = [
		...root.querySelectorAll<HTMLElement>("h1, h2, h3, h4, p, span, a, button, div, th, td, li"),
	];
	return (
		candidates
			.filter((element) => element.textContent.trim().startsWith(text))
			.sort((a, b) => a.textContent.length - b.textContent.length)
			.at(0) ?? null
	);
}

/** The first open feedback card in `root`: the one with a next step band. */
function openCard(root: ParentNode): HTMLElement | null {
	const edge = root.querySelector("article [data-next-step-edge]");
	return edge?.closest("article") ?? root.querySelector("article");
}

function up(element: Element | null | undefined, levels: number): Element | null {
	let at = element ?? null;
	for (let level = 0; level < levels && at?.parentElement; level += 1) {
		at = at.parentElement;
	}
	return at;
}

/** The first group row of the All practice groups table: the one the steps explain throughout. */
function firstGroupRow(root: ParentNode): HTMLElement | null {
	return root.querySelector("table tbody tr");
}

function firstWord(words: string[], within: ParentNode | null | undefined): HTMLElement | null {
	for (const word of words) {
		const found = byText(word, within);
		if (found) {
			return found;
		}
	}
	return null;
}

/** One tab of the practice level, by its name. */
function tab(name: string, root: ParentNode): Element | null {
	return (
		[...root.querySelectorAll<HTMLElement>('[role="tab"]')].find((each) =>
			each.textContent.trim().startsWith(name),
		) ?? null
	);
}

/**
 * The strip left of the lowest open level, over the dimmed page: a press there closes every level,
 * as in the app.
 */
function besideLevels(): TourTarget | null {
	const popups = [...document.querySelectorAll('[data-slot="drawer-popup"]')];
	if (popups.length === 0) {
		return null;
	}
	const right = Math.min(...popups.map((popup) => popup.getBoundingClientRect().left));
	const area = new DOMRect(8, 72, Math.max(0, right - 24), window.innerHeight - 144);
	// The strip is no element of its own, so there is nothing to scroll into view.
	return { getBoundingClientRect: () => area, scrollIntoView: () => undefined };
}

const STANDING_WORDS = ["Needs attention", "Mixed feedback", "Going well", "No standing yet"];
const TREND_WORDS = [
	"Not enough to compare",
	"Direction unclear",
	"More positive",
	"More difficulties",
];

const HOW = "How it works";
const PROFILE = "Your profile";
const DEEPER = "Going deeper";
const COMPARE = "Compare";

const EXAMPLE = "Example: a Practice profile after a few weeks of reviews";
const PANEL = 470;
const PANEL_SMALL = 340;

/** Every step on the example profile shares one page, so the app's levels slide in and out over it. */
const onProfile = (extra: Partial<TourStep> & Pick<TourStep, "title" | "body">): TourStep => ({
	sceneKey: "profile",
	pageLabel: EXAMPLE,
	page: <ProfileWithLevels />,
	...extra,
});

const onAcross = (extra: Partial<TourStep> & Pick<TourStep, "title" | "body">): TourStep => ({
	sceneKey: "across",
	pageLabel: "Example: Practices across the workspace",
	page: <AcrossPage />,
	chapter: COMPARE,
	...extra,
});

export const PRACTICE_PROFILE_TOUR: TourStep[] = [
	// 1 · How it works: Heph explains in its own window; the stations stay in view at its top.
	{
		chapter: HOW,
		title: "Hi, I’m Heph. Let me show you around.",
		body: "First I explain how feedback comes about. Then we look at an example profile together, and at where each click takes you. It is feedback for learning, not a grade.",
	},
	{
		chapter: HOW,
		panel: <FlowOverviewPanel />,
		sceneKey: "overview",
		title: "The whole way, at a glance",
		body: "This is how it works overall, in five stations. Now let’s go through them step by step.",
	},
	{
		chapter: HOW,
		stage: 0,
		stageIntro: true,
		panelHeight: PANEL_SMALL,
		panel: <WorkPanel />,
		sceneKey: "work",
		title: "It starts with your pull request",
		body: "You work as usual: you open a pull request or merge request on GitHub or GitLab. When it is ready, I review it.",
	},
	{
		chapter: HOW,
		stage: 1,
		panelHeight: PANEL,
		panel: <OnePracticePanel />,
		sceneKey: "practice",
		title: "I review it against practices",
		body: "Your course has many practices. Let’s take one out: a practice is one way of working, such as describing why a change is needed, and it says why it matters and what good looks like.",
	},
	{
		chapter: HOW,
		stage: 1,
		panelHeight: PANEL,
		panel: <GroupPanel />,
		sceneKey: "groups-def",
		title: "Related practices form a practice group",
		body: "So that you see the bigger topics, the practices are sorted into a few groups, such as packaging your work for review or testing your changes.",
	},
	{
		chapter: HOW,
		stage: 2,
		panelHeight: PANEL,
		panel: <ObservationPanel />,
		sceneKey: "observation",
		title: "What I see is an observation",
		body: "I look at your pull request one practice at a time and note what I saw: met or not met, always with the evidence from your work, so you can check it yourself.",
	},
	{
		chapter: HOW,
		stage: 3,
		panelHeight: PANEL_SMALL,
		panel: <FeedbackPlacesPanel />,
		sceneKey: "places",
		title: "My feedback reaches you in two places",
		body: "1 is about one pull request: a comment on it, right after the review. 2 is about a pattern across your work: a feedback card on your profile. Let’s look at 2.",
	},
	{
		chapter: HOW,
		stage: 3,
		panelHeight: PANEL_SMALL,
		panel: <PatternPanel />,
		sceneKey: "pattern",
		title: "A repeated problem becomes a feedback card",
		body: "When the same practice is not met in two or more of your pull requests, I write one card about the pattern, with one thing to try next. This card is what you see on your profile.",
	},
	{
		chapter: HOW,
		stage: 4,
		panelHeight: PANEL_SMALL,
		panel: <ProfileContentsPanel />,
		sceneKey: "contents",
		title: "Your profile collects all of it",
		body: "And it shows much more than the cards. Only you see your profile.",
	},
	// 2 · Your profile: the example page; Heph points at its parts.
	onProfile({
		chapter: PROFILE,
		title: "Let’s look at a profile",
		body: "This is how your profile could look after a few weeks of reviews. It is an example, not your data.",
	}),
	onProfile({
		chapter: PROFILE,
		title: "Where you stand at a glance",
		body: "The ring counts your practices by standing: needs attention, mixed or going well. Gray means there is nothing to judge yet.",
		target: (root) =>
			byText("See all practice groups", root)?.closest('[class*="group/panel"]') ?? null,
	}),
	onProfile({
		chapter: PROFILE,
		title: "A feedback card",
		body: "One card is one pattern: the practice and its group at the top, then in one line what keeps coming up.",
		target: (root) => openCard(root),
		padding: 8,
	}),
	onProfile({
		chapter: PROFILE,
		title: "Why it was raised",
		body: "The pull requests where it came up, each linked, so you can look at them again.",
		target: (root) => up(byText("Why this was raised", openCard(root) ?? root), 1),
	}),
	onProfile({
		chapter: PROFILE,
		title: "The one thing to try next",
		body: "The blue band is the next step: one concrete thing to try in your next piece of work.",
		target: (root) => openCard(root)?.querySelector("[data-next-step-edge]")?.parentElement ?? null,
	}),
	onProfile({
		chapter: PROFILE,
		title: "How a card goes away",
		body: "Each pull request reviewed after the card adds a dot here. When three in a row come back clean, the card resolves by itself. If you fixed it or it does not fit your work, mark it yourself; your next work confirms it.",
		target: (root) => up(byText("Work reviewed for this practice", openCard(root) ?? root), 2),
	}),
	onProfile({
		chapter: PROFILE,
		title: "Tell me what you think",
		body: "Thumbs up, thumbs down or a comment. Your answer stays with the card and helps improve the feedback.",
		target: (root) => root.querySelector('[aria-label="Rate this feedback"]'),
		padding: 8,
	}),
	// 3 · Going deeper: the levels, then the app's own levels sliding in over the example.
	onProfile({
		chapter: DEEPER,
		panel: <LayersPanel />,
		title: "Your profile has levels",
		body: "Each click opens one more level that slides in over the page. To go back, click a crumb at the top, click the page on the left beside the levels, or press Escape.",
	}),
	onProfile({
		chapter: DEEPER,
		title: "Click “See all practice groups”",
		body: "This opens the first level: every practice group with your standing in it.",
		target: (root) => byText("See all practice groups", root)?.closest("a") ?? null,
		padding: 8,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		inLevel: true,
		title: "All practice groups",
		body: "One row per group. Let’s read the first row together.",
		target: (root) => firstGroupRow(root),
		padding: 4,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		inLevel: true,
		title: "Your standing in this group",
		body: "Where you stand: needs attention, mixed or going well. For one practice, I read your four most recent reviewed pull requests, the newest counting most: almost all clean is going well, mostly problems needs attention, in between mixed.",
		target: (root) => {
			const found = firstWord(STANDING_WORDS, firstGroupRow(root)?.querySelector("td"));
			return found?.closest('[data-slot="badge"]') ?? found;
		},
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		inLevel: true,
		title: "Your trend in this group",
		body: "The trend compares your latest reviewed work with the work before it, in the last 90 days: more positive, more difficulties, direction unclear, or not enough to compare yet.",
		target: (root) => {
			const found = firstWord(TREND_WORDS, firstGroupRow(root)?.querySelector("td"));
			return found?.closest("button, span") ?? found;
		},
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		panel: <GroupStandingPanel />,
		title: "Practice level and group level",
		body: "Standing and trend work the same on both levels. On the practice level each practice has its own standing; on the group level they are added up: each practice is one part of the ring, and the group’s standing follows from it.",
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		inLevel: true,
		title: "What changed since your last review",
		body: "Here you see the updates: which practices moved, and where the group stands now.",
		target: (root) => {
			const lists = firstGroupRow(root)?.querySelectorAll("ul");
			return lists === undefined ? null : ([...lists].at(-1) ?? null);
		},
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups"]} />,
		inLevel: true,
		title: "Click “Open group”",
		body: "This opens the next level: the group with all its practices.",
		target: (root) => byText("Open group", firstGroupRow(root)),
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group"]} />,
		inLevel: true,
		title: "One group: its practices",
		body: "Every practice of the group with your standing in it. A practice is where the details are; a click on “Open practice” opens it.",
		target: (root) => root.querySelector("table tbody tr") ?? root.querySelector("table"),
		padding: 4,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group", "practice"]} practiceTab="feedback" />,
		inLevel: true,
		title: "One practice: three tabs",
		body: "Each practice has three tabs: Feedback, Observations and Guide. Let’s open them one by one.",
		target: (root) => root.querySelector('[role="tablist"]'),
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group", "practice"]} practiceTab="feedback" />,
		inLevel: true,
		title: "Feedback",
		body: "The practice’s feedback card, if there is one, with its progress and next step.",
		target: (root) => tab("Feedback", root),
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group", "practice"]} practiceTab="results" />,
		inLevel: true,
		title: "Observations",
		body: "Every observation of this practice on your work, newest first: met or not met, with the evidence. Open one to read it in full.",
		target: (root) => tab("Observations", root),
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group", "practice"]} practiceTab="guide" />,
		inLevel: true,
		title: "Guide",
		body: "The guide explains the practice: why it matters, what good looks like, with examples. A good place to start when a card is unclear.",
		target: (root) => tab("Guide", root),
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["groups", "group", "practice"]} practiceTab="guide" />,
		clickHint: true,
		title: "Let’s go back to the home view",
		body: "A click on the page beside the levels closes them, here all at once. Click Next and I click there for you.",
		target: () => besideLevels(),
		padding: 0,
	}),
	onProfile({
		chapter: DEEPER,
		title: "Back on your profile",
		body: "All levels slid away; you are back where you started.",
	}),
	onProfile({
		chapter: DEEPER,
		title: "Click “Latest review”",
		body: "The chip next to the title opens another level: every review of your work.",
		target: (root) => byText("Latest review", root)?.closest("a, button") ?? null,
		padding: 6,
	}),
	onProfile({
		chapter: DEEPER,
		page: <ProfileWithLevels levels={["reviews"]} />,
		inLevel: true,
		title: "Reviews of your work",
		body: "Each reviewed pull request and what I saw. A click on “See the reviewed practices” opens that one review with every practice it checked and their observations. After you changed a pull request, you can ask for another review here.",
		target: (root) => byText("See the reviewed practices", root)?.closest("tr") ?? null,
		padding: 4,
	}),
	// 4 · Compare: through the sidebar to the comparison, then back to the profile from the same place.
	onProfile({
		chapter: COMPARE,
		title: "Click “Across the workspace” in the sidebar",
		body: "Your profile is only for you; nobody else sees it. To see how you compare with the course, open “Across the workspace” in the sidebar, under “Your practices”.",
		target: () => document.querySelector('a[href*="practices-across-the-workspace"]'),
		padding: 6,
	}),
	onAcross({
		title: "How the course is doing, anonymously",
		body: "This page shows how everyone in your course splits on each practice group. Nobody is named; only your own place is marked for you.",
	}),
	onAcross({
		title: "Your figures next to the typical range",
		body: "Your own numbers, such as reviewed pull requests or practices going well, each with the range where most of the course lies.",
		target: (root) =>
			byText("Your figures", root)?.closest("section") ?? up(byText("Your figures", root), 3),
	}),
	onAcross({
		title: "Each group: how the course splits",
		body: "One bar per group: how many developers need attention, are mixed or going well. “You” marks your own place. “Open group” shows the same split per practice.",
		target: (root) => root.querySelector("table"),
	}),
	onAcross({
		page: <AcrossPage level />,
		inLevel: true,
		title: "One group, across the workspace",
		body: "The split for each practice of the group. Nobody is named; “You” marks your own place.",
		target: (root) => root.querySelector("table") ?? root.querySelector("ul"),
		padding: 4,
	}),
	onAcross({
		page: <AcrossPage level />,
		inLevel: true,
		title: "Click “See your own standing”",
		body: "At the foot of every level: one click takes you to the same group in your own profile.",
		target: (root) => byText("See your own standing", root)?.closest("button") ?? null,
		padding: 6,
	}),
	onProfile({
		chapter: COMPARE,
		page: <ProfileWithLevels levels={["groups", "group"]} />,
		inLevel: true,
		title: "Back in your profile, at the same group",
		body: "And at the same place, “See this group across the workspace” takes you over again. So you switch between your profile and the comparison in one click, both ways.",
		target: (root) => byText("See this group across", root)?.closest("button") ?? null,
		padding: 6,
	}),
	{
		end: true,
		title: "That’s it! You find this tour here",
		body: "In short: I review your pull requests against your course’s practices, comment on each one, and turn repeated problems into a card on your profile, with one next step. Whenever you want to see it again, click “Take the tour” here.",
		target: () => document.querySelector("[data-tour-start]"),
		padding: 6,
	},
];
