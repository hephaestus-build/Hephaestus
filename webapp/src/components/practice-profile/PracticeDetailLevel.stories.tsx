import type { Meta, StoryObj } from "@storybook/react-vite";

import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";
import type { ResultList } from "@/api/types.gen";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { PracticeGuidanceState } from "@/components/practice-guidance/PracticeIntro";
import { ReviewResultDetails } from "@/components/profile/ReviewResultRow";
import type { ReviewFeedState } from "@/components/profile/reviews";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";
import { withPageBehind, withProvider } from "@/stories/decorators";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import { expectSettledVisible } from "@/stories/overlay";
import {
	detailPractices,
	detailResult,
	detailReviews,
	focusedChanges,
	metWithoutSummary,
	requestedCleanReview,
	unwrittenAbout,
} from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import { bundledGuidance, bundledPractice } from "@/stories/practice-guidance-story-mock-data";
import { packagingGroup } from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";
import { precedes } from "@/test/dom";

import { type ObservationFilter, observationLinkFilter } from "./observation-filter";
import { newestFirst } from "./practice-feedback-cards";
import {
	DEFAULT_PRACTICE_TAB,
	type PracticeProfileDetailLevelKind,
	type PracticeTab,
} from "./practice-profile-search";
import { PracticeDetailLevel, type PracticeDetailLevelProps } from "./PracticeDetailLevel";

const readyFeed = {
	status: "ready",
	reviews: detailReviews,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewFeedState;

/** The practice the preview fixtures write feedback about, both open and resolved. */
const describedPractice =
	detailPractices.find((practice) => practice.slug === "describe-what-and-why") ?? focusedChanges;

interface LevelState {
	stack: DetailStackEntry<PracticeProfileDetailLevelKind>[];
	tab: PracticeTab;
	filter: ObservationFilter;
	/** The feedback card a link asked for, until the level lets go of it on arrival. */
	marked?: string;
}

/** The bundled picture and guide; the introduction's own states are `PracticeIntro`'s stories. */
const readyGuidance = { status: "ready", ...bundledGuidance } satisfies PracticeGuidanceState;

/** The level with the ratings held in story state, where the route keeps them on the server. */
function RatedLevel(props: PracticeDetailLevelProps) {
	const { ratingProps } = useFeedbackRatings();
	return <PracticeDetailLevel {...props} ratingProps={ratingProps} />;
}

const meta = {
	component: PracticeDetailLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind, withProvider("GITHUB")],
	args: {
		path: {
			behind: [
				{ label: "Practice profile", depth: 0 },
				{ label: packagingGroup.name, depth: 1 },
			],
			onClose: fn(),
		},
		practice: focusedChanges,
		guidance: readyGuidance,
		feed: readyFeed,
		feedbackCards: ALL_FEEDBACK_CARDS,
		group: packagingGroup,
		onOpenGroup: fn(),
		tab: DEFAULT_PRACTICE_TAB,
		onTabChange: fn(),
		skeletonRows: 3,
		isLoading: false,
		onRetry: fn(),
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		feed: { control: false },
		// The close is the drawer stack's, which the render holds; only the crumbs come from here.
		path: { control: false },
		// A discriminated union, for the reason `feed` gives.
		guidance: { control: false },
	},
	render: (args) => (
		<StatefulPatch<LevelState>
			initial={{
				stack: [
					{ kind: "practice-group", id: packagingGroup.slug },
					{ kind: "practice", id: args.practice?.slug ?? focusedChanges.slug },
				],
				tab: args.tab,
				filter: args.observationFilter ?? {},
				marked: args.markedFeedbackId,
			}}
		>
			{(state, patch) => (
				<DetailDrawerStack
					stack={state.stack}
					onClose={(depth) => patch({ stack: state.stack.slice(0, depth) })}
				>
					{(entry, level) =>
						entry.kind === "practice" ? (
							<RatedLevel
								{...args}
								nested={level.nested}
								path={{
									behind: args.path.behind,
									onClose: (depth) => patch({ stack: state.stack.slice(0, depth) }),
								}}
								tab={state.tab}
								observationFilter={state.filter}
								onObservationFilterChange={(filter) => patch({ filter })}
								markedFeedbackId={state.marked}
								onFeedbackArrivalShown={() => patch({ marked: undefined })}
								onTabChange={(tab) => {
									args.onTabChange?.(tab);
									patch({ tab });
								}}
								onOpenGroup={() => {
									args.onOpenGroup?.();
									patch({ stack: state.stack.slice(0, 1) });
								}}
							/>
						) : (
							// A covered level is not inert, so it is a dialog with a name like any other.
							<>
								<DetailDrawerHeader nested={level.nested}>
									<DrawerTitle>{packagingGroup.name}</DrawerTitle>
								</DetailDrawerHeader>
								<DrawerBody />
							</>
						)
					}
				</DetailDrawerStack>
			)}
		</StatefulPatch>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeDetailLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

const olderSummary = detailReviews[1]?.results[0]?.summary;
if (!hasText(olderSummary)) {
	throw new Error("Expected a second review with a summary in the fixture feed.");
}
const newestSummary = detailResult.summary ?? "";

export const Default: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		// The heading names the practice as its group's table does: the gray pill with the group's
		// icon inside it, only larger.
		const heading = screen.getByRole("heading", { name: focusedChanges.name });
		const pill = heading.querySelector("svg")?.parentElement;
		await expect(pill).not.toBe(heading);
		await expect(getComputedStyle(pill ?? heading).backgroundColor).not.toBe("rgba(0, 0, 0, 0)");
		const [first, second, third] = screen.getAllByRole("tab");
		await expect(first).toHaveAccessibleName("Feedback 1");
		await expect(second).toHaveAccessibleName("Observations 3");
		await expect(third).toHaveAccessibleName("Guide");
		// The feedback leads its tab; the introduction and its picture wait in the Guide tab.
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
		await expect(
			within(screen.getByRole("tabpanel", { name: "Feedback 1" })).getByRole("heading", {
				level: 2,
				name: "Feedback",
			}),
		).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Feedback 1" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.getByRole("tab", { name: "Observations 3" })).toHaveAttribute(
			"aria-selected",
			"false",
		);
		await expect(screen.queryByRole("heading", { name: "Observations" })).not.toBeInTheDocument();
	},
};

