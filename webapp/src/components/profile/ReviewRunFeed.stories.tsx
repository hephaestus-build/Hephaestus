import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, waitFor } from "storybook/test";

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
		emptyTitle: "No reviews yet",
		emptyDescription: "Reviews appear here once your work has been reviewed.",
	},
} satisfies Meta<typeof ReviewRunFeed>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The runs the feed carries, newest first; nothing offers earlier ones when there are none. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Reviews" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View earlier reviews" })).toBeNull();
	},
};

/** Earlier runs exist, so the timeline continues past the last card. */
export const LoadMore: Story = {
	args: { feed: { ...readyFeed, hasMore: true } },
	play: async ({ canvas, userEvent }) => {
		const press = canvas.getByRole("button", { name: "View earlier reviews" });
		press.scrollIntoView();
		await waitFor(async () => expect(onLoadMore).toHaveBeenCalled());
		const before = onLoadMore.mock.calls.length;
		await userEvent.click(press);
		await expect(onLoadMore).toHaveBeenCalledTimes(before + 1);
	},
};

/** A run card's skeleton stands in for the next page. */
export const LoadingMore: Story = {
	args: { feed: { ...readyFeed, hasMore: true, isLoadingMore: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Loading…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(onLoadMore).not.toHaveBeenCalled();
	},
};

/** A failed page keeps the runs already read. */
export const LoadMoreFailed: Story = {
	args: { feed: { ...readyFeed, hasMore: true, loadMoreError: new Error("network") } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load earlier reviews.")).toBeVisible();
		await expect(onLoadMore).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

/** One block per run card the feed will show, in a region marked busy until they land. */
export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading reviews…").closest("[aria-busy]")).toHaveAttribute(
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
		await expect(canvas.queryByRole("list", { name: "Reviews" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "View earlier reviews" })).toBeNull();
	},
};

/** A narrowing hid every run: the way back out sits under the empty state. */
export const NarrowedToNothing: Story = {
	args: {
		feed: { ...readyFeed, runs: [] },
		emptyDescription: "No reviews mention Scope the change to one concern.",
		emptyAction: <button type="button">Show every review in this group</button>,
	},
};

/**
 * A narrowing emptied the pages read so far while earlier ones remain: the feed does not yet know
 * that nothing matches, so it says only what it has read and reads the earlier pages by itself.
 */
export const NarrowedToNothingSoFar: Story = {
	args: {
		feed: { ...readyFeed, hasMore: true },
		runs: [],
		emptyAction: <button type="button">Show every review in this group</button>,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("No reviews yet")).toBeNull();
		await expect(canvas.getByText("The latest reviews have no observations here.")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Show every review in this group" }),
		).toBeVisible();
		await waitFor(async () => expect(onLoadMore).toHaveBeenCalledOnce());
		await expect(canvas.getByRole("button", { name: "View earlier reviews" })).toBeVisible();
	},
};

/** The feed could not be read: the error says which feed, and its retry asks for it again. */
export const Failed: Story = {
	args: { feed: { status: "error", error: new Error("network"), onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load reviews")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.feed.status !== "error") {
			throw new Error("The story's feed is the failed one.");
		}
		await expect(args.feed.onRetry).toHaveBeenCalledOnce();
	},
};
