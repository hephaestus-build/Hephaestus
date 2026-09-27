import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import {
	ada,
	GITLAB_WORK_LOG,
	LONG_WORK_LOG,
	WORK_LOG,
	WORKSPACE_WORK_LOG,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityWorkLog } from "./ActivityWorkLog";

const onLoadMore = fn();
const onCopy = fn(async () => {
	/* the copy is the route's */
});

/**
 * One row per pull request or issue, not per event: forty comments on one pull request are one row
 * with "40". A lifecycle event reads by its label, a comment by its count.
 */
const meta = {
	component: ActivityWorkLog,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			stale: false,
			items: WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
		providerType: "GITHUB",
		subject: { people: "one", login: ada.login },
	},
} satisfies Meta<typeof ActivityWorkLog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const [today] = canvas.getAllByRole("list");
		await expect(today).toHaveAccessibleName("Today");
		// Two reviews and forty comments on Chen's pull request are one row.
		await expect(canvas.getByRole("img", { name: "40 comments on code" })).toBeVisible();
		await expect(
			canvas.getByText("Changes requested", { selector: "[aria-hidden=true]" }),
		).toBeVisible();
		// The work the provider no longer has keeps its row, named and drawn by what it was.
		await expect(canvas.getByText("A pull request that is no longer available")).toBeVisible();
		await expect(canvas.getByRole("img", { name: "No longer available" })).toBeVisible();
		// A comment-only review reads "Commented" beside the eye, and is named for what it was.
		await expect(canvas.getByRole("img", { name: "Commented" })).toBeVisible();
		// Ada's own work names nobody; Chen's names Chen.
		await expect(canvas.getAllByText("by Chen Wei")).toHaveLength(2);
		await expect(canvas.queryByText("Ada Lovelace")).not.toBeInTheDocument();
	},
};

/** Several people's timeline: each row shows who did it. */
export const Workspace: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: WORKSPACE_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
		subject: { people: "several" },
	},
	play: async ({ canvas }) => {
		const people = canvas.getAllByRole("list", { name: "People" });
		await expect(people[2]).toHaveTextContent("+2");
	},
};

export const LongTitles: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: LONG_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "1000 comments on code" })).toBeVisible();
	},
};

export const MoreToLoad: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: WORK_LOG,
			hasMore: true,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show more" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

/** While another range loads, the previous range's rows stay, dimmed and marked busy. */
export const Stale: Story = {
	args: {
		state: {
			status: "ready",
			stale: true,
			items: WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		const [today] = canvas.getAllByRole("list");
		await expect(today?.closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
	},
};

export const LoadingMore: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: WORK_LOG,
			hasMore: true,
			isLoadingMore: true,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
	},
};

export const LoadMoreFailed: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: WORK_LOG,
			hasMore: true,
			isLoadingMore: false,
			loadMoreError: new Error("Network down"),
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("Couldn't load more activity.");
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeEnabled();
	},
};

export const Empty: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: [],
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No activity in this range")).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
		state: {
			status: "ready",
			stale: false,
			items: GITLAB_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("A merge request that is no longer available")).toBeVisible();
		await expect(canvas.getAllByRole("img", { name: "Merged merge request" })).toHaveLength(2);
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			items: LONG_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
			onCopy,
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