export const ObservationsTab: Story = {
	args: {
		tab: "results",
		renderObservation: (result) =>
			result.id === detailResult.id ? <ReviewResultDetails result={detailResult} /> : undefined,
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		await expect(screen.getByRole("tab", { name: "Guide" })).toHaveAttribute(
			"aria-selected",
			"false",
		);
		// Below the top, dismissing returns to the group behind, so the control says Back.
		await expect(screen.getByRole("button", { name: "Back" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Observations 3" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.getByRole("tab", { name: "Feedback 1" })).toBeVisible();
		await expect(screen.getAllByText(newestSummary)).not.toHaveLength(0);
		await expect(screen.getAllByText(olderSummary)).not.toHaveLength(0);
		// The review also recorded a result on another practice; only this practice's is shown.
		await expect(screen.queryByText(metWithoutSummary.practiceName)).not.toBeInTheDocument();
		await expect(screen.getByRole("heading", { name: focusedChanges.name })).toBeVisible();
		await expect(screen.queryByText(/is evidence only/u)).not.toBeInTheDocument();
	},
};

const completeDetail = (result: ResultList) =>
	result.id === detailResult.id ? <ReviewResultDetails result={detailResult} /> : undefined;

/**
 * Opened from a review's "Open observation": the link sets the filter to the observation's work and
 * names the observation, which the route also reads on its own.
 */
const selectedArgs = {
	tab: "results",
	renderObservation: completeDetail,
	observationFilter: observationLinkFilter(detailResult),
	selectedObservation: { status: "ready", result: detailResult },
} satisfies Partial<PracticeDetailLevelProps>;

/**
 * Opened from a review at one observation: the tab is filtered to that observation's work, the
 * observation is open with its problems and next steps, and no other row is. It is in the history
 * once, with no pinned copy. Going to the Guide tab and back keeps it.
 */
export const SelectedObservation: Story = {
	args: selectedArgs,
	play: async () => {
		const history = await screen.findByRole("list", { name: "Reviews" });
		await expectSettledVisible(history);
		const observations = screen.getByRole("tabpanel", { name: /^Observations/u });
		await expect(within(observations).getByRole("searchbox", { name: "Search" })).toHaveValue(
			detailResult.item.label,
		);
		await expect(within(history).getAllByText(newestSummary)).toHaveLength(1);
		await expect(screen.queryByText(olderSummary)).not.toBeInTheDocument();
		// The asked observation wears the accent's bar; no open row carries an outcome-colored rail.
		await expect(history.querySelectorAll("[data-selected-rail]")).toHaveLength(1);
		await expect(history.querySelector("[data-outcome-rail]")).toBeNull();
		const open = within(history)
			.getAllByRole("button", { expanded: true })
			.filter((button) => button.hasAttribute("aria-controls"));
		await expect(open).toHaveLength(1);
		await expect(
			within(history).getByText("Move the class in one commit and add the cache in the next."),
		).toBeVisible();
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
		await expect(precedes(screen.getByRole("tablist", { name: "Practice" }), history)).toBe(true);

		await userEvent.click(screen.getByRole("tab", { name: "Guide" }));
		const guide = await screen.findByRole("tabpanel", { name: "Guide" });
		const intro = within(guide).getByRole("region", { name: "Introduction" });
		await expect(
			within(intro).getByRole("figure", { name: focusedChanges.whatGoodLooksLike ?? "" }),
		).toBeVisible();
		await expect(
			within(guide).getByRole("heading", { level: 3, name: "Practice guide" }),
		).toBeVisible();

		await userEvent.click(screen.getByRole("tab", { name: /^Observations/u }));
		const back = await screen.findByRole("list", { name: "Reviews" });
		await expect(within(back).getByText(newestSummary)).toBeVisible();
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
	},
};

/**
 * The linked observation's review is on a page not loaded yet: the observation joins the history
 * once, in its place by time, read on its own.
 */
export const SelectedObservationNotLoaded: Story = {
	args: {
		...selectedArgs,
		feed: { ...readyFeed, reviews: detailReviews.slice(1), hasMore: true },
		observationFilter: { observationId: detailResult.id },
	},
	play: async () => {
		const history = await screen.findByRole("list", { name: "Reviews" });
		await expectSettledVisible(history);
		await expect(within(history).getAllByText(newestSummary)).toHaveLength(1);
		await expect(within(history).getByText(olderSummary)).toBeVisible();
		await expect(
			precedes(within(history).getByText(newestSummary), within(history).getByText(olderSummary)),
		).toBe(true);
	},
};

/**
 * The linked observation, read on its own, stands while the rest of the history still loads: the
 * history's placeholder follows it, and it does not wait for the pages.
 */
export const SelectedObservationWhileHistoryLoads: Story = {
	args: {
		...selectedArgs,
		feed: { status: "loading" },
		observationFilter: { observationId: detailResult.id },
	},
	play: async () => {
		const history = await screen.findByRole("list", { name: "Reviews" });
		await expectSettledVisible(history);
		await expect(within(history).getAllByText(newestSummary)).toHaveLength(1);
		await expect(history.querySelectorAll("li[aria-current=true]")).toHaveLength(1);
	},
};

/** The rest of the history failed: the linked observation still stands, with the error beside it. */
export const SelectedObservationWhenHistoryFailed: Story = {
	args: {
		...selectedArgs,
		feed: { status: "error", error: new Error("offline"), onRetry: fn() },
		observationFilter: { observationId: detailResult.id },
	},
	play: async () => {
		const history = await screen.findByRole("list", { name: "Reviews" });
		await expectSettledVisible(history);
		await expect(within(history).getAllByText(newestSummary)).toHaveLength(1);
		await expectSettledVisible(screen.getByText("We could not load reviews"));
	},
};

/**
 * The linked observation is still on its way and no loaded page holds an observation: the history
 * shows the placeholder only, not a "No observations match" that the observation could still prove
 * wrong.
 */
export const SelectedObservationStillLoading: Story = {
	args: {
		...selectedArgs,
		feed: { ...readyFeed, reviews: [], hasMore: false },
		observationFilter: { observationId: detailResult.id },
		selectedObservation: { status: "loading" },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Observations/u }));
		screen.getByText("Loading the observation you opened…");
		await expect(screen.queryByText("No observations match your filters")).not.toBeInTheDocument();
	},
};

