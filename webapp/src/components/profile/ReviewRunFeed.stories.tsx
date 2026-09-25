import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { detailRuns } from "@/stories/practice-detail-story-mock-data";

import type { ReviewRunFeedState } from "./review-runs";
import { ReviewRunFeed } from "./ReviewRunFeed";

const onLoadMore = fn();

const readyFeed = {
	status: "ready",
	runs: detailRuns,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore,
} satisfies ReviewRunFeedState;

const meta = {
	component: ReviewRunFeed,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		feed: readyFeed,
		skeletonRows: 3,
		emptyTitle: "No review runs",
		emptyDescription: "Review runs appear here once your work has been reviewed.",
	},
} satisfies Meta<typeof ReviewRunFeed>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The runs the feed carries, newest first; nothing offers earlier ones when there are none. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Review runs" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View earlier reviews" })).toBeNull();
	},
};

/** Earlier runs exist: the feed offers them, and asks for them once. */
export const LoadMore: Story = {
	args: { feed: { ...readyFeed, hasMore: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "View earlier reviews" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

/** While the earlier runs are on their way the button says so and takes no second press. */
export const LoadingMore: Story = {
	args: { feed: { ...readyFeed, hasMore: true, isLoadingMore: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
	},
};

/** One block per run card the feed will show, announced while the reader waits. */
export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Loading review runs");
	},
};

/** Nothing has reached this surface yet: the feed says so in the surface's own words. */
export const NoRuns: Story = {
	args: { feed: { ...readyFeed, runs: [] } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Review runs appear here once your work has been reviewed."),
		).toBeVisible();
	},
};

/** A narrowing hid every run: the way back out sits under the empty state. */
export const NarrowedToNothing: Story = {
	args: {
		feed: { ...readyFeed, runs: [] },
		emptyDescription: "No review runs mention Scope the change to one concern.",
		emptyAction: <button type="button">Show every review in this group</button>,
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Show every review in this group" }),
		).toBeVisible();
	},
};

/** The feed could not be read: the error says which feed, and offers the retry. */
export const Failed: Story = {
	args: { feed: { status: "error", error: new Error("network"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Could not load review runs")).toBeVisible();
	},
};
