import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { ReviewOverview } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import {
	feedbackCounts,
	practiceReviewOverview,
	quietPracticeReviewOverview,
	reviewOverviewScope,
} from "./fixtures";
import type { OverviewRegionState, PreviousReviewPeriodState } from "./review-states";
import { ReviewPipeline } from "./ReviewPipeline";

const PREVIOUS: ReviewOverview = {
	...quietPracticeReviewOverview,
	reviews: { complete: 30, partial: 0, failed: 0, cancelled: 0, running: 0, queued: 0 },
	results: { met: 40, notMet: 30, doesNotApply: 5, couldNotDetermine: 3 },
	feedback: { ...feedbackCounts([]), delivered: 30 },
};

const READY: OverviewRegionState = {
	status: "ready",
	overview: practiceReviewOverview,
	stale: false,
};

const COURSE_DAY: ReviewOverview = {
	...practiceReviewOverview,
	feedback: {
		...feedbackCounts([]),
		delivered: 60,
		ready: 5,
		failed: 2,
		withheld: 30,
		rejected: 3,
		replaced: 20,
	},
};

function readyOf(overview: ReviewOverview): OverviewRegionState {
	return { status: "ready", overview, stale: false };
}

const PREVIOUS_READY: PreviousReviewPeriodState = {
	status: "ready",
	period: { overview: PREVIOUS },
};

/**
 * What the reviews did over the range, a tile per stage in the recipe of Activity's tiles: the
 * total, how it compares with the period of the same length before, its volume over the range, and
 * what it split into. Volume is drawn in the neutral foreground — only an outcome carries colour
 * on a practice surface — and the bars are hidden from assistive technology, since the total and
 * its legend say the same in words.
 */
const meta = {
	component: ReviewPipeline,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: READY,
		previous: PREVIOUS_READY,
		previousName: "the previous 30 days",
		scope: reviewOverviewScope,
	},
	argTypes: {
		state: { control: false },
		previous: { control: false },
	},
} satisfies Meta<typeof ReviewPipeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const stages = canvas.getByRole("list", { name: "Stages" });
		await expect(stages).not.toHaveAttribute("aria-busy");
		// Each total is set against the period before it, without a verdict either way.
		canvas.getByText("9 more than the previous 30 days");
		canvas.getByText("Same as the previous 30 days");
		canvas.getByText("5 fewer than the previous 30 days");
		// This preview has no workspace, so nothing links; with one, each title opens its tab
		// (TitlesOpenTheirTabs).
		await expect(within(stages).queryByRole("link")).not.toBeInTheDocument();
		within(stages).getByText("Reviews");
		await expect(
			within(canvas.getByRole("list", { name: "Reviews not finished yet" })).getByRole("listitem"),
		).toHaveTextContent("3 not finished yet");
		await expect(canvas.getByTitle("3 of 35 finished reviews")).toHaveTextContent("9% failed");
		await expect(canvas.queryByText(/\brunning\b/u)).not.toBeInTheDocument();
		// Every tile has the same rows: title, figure, comparison, chart, then the row of figures, so
		// the charts of the tiles side by side stand at the same height and are as tall.
		const charts = [...stages.querySelectorAll("[data-slot=chart]")].map((chart) =>
			chart.getBoundingClientRect(),
		);
		await expect(charts).toHaveLength(3);
		await expect(new Set(charts.map((chart) => Math.round(chart.top))).size).toBe(1);
		await expect(new Set(charts.map((chart) => Math.round(chart.height))).size).toBe(1);
		await expect(
			within(canvas.getByRole("list", { name: "Reviews by outcome" }))
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual(["31 complete", "1 partial", "3 failed", "1 cancelled"]);
		await expect(
			within(canvas.getByRole("list", { name: "Feedback by delivery" }))
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual([
			"3 awaiting approval",
			"1 ready",
			"14 delivered",
			"1 delivery unconfirmed",
			"4 withheld",
			"1 failed to deliver",
			"1 replaced by newer",
		]);
		await expect(
			within(canvas.getByRole("list", { name: "Observations checked by an admin" })).getByRole(
				"listitem",
			),
		).toHaveTextContent("3 marked incorrect");
	},
};

/**
 * Each card's title opens its tab in the card's scope: the title is where one clicks, the tabs sit
 * far above. It reads as a title, underlined on hover and focus only.
 */