/**
 * The timeframe of Reviews of your work, in the tab row right of Guide: All time by default, so
 * nothing is hidden; a choice is kept with the other filters and read by the server.
 */
export const ObservationsTimeframe: Story = {
	args: { tab: "results", renderObservation: completeDetail },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Observations/u }));
		const range = screen.getByRole("toolbar", { name: "Timeframe" });
		// In the tab row, right of Guide: one setting for the Feedback and the Observations tab.
		await expect(screen.getByRole("tabpanel", { name: /^Observations/u })).not.toContainElement(
			range,
		);
		await expect(screen.getByRole("tablist", { name: "Practice" }).parentElement).toContainElement(
			range,
		);
		await expect(within(range).getByRole("button", { name: "All time" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(
			within(range)
				.getAllByRole("button")
				.map((button) => button.textContent),
		).toStrictEqual(["Last 7 days", "Last 30 days", "Last 90 days", "All time"]);
		await userEvent.click(within(range).getByRole("button", { name: "Last 30 days" }));
		await expect(within(range).getByRole("button", { name: "Last 30 days" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		// The timeframe is not one of the tab's own filters: no Reset appears for it, and no line
		// repeats the count the tab's name carries.
		await expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
		await expect(screen.queryByText(/match(?:es)? your filters/u)).not.toBeInTheDocument();
		await expect(screen.getByRole("tab", { name: /^Observations \d/u })).toBeVisible();
	},
};

/** A filter on the work: typing "#898" keeps that pull request's observation and opens it. */
export const FilterByWork: Story = {
	args: { tab: "results", renderObservation: completeDetail },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		const search = await screen.findByRole("searchbox", { name: "Search" });
		await userEvent.type(search, "#898");
		const history = screen.getByRole("list", { name: "Reviews" });
		await expect(within(history).getByText(olderSummary)).toBeVisible();
		await expect(within(history).queryByText(newestSummary)).not.toBeInTheDocument();
		// The tab's name carries the count; a screen reader hears it in a status of its own.
		await expect(screen.getByRole("tab", { name: "Observations 1" })).toBeVisible();
		await expect(screen.getByRole("status")).toHaveTextContent("1 observation shown.");
		await expect(screen.queryByText(/match(?:es)? your filters/u)).not.toBeInTheDocument();
		// Every observation the search found wears the accent's bar; none claims to be the current one,
		// which only a link's observation is.
		const observations = history.querySelectorAll("ul > li");
		await expect(observations.length).toBeGreaterThan(0);
		await expect(history.querySelectorAll("[data-selected-rail]")).toHaveLength(
			observations.length,
		);
		await expect(history.querySelector("[aria-current]")).toBeNull();
		// The search's own clear button, in the foreground like every control's icon.
		await expect(screen.getByRole("button", { name: "Clear search" })).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "Reset" }));
		await expectSettledVisible(await within(history).findByText(newestSummary));
		await expect(screen.getByRole("tab", { name: "Observations 3" })).toBeVisible();
		await expect(history.querySelectorAll("[data-selected-rail]")).toHaveLength(0);
	},
};

