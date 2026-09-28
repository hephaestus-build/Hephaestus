import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { PracticeReviewOverview } from "@/api/types.gen";
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

const FROM_DAY = reviewOverviewScope.from;
if (FROM_DAY === undefined) {
	throw new Error("The overview's scope starts on a day");
}

/** The thirty days before: fewer reviews, as many observations, more feedback. */
const PREVIOUS: PracticeReviewOverview = {
	...quietPracticeReviewOverview,
	reviews: { completed: 30, failed: 0, timedOut: 0, cancelled: 0, running: 0, queued: 0 },
	observations: { strengths: 40, problems: 30, notApplicable: 5, undetermined: 3 },
	feedback: { ...feedbackCounts([]), delivered: 30 },
};

const READY: OverviewRegionState = {
	status: "ready",
	overview: practiceReviewOverview,
	stale: false,
};

const PREVIOUS_READY: PreviousReviewPeriodState = {
	status: "ready",
	period: { overview: PREVIOUS, name: "the previous 30 days" },
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
		workspaceSlug: "demo",
		state: READY,
		previous: PREVIOUS_READY,
		scope: reviewOverviewScope,
	},
	argTypes: { state: { control: false }, previous: { control: false } },
} satisfies Meta<typeof ReviewPipeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const stages = canvas.getByRole("list", { name: "Stages" });
		await expect(stages).not.toHaveAttribute("aria-busy");
		// Each total is set against the period before it, without a verdict either way.
		canvas.getByText("8 more than the previous 30 days");
		canvas.getByText("Same as the previous 30 days");
		canvas.getByText("6 fewer than the previous 30 days");
		// Each stage's title opens its list over the range the tiles count.
		const reviews = new URL(
			within(stages).getByRole<HTMLAnchorElement>("link", { name: "Reviews" }).href,
		);
		await expect(reviews.pathname).toBe("/w/demo/admin/practices/reviews/runs");
		await expect(reviews.searchParams.get("from")).toBe(FROM_DAY);
		// Reviews still going have no outcome yet: they are a live line, not legend entries.
		const running = new URL(
			canvas.getByRole<HTMLAnchorElement>("link", { name: "1 running" }).href,
		);
		await expect(running.searchParams.get("status")).toBe('["RUNNING"]');
		canvas.getByRole("link", { name: "2 queued" });
		await expect(
			within(canvas.getByRole("list", { name: "Reviews by outcome" }))
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual(["31 completed", "2 failed", "1 timed out", "1 cancelled"]);
		await expect(
			within(canvas.getByRole("list", { name: "Feedback by delivery" }))
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual([
			"3 awaiting approval",
			"1 prepared",
			"14 delivered",
			"5 withheld",
			"1 failed to deliver",
		]);
		// The marked-incorrect count overlaps the observations' outcomes, so it is listed apart.
		within(canvas.getByRole("list", { name: "Observations checked by an admin" })).getByRole(
			"link",
			{ name: "3 marked incorrect" },
		);
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

/** A quiet range: each tile is a nought with no chart and no legend, and its list is still a link. */
export const NothingReviewed: Story = {
	args: {
		state: { status: "ready", overview: quietPracticeReviewOverview, stale: false },
		previous: {
			status: "ready",
			period: { overview: quietPracticeReviewOverview, name: "the previous 30 days" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Same as the previous 30 days")).toHaveLength(3);
		await expect(canvas.queryByRole("list", { name: /\bby\b/u })).not.toBeInTheDocument();
		canvas.getByRole("link", { name: "Feedback" });
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Stages" })).toHaveAttribute("aria-busy", "true");
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