export const TitlesOpenTheirTabs: Story = {
	args: { workspaceSlug: "demo" },
	play: async ({ canvas, userEvent }) => {
		for (const [title, path] of [
			["Reviews", "/w/demo/admin/practices/reviews"],
			["Observations", "/w/demo/admin/practices/reviews/results"],
			["Feedback", "/w/demo/admin/practices/reviews/feedback"],
		] as const) {
			// The link's name is the title alone, not the title twice or with its icon.
			const link = canvas.getByRole<HTMLAnchorElement>("link", { name: title });
			const target = new URL(link.href);
			await expect(target.pathname).toBe(path);
			await expect(target.searchParams.get("from")).toBe(reviewOverviewScope.from);
			await expect(target.searchParams.get("to")).toBe(reviewOverviewScope.to);
		}
		// At rest it reads as a title, not as an underlined count.
		const results = canvas.getByRole("link", { name: "Observations" });
		const restColor = getComputedStyle(results).color;
		await expect(getComputedStyle(results).textDecorationLine).toBe("none");
		// Reached by Tab like any link, and drawn as every link on the page is when focused (and
		// hovered): in the accent and underlined.
		await userEvent.tab();
		const reviews = canvas.getByRole("link", { name: "Reviews" });
		await expect(reviews).toHaveFocus();
		await expect(getComputedStyle(reviews).textDecorationLine).toBe("underline");
		await expect(getComputedStyle(reviews).color).not.toBe(restColor);
	},
};

/**
 * Replaced feedback is no loss: it leaves the withheld count and stands apart in a neutral word.
 * Why feedback was withheld is the Feedback list's to say, so the tile gives the count alone.
 */
export const WithheldCountOnly: Story = {
	args: { state: readyOf(COURSE_DAY) },
	play: async ({ canvas }) => {
		const legend = within(canvas.getByRole("list", { name: "Feedback by delivery" }))
			.getAllByRole("listitem")
			.map((item) => item.textContent);
		await expect(legend).toContain("33 withheld");
		await expect(legend).toContain("20 replaced by newer");
		await expect(canvas.queryByText(/lost to timing/u)).not.toBeInTheDocument();
	},
};

/**
 * The period before is still on its way: each total stands, and the line its comparison will take is
 * held, so the tile does not move when it lands.
 */
export const ComparisonLoading: Story = {
	args: { previous: { status: "loading" } },
	play: async ({ canvas, canvasElement }) => {
		canvas.getByText("78");
		await expect(canvas.queryByText(/previous 30 days/u)).not.toBeInTheDocument();
		// One held line per tile.
		await expect(canvasElement.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
	},
};

/** The period before could not be read: the totals stand without a comparison, and nothing is held. */
export const WithoutComparison: Story = {
	args: {
		previous: {
			status: "error",
			error: { status: 503, detail: "The overview is unavailable." },
			onRetry: () => undefined,
		},
	},
	play: async ({ canvas, canvasElement }) => {
		canvas.getByText("78");
		await expect(canvas.queryByText(/previous 30 days/u)).not.toBeInTheDocument();
		await expect(canvasElement.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(0);
	},
};

/** Below the container's breakpoint the three tiles stack rather than squeezing. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("list", { name: "Stages" });
		await expectNoPageOverflow();
	},
};

/**
 * The range just changed and its counts are on their way: the previous range's stand in, drained of
 * colour and marked busy, rather than the region collapsing to skeletons. Nothing is set against
 * them — the period before belongs to the range just chosen — but its line is held for it.
 */
export const Stale: Story = {
	args: { state: { status: "ready", overview: practiceReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Stages" })).toHaveAttribute("aria-busy", "true");
		canvas.getByText("78");
		await expect(canvas.queryByText(/previous 30 days/u)).not.toBeInTheDocument();
	},
};

/** A quiet range: each tile is a nought with no chart and no legend. */
export const NothingReviewed: Story = {
	args: {
		state: { status: "ready", overview: quietPracticeReviewOverview, stale: false },
		previous: {
			status: "ready",
			period: { overview: quietPracticeReviewOverview },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Same as the previous 30 days")).toHaveLength(3);
		await expect(canvas.queryByRole("list", { name: /\bby\b/u })).not.toBeInTheDocument();
		canvas.getByText("Feedback");
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Stages" })).toHaveAttribute("aria-busy", "true");
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