/** A filter that keeps nothing says so and offers the way back. */
export const FilterMatchesNothing: Story = {
	args: {
		tab: "results",
		renderObservation: completeDetail,
		observationFilter: { query: "no such work" },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("No observations match your filters"));
		await userEvent.click(screen.getByRole("button", { name: "Clear filters" }));
		await expect(await screen.findByText(newestSummary)).toBeVisible();
	},
};

/**
 * Only the timeframe narrows, and nothing falls in it: no Reset, as the timeframe has its own
 * control, and the way back is a longer timeframe. Reset lets go of the search and keeps the timeframe.
 */
export const TimeframeMatchesNothing: Story = {
	args: {
		tab: "results",
		renderObservation: completeDetail,
		feed: { ...readyFeed, reviews: [] },
		observationFilter: { since: "7d" },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("No observations match your filters"));
		await expect(screen.getByRole("tab", { name: "Observations 0" })).toBeVisible();
		await expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
		await userEvent.click(screen.getByRole("button", { name: "Show all time" }));
		await expect(
			within(screen.getByRole("toolbar", { name: "Timeframe" })).getByRole("button", {
				name: "All time",
			}),
		).toHaveAttribute("aria-pressed", "true");
	},
};

/** Reset clears the search and leaves the timeframe as chosen. */
export const ResetKeepsTheTimeframe: Story = {
	args: {
		tab: "results",
		renderObservation: completeDetail,
		observationFilter: { since: "30d", query: "#898" },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Observations/u }));
		await userEvent.click(screen.getByRole("button", { name: "Reset" }));
		await expect(screen.getByRole("searchbox", { name: "Search" })).toHaveValue("");
		await expect(
			within(screen.getByRole("toolbar", { name: "Timeframe" })).getByRole("button", {
				name: "Last 30 days",
			}),
		).toHaveAttribute("aria-pressed", "true");
		await expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

/** A practice with no standing still shows the linked observation, and its words in the Guide tab. */
export const SelectedObservationWithoutStanding: Story = {
	args: {
		...selectedArgs,
		practice: undefined,
		described: { name: "Pair on schema changes", ...bundledPractice },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("list", { name: "Reviews" }));
		await expect(screen.queryByText("Not reviewed")).toBeNull();
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
		await userEvent.click(screen.getByRole("tab", { name: "Guide" }));
		const guide = await screen.findByRole("tabpanel", { name: "Guide" });
		await expect(within(guide).getByText(bundledPractice.whyItMatters)).toBeVisible();
	},
};

