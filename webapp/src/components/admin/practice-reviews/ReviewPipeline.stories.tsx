import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import {
	practiceReviewOverview,
	quietPracticeReviewOverview,
	readyReviewOverview,
	reviewOverviewScope,
} from "./fixtures";
import { ReviewPipeline } from "./ReviewPipeline";

const FROM_DAY = reviewOverviewScope.from;
if (FROM_DAY === undefined) {
	throw new Error("The overview's scope starts on a day");
}

/**
 * What the reviews did over the range, stage by stage. The sentence is what a screen reader hears in
 * place of the bars, so it carries every total the tiles draw; the bars themselves are hidden.
 */
const meta = {
	component: ReviewPipeline,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		state: readyReviewOverview(),
		scope: reviewOverviewScope,
	},
	argTypes: { state: { control: false } },
} satisfies Meta<typeof ReviewPipeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"In this range: 38 reviews, 78 observations and 24 pieces of feedback, of which 14 delivered and 3 awaiting approval.",
			),
		).toBeVisible();
		const stages = canvas.getByRole("list", { name: "Stages" });
		await expect(stages).not.toHaveAttribute("aria-busy");
		// Each stage's title opens its list over the range the tiles count.
		const reviews = new URL(
			within(stages).getByRole<HTMLAnchorElement>("link", { name: "Reviews" }).href,
		);
		await expect(reviews.pathname).toBe("/w/demo/admin/practices/reviews/runs");
		await expect(reviews.searchParams.get("from")).toBe(FROM_DAY);
		canvas.getByRole("list", { name: "Reviews by outcome" });
		canvas.getByRole("list", { name: "Feedback by outcome" });
		// The marked-incorrect count overlaps the observations' outcomes, so it sits in their legend.
		within(canvas.getByRole("list", { name: "Observations by outcome" })).getByRole("link", {
			name: "3 marked incorrect",
		});
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
 * colour and marked busy, rather than the region collapsing to skeletons under the reader.
 */
export const Stale: Story = {
	args: { state: { status: "ready", overview: practiceReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Stages" })).toHaveAttribute("aria-busy", "true");
		canvas.getByText(/^In this range: 38 reviews/u);
	},
};

/** A quiet range says so in one line, and each tile is a nought with no chart and no legend. */
export const NothingReviewed: Story = {
	args: { state: readyReviewOverview(quietPracticeReviewOverview) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing was reviewed in this range.")).toBeVisible();
		await expect(canvas.queryByRole("list", { name: /by outcome/u })).not.toBeInTheDocument();
		// The stage links stay: a quiet range is still a list to open.
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
