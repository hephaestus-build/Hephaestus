import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { detailRuns } from "@/stories/practice-detail-story-mock-data";

import type { ReviewRunFeedState } from "./review-runs";
import { ReviewedWorkFeedbackPage } from "./ReviewedWorkFeedbackPage";

const readyFeed = {
	status: "ready",
	runs: detailRuns,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewRunFeedState;

/**
 * The page a comment on a pull request, merge request or issue links to: the reader's own reviews of
 * that work, where each observation can be answered or disputed.
 */
const meta = {
	component: ReviewedWorkFeedbackPage,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { feed: readyFeed, observations: { onRespond: fn() } },
} satisfies Meta<typeof ReviewedWorkFeedbackPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The reader's reviews of the work, with what a dispute does said before they write one. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Your feedback on this work" }),
		).toBeVisible();
		await expect(canvas.getByText(/Workspace admins read a dispute’s explanation/u)).toBeVisible();
		await expect(canvas.getByRole("list", { name: "Reviews" })).toBeVisible();
	},
};

/** Somebody the comment was not about follows its link: nothing of anybody else's is shown. */
export const NotAboutTheReader: Story = {
	args: { feed: { ...readyFeed, runs: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing here is about your work")).toBeVisible();
		await expect(canvas.queryByRole("list", { name: "Reviews" })).toBeNull();
	},
};

export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async ({ canvas }) => {
		canvas.getByText("Loading reviews…");
		await expect(canvas.queryByRole("list", { name: "Reviews" })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		feed: {
			status: "error",
			error: { status: 500, detail: "The review history is temporarily unavailable." },
			onRetry: fn(),
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load reviews")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.feed.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.feed.onRetry).toHaveBeenCalledOnce();
	},
};