export const SelectedObservationPhone: Story = {
	args: selectedArgs,
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await expect(window.innerWidth).toBe(320);
		await expect(window.innerHeight).toBe(568);
		const history = await screen.findByRole("list", { name: "Reviews" });
		await expectSettledVisible(history);
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
		await expectNoPanelOverflow(screen.getByRole("dialog", { name: focusedChanges.name }));
	},
};

export const RequestedReviewClean: Story = {
	args: {
		tab: "results",
		feed: { ...readyFeed, reviews: [requestedCleanReview, ...detailReviews] },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		await expect(screen.getAllByText("Needs attention")).not.toHaveLength(0);
		await expect(
			screen.getByText(/A requested review is evidence only and does not change your standing\./u),
		).toBeVisible();
	},
};

/**
 * Arrived from an observation's "Read it on this practice's Feedback tab": the card its feedback
 * became is scrolled into view and marked with the accent bar, and says it is the current one.
 */
export const FeedbackCardFromAnObservation: Story = {
	args: {
		tab: "feedback",
		practice: describedPractice,
		markedFeedbackId: [...ALL_FEEDBACK_CARDS]
			.reverse()
			.find((card) => card.practiceSlug === describedPractice.slug)?.feedbackId,
	},
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		const marked = document.querySelector("[data-marked-feedback]");
		await expect(marked).toHaveAttribute("aria-current", "true");
		// Washed once on arrival, not barred for good.
		await expect(marked?.querySelector("[data-arrival-highlight]")).not.toBeNull();
		await expect(marked?.querySelector("[data-selected-rail]")).toBeNull();
		await expect(marked?.querySelector("article")).not.toBeNull();
		await expect(document.querySelectorAll("[data-marked-feedback]")).toHaveLength(
			args.markedFeedbackId === undefined ? 0 : 1,
		);
		// Once per arrival: to Observations and back, the sheen does not play again.
		await userEvent.click(screen.getByRole("tab", { name: /^Observations/u }));
		await userEvent.click(screen.getByRole("tab", { name: /^Feedback/u }));
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		await expect(document.querySelector("[data-arrival-highlight]")).toBeNull();
		await expect(document.querySelector("[data-marked-feedback]")).toBeNull();
	},
};

/** A link may ask for a card the tab no longer lists: it says so instead of marking nothing. */
export const FeedbackCardNoLongerListed: Story = {
	args: {
		tab: "feedback",
		practice: describedPractice,
		markedFeedbackId: "00000000-0000-4000-8000-00000000f0ff",
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		await expect(
			screen.getByText(/The feedback you came from is no longer listed here/u),
		).toBeVisible();
		await expect(document.querySelector("[data-marked-feedback]")).toBeNull();
	},
};

/** The level's timeframe narrows the Feedback tab's cards too, by the time each card shows. */
export const FeedbackTimeframe: Story = {
	args: { tab: "feedback", practice: describedPractice, observationFilter: { since: "7d" } },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		const panel = screen.getByRole("tabpanel", { name: /^Feedback/u });
		const narrowed = within(panel).queryAllByRole("article").length;
		const range = screen.getByRole("toolbar", { name: "Timeframe" });
		await userEvent.click(within(range).getByRole("button", { name: "All time" }));
		await expect(within(panel).queryAllByRole("article").length).toBeGreaterThan(narrowed);
	},
};

const oldestDescribedCard = newestFirst(
	ALL_FEEDBACK_CARDS.filter((card) => card.practiceSlug === describedPractice.slug),
).at(-1);

/**
 * A link to a card older than the level's timeframe: the card shows and is highlighted once, and
 * it stays listed for the rest of the visit after the address lets go of its id; a visit to
 * Observations and back does not replay the highlight.
 */
export const FeedbackCardOutsideTimeframe: Story = {
	args: {
		tab: "feedback",
		practice: describedPractice,
		observationFilter: { since: "7d" },
		markedFeedbackId: oldestDescribedCard?.feedbackId,
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		const marked = document.querySelector("[data-marked-feedback]");
		await expect(marked).not.toBeNull();
		await expect(marked?.querySelector("[data-arrival-highlight]")).not.toBeNull();
		const title = oldestDescribedCard?.headline ?? "";
		// Once the highlight is over, the card is still listed.
		await waitFor(
			async () => expect(document.querySelector("[data-arrival-highlight]")).toBeNull(),
			{ timeout: 5000 },
		);
		await expect(screen.getByRole("article", { name: title })).toBeVisible();
		await userEvent.click(screen.getByRole("tab", { name: /^Observations/u }));
		await userEvent.click(screen.getByRole("tab", { name: /^Feedback/u }));
		await expectSettledVisible(await screen.findByRole("tabpanel", { name: /^Feedback/u }));
		await expect(screen.getByRole("article", { name: title })).toBeVisible();
		await expect(document.querySelector("[data-arrival-highlight]")).toBeNull();
	},
};

