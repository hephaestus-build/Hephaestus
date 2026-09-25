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

/** One block per run card the feed will show, in a region marked busy until they land. */
export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading review runs").closest("[aria-busy]")).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("status")).toBeNull();
	},
};

/**
 * Nothing has reached this surface and nothing earlier is left to read: the empty state, in the
 * surface's own words, instead of a rail with no runs on it.
 */
export const NoRuns: Story = {
	args: { feed: { ...readyFeed, runs: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list", { name: "Review runs" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "View earlier reviews" })).toBeNull();
	},
};

/** A narrowing hid every run: the way back out sits under the empty state. */
export const NarrowedToNothing: Story = {
	args: {
		feed: { ...readyFeed, runs: [] },
		emptyDescription: "No review runs mention Scope the change to one concern.",
		emptyAction: <button type="button">Show every review in this group</button>,
	},
};

/**
 * A narrowing emptied the pages read so far while earlier ones remain: the feed does not yet know
 * that nothing matches, so it says only what it has read and keeps the earlier pages a press away.
 */
export const NarrowedToNothingSoFar: Story = {
	args: {
		feed: { ...readyFeed, hasMore: true },
		runs: [],
		emptyAction: <button type="button">Show every review in this group</button>,
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByText("No review runs")).toBeNull();
		await expect(canvas.getByText("Nothing here in the latest reviews.")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Show every review in this group" }),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View earlier reviews" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

/** The feed could not be read: the error says which feed, and its retry asks for it again. */
export const Failed: Story = {
	args: { feed: { status: "error", error: new Error("network"), onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Could not load review runs")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.feed.status !== "error") {
			throw new Error("The story's feed is the failed one.");
		}
		await expect(args.feed.onRetry).toHaveBeenCalledOnce();
	},
};
