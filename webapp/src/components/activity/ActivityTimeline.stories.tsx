import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { TIMELINE, WORKSPACE_TIMELINE } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";
import { expectGenuinelyDisabled } from "@/test/controls";

import { ActivityTimeline } from "./ActivityTimeline";

const onLoadMore = fn();

const meta = {
	component: ActivityTimeline,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			items: TIMELINE,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
		},
		providerType: "GITHUB",
		people: "one",
		empty: {
			title: "Nothing in the last 7 days",
			description: "Your pull requests, reviews, issues and comments show up here.",
		},
	},
} satisfies Meta<typeof ActivityTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Six entries on five days, the first two of them today.
		await expect(canvas.getAllByRole("list")).toHaveLength(5);
		await expect(canvas.getByText("Today")).toBeVisible();
		await expect(canvas.getByText("Yesterday")).toBeVisible();
		// A review opens itself on the provider, not only the pull request it is on.
		await expect(
			canvas.getByRole("link", { name: /Show open work on the Activity page/u }),
		).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/pull/2310#pullrequestreview-9001",
		);
		// One person's timeline names nobody.
		await expect(canvas.queryAllByText(/ by /u)).toHaveLength(0);
		// Work deleted upstream keeps its entry, so the list still matches the count.
		await expect(canvas.getByText("A pull request that is no longer available")).toBeVisible();
	},
};

/** Several people: a merge is credited to the author it is counted for, never to whoever merged. */
export const SeveralPeople: Story = {
	args: {
		state: {
			status: "ready",
			items: WORKSPACE_TIMELINE,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore,
		},
		people: "several",
	},
	play: async ({ canvas }) => {
		const entries = canvas.getAllByRole("listitem").map((entry) => entry.textContent);
		await expect(entries).toStrictEqual(
			expect.arrayContaining([
				expect.stringMatching(/Merged.*opened by Ada Lovelace/u),
				expect.stringContaining("Changes requested by Bob Brenner"),
			]),
		);
		const [bob] = canvas.getAllByRole("link", { name: "Bob Brenner" });
		await expect(bob).toHaveAttribute("href", expect.stringContaining("member%3Abob"));
	},
};

export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("A merge request that is no longer available")).toBeVisible();
	},
};

export const OlderActivity: Story = {
	args: {
		state: { status: "ready", items: TIMELINE, hasMore: true, isLoadingMore: false, onLoadMore },
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show older activity" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

export const LoadingOlder: Story = {
	args: {
		state: { status: "ready", items: TIMELINE, hasMore: true, isLoadingMore: true, onLoadMore },
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Loading…" }));
	},
};

/** An older page that failed leaves what is loaded in place, says so, and asks again. */
export const LoadingOlderFailed: Story = {
	args: {
		state: {
			status: "ready",
			items: TIMELINE,
			hasMore: true,
			isLoadingMore: false,
			loadMoreError: new Error("Network down"),
			onLoadMore,
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("Couldn’t load older activity.");
		await expect(canvas.getByText("Today")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(onLoadMore).toHaveBeenCalledOnce();
	},
};

export const Empty: Story = {
	args: {
		state: { status: "ready", items: [], hasMore: false, isLoadingMore: false, onLoadMore },
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("listitem")).not.toBeInTheDocument();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
};