export const FeedbackTab: Story = {
	args: { tab: "feedback", practice: describedPractice },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { level: 2, name: "Feedback" }));
		await expect(screen.getByRole("heading", { level: 3, name: "Current feedback" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Feedback 2" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		// The open card stands on its own words; the resolved one keeps the words it was written in.
		const open = screen.getByRole("article", {
			name: "Descriptions name the files, not the problem",
		});
		const resolved = screen.getByRole("article", {
			name: "Descriptions named the what, rarely the why",
		});
		await expect(within(open).getByText("Open")).toBeVisible();
		await expect(
			screen.getByRole("heading", { level: 3, name: "Resolved feedback" }),
		).toBeVisible();
		await expect(within(resolved).getByText("Resolved 9 September")).toBeVisible();
		await userEvent.click(within(open).getByRole("button", { name: "Helpful" }));
		await expect(
			screen.getByRole("textbox", { name: "What worked about this feedback?" }),
		).toBeVisible();
		await expect(
			screen.queryByRole("button", { name: "Learn more about this practice" }),
		).not.toBeInTheDocument();
	},
};

export const FeedbackEmpty: Story = {
	// A practice no feedback was ever written about, so the tab has nothing to list.
	args: { tab: "feedback", practice: unwrittenAbout },
	play: async () => {
		await expectSettledVisible(await screen.findByText("No feedback yet"));
		await expect(
			screen.getByText(
				"Feedback appears here once a review sees the same pattern in your work more than once. Feedback posted on a single piece of work stays there. Open its observation to find recorded comment links.",
			),
		).toBeVisible();
		await expect(screen.getByRole("heading", { level: 2, name: "Feedback" })).toBeVisible();
		await expect(screen.queryByText("Current feedback")).not.toBeInTheDocument();
		await expect(screen.queryByText("Resolved feedback")).not.toBeInTheDocument();
	},
};

export const FeedbackOnTheWorkOnly: Story = {
	args: { tab: "feedback", feedbackCards: [] },
	play: async () => {
		await expectSettledVisible(await screen.findByText("No feedback yet"));
		await userEvent.click(screen.getByRole("tab", { name: "Observations 3" }));
		await expect(screen.getByRole("tab", { name: "Observations 3" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.getAllByText(newestSummary)).not.toHaveLength(0);
	},
};

export const GuideTab: Story = {
	args: { tab: "guide" },
	play: async () => {
		await expectSettledVisible(
			await screen.findByRole("heading", { level: 3, name: "Practice guide" }),
		);
		await expect(screen.getByRole("tab", { name: "Guide" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		const panel = screen.getByRole("tabpanel", { name: "Guide" });
		for (const heading of [
			"How to do it",
			"When it does not apply",
			"Common mistakes",
			"Sources",
		]) {
			await expect(within(panel).getByRole("heading", { level: 4, name: heading })).toBeVisible();
		}
		await expect(
			within(panel).getByRole("img", { name: /^Three changes follow in order/u }),
		).toBeVisible();
		// The introduction and its picture lead the tab, then the guide.
		const intro = within(panel).getByRole("region", { name: "Introduction" });
		await expect(
			within(intro).getByRole("figure", { name: focusedChanges.whatGoodLooksLike ?? "" }),
		).toBeVisible();
		await expect(
			precedes(intro, within(panel).getByRole("heading", { level: 3, name: "Practice guide" })),
		).toBe(true);
		await expect(screen.queryByText("Read more")).not.toBeInTheDocument();
	},
};

/** A practice with a picture and no guide still has its Guide tab: the introduction and picture. */
export const WithoutLongGuide: Story = {
	args: { tab: "guide", guidance: { ...readyGuidance, guide: null } },
	play: async () => {
		const panel = await screen.findByRole("tabpanel", { name: "Guide" });
		await expectSettledVisible(panel);
		await expect(screen.getByRole("tab", { name: "Guide" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		const intro = within(panel).getByRole("region", { name: "Introduction" });
		await expect(within(intro).getByText(focusedChanges.whyItMatters ?? "")).toBeVisible();
		await expect(
			within(intro).getByRole("img", { name: bundledGuidance.visual.alt }),
		).toBeVisible();
		await expect(
			within(panel).queryByRole("heading", { name: "Practice guide" }),
		).not.toBeInTheDocument();
	},
};

/**
 * A practice with no words, picture or guide has nothing for a Guide tab, so a link that asks for
 * the guide lands on the feedback instead of an empty tab.
 */
export const NothingToExplain: Story = {
	args: {
		tab: "guide",
		practice: { ...focusedChanges, whyItMatters: null, whatGoodLooksLike: null },
		guidance: { ...readyGuidance, visual: null, guide: null },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { level: 2, name: "Feedback" }));
		await expect(screen.getByRole("tab", { name: "Feedback 1" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.queryByRole("tab", { name: "Guide" })).not.toBeInTheDocument();
		await expect(screen.queryByRole("region", { name: "Introduction" })).not.toBeInTheDocument();
	},
};

/** No "Where you stand" section: the header's badge and trend carry the standing. */
export const WithoutWhereYouStand: Story = {
	args: { tab: "results" },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		await expect(screen.queryByText("Where you stand")).not.toBeInTheDocument();
		await expect(
			screen.queryByText(/counts most, and older work counts less/u),
		).not.toBeInTheDocument();
	},
};

// Loaded pages cannot establish an exact count until every page is known.
export const MoreToLoad: Story = {
	args: { tab: "results", feed: { ...readyFeed, hasMore: true } },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("button", { name: "View earlier reviews" }));
		await expect(screen.getByRole("tab", { name: "Observations" })).toBeVisible();
		await expect(screen.queryByRole("tab", { name: /^Observations \d/u })).not.toBeInTheDocument();
		// The feedback cards arrive in one piece, so that tab is counted either way.
		await expect(screen.getByRole("tab", { name: "Feedback 1" })).toBeVisible();
	},
};

/**
 * A practice no review has settled: the badge and the trend in the header say so, and nothing
 * claims a basis or a direction.
 */
export const NotReviewed: Story = {
	args: {
		practice: {
			...focusedChanges,
			standing: "NOT_OBSERVED",
			direction: null,
			trendSupport: null,
		},
		feed: { ...readyFeed, reviews: [] },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { level: 2, name: "Feedback" }));
		await expect(screen.getAllByText("Not reviewed")).toHaveLength(1);
		await expect(screen.queryByText(/No practice in this group/u)).not.toBeInTheDocument();
		await expect(screen.queryByText(/Read from|counts most/u)).not.toBeInTheDocument();
		await expect(screen.queryByText(/pieces? of work$/u)).not.toBeInTheDocument();
		// The header's chip is the only one: no direction is claimed over no verdict.
		await expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
	},
};

/**
 * The rail and the tab it opens on each draw their own shape, so nothing jumps when the level
 * lands: a feedback card by default.
 */
export const Loading: Story = {
	args: { isLoading: true, practice: undefined },
	play: async () => {
		await screen.findByText("Loading feedback…");
		await expect(screen.queryByRole("tab")).toBeNull();
	},
};

export const LoadingObservations: Story = {
	args: { isLoading: true, practice: undefined, tab: "results" },
	play: async () => {
		await screen.findByText("Loading reviews…");
		await expect(screen.queryByRole("tab")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { error: new Error("Unavailable") },
	play: async () => {
		await expectSettledVisible(
			await screen.findByText(`We could not load your standing for ${focusedChanges.name}`),
		);
	},
};

export const GuidanceOnly: Story = {
	args: {
		practice: undefined,
		described: { name: "Pair on schema changes", ...bundledPractice },
	},
	play: async () => {
		await expectSettledVisible(
			await screen.findByText("This practice offers guidance without automated review."),
		);
		await expect(screen.getByRole("heading", { name: "Pair on schema changes" })).toBeVisible();
		await expect(screen.getByText(bundledPractice.whyItMatters)).toBeVisible();
		await expect(screen.getByRole("heading", { level: 3, name: "Practice guide" })).toBeVisible();
		await expect(screen.queryByRole("tab")).toBeNull();
		await expect(screen.queryByRole("heading", { name: "Observations" })).toBeNull();
		await expect(screen.queryByText("Not reviewed")).toBeNull();
	},
};

/** The server answers 404 for a practice that this workspace does not review, which offers no retry. */
export const Missing: Story = {
	args: {
		practice: undefined,
		guidance: { status: "error", error: { status: 404 }, onRetry: fn() },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText(/We could not find this practice\./u));
		await expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};

/** Any other failure says nothing about whether the practice exists, so it offers the retry. */
export const MissingLoadFailed: Story = {
	args: {
		practice: undefined,
		guidance: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
	},
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("We could not load this practice"));
		await expect(screen.queryByText(/We could not find this practice\./u)).toBeNull();
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		if (args.guidance.status !== "error") {
			throw new Error("Expected the guidance to have failed.");
		}
		await expect(args.guidance.onRetry).toHaveBeenCalledOnce();
	},
};

export const MobileReflow: Story = {
	args: { tab: "results" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		// The frontmost panel: the group level behind it is a placeholder with no body to measure.
		const panels = document.querySelectorAll<HTMLElement>('[data-slot="drawer-popup"]');
		const frontmost = [...panels].at(-1);
		if (!frontmost) {
			throw new Error("Expected the practice level to be open.");
		}
		await expectNoPanelOverflow(frontmost);
	},
};

// --- The pattern label and the folds ------------------------------------------------------------

/** The pattern stays in the group's table, never in this header. */
export const PatternNotInHeader: Story = {
	args: { practice: describedPractice },
	play: async () => {
		await expectSettledVisible(
			await screen.findByRole("heading", { name: describedPractice.name }),
		);
		await expect(screen.queryByText(/^Has open feedback/u)).not.toBeInTheDocument();
	},
};

/** A practice that never applied says so instead of a gap. */
export const NeverApplied: Story = {
	args: {
		practice: { ...focusedChanges, standing: "NO_OPPORTUNITY", doesNotApplyOnly: true },
		neverApplied: true,
	},
	play: async () => {
		await expect(await screen.findAllByText("Does not apply to your work yet")).not.toHaveLength(0);
	},
};

/**
 * The standing includes earlier assessments whose criterion version is unavailable. The level marks
 * that basis beside the badge; those assessments provide no trend or quoted feedback.
 */
export const EarlierReviewsCriteriaUnknown: Story = {
	args: {
		practice: {
			...focusedChanges,
			standing: "MIXED",
			basis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN",
			direction: null,
			trendSupport: null,
			toWorkOn: [],
			strengths: [],
		},
	},
	play: async () => {
		const mark = await screen.findByRole("button", {
			name: "Based on earlier reviews, criteria unknown",
		});
		await expect(mark).toHaveAccessibleDescription(
			/They do not show whether the current criteria are met\./u,
		);
	},
};

/** A practice its current reviews decided carries no earlier-reviews mark. */
export const CurrentReviewsCarryNoMark: Story = {
	args: { practice: { ...focusedChanges, basis: "CURRENT_REVIEWS" } },
	play: async () => {
		await screen.findAllByRole("heading");
		await expect(screen.queryByRole("button", { name: /^Based on earlier reviews/u })).toBeNull();
	},
};

export const NeverAppliedDark: Story = {
	args: { practice: describedPractice, neverApplied: true },
	globals: { theme: "dark" },
};

export const NeverAppliedPhone: Story = {
	args: { practice: describedPractice, neverApplied: true },
	globals: { viewport: { value: "mobile" } },
};

export const ObservationLoading: Story = {
	args: {
		tab: "results",
		renderObservation: () => (
			<div aria-busy="true" className="flex flex-col gap-3">
				<span className="sr-only">Loading observation…</span>
				<Skeleton className="h-5 w-3/4" />
				<Skeleton className="h-16 w-full" />
			</div>
		),
	},
};

export const ObservationError: Story = {
	args: {
		tab: "results",
		renderObservation: () => (
			<QueryErrorAlert
				error={{ status: 503 }}
				title="We could not load this observation"
				onRetry={fn()}
			/>
		),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		await expect(screen.getByText("We could not load this observation")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};

export const CompleteObservationPhone: Story = {
	...ObservationsTab,
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await expect(window.innerWidth).toBe(320);
		const heading = await screen.findByRole("heading", { name: "Observations" });
		await expectSettledVisible(heading);
		await expect(
			screen.getByText("Move the class in one commit and add the cache in the next."),
		).toBeVisible();
		await expect(
			screen.getByText("Say in the description that the loader now caches per workspace."),
		).toBeVisible();
		const panel = heading.closest<HTMLElement>('[data-slot="drawer-popup"]');
		if (panel === null) {
			throw new Error("The observation belongs to a drawer");
		}
		await expectNoPanelOverflow(panel);
	},
};
