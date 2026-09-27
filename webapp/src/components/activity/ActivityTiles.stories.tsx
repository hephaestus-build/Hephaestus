import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import {
	OVERVIEW,
	QUIET_OVERVIEW,
	readyOverview,
	WEEK_OVERVIEW,
	YEAR_OVERVIEW,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityTiles } from "./ActivityTiles";

/**
 * Four numbers of four different units, so four tiles rather than one chart: 142 comments on the
 * same axis as 1 issue would flatten the issue to nothing. Each tile's bars are on its own scale
 * from zero — a shape to read against the tile's own number, never against the tile beside it.
 */
const meta = {
	component: ActivityTiles,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyOverview(OVERVIEW),
		providerType: "GITHUB",
	},
} satisfies Meta<typeof ActivityTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A reviewer's month: 1 issue, 3 merged, 18 reviews and 142 comments. */
export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		// Each tile charts its headline alone; the breakdown by kind is the category level's.
		const charts = canvasElement.querySelectorAll("[data-slot=chart]");
		await expect(charts).toHaveLength(4);
		for (const chart of charts) {
			await expect(chart.querySelectorAll(".recharts-bar")).toHaveLength(1);
		}
		// The title already says "Reviews"; only a state earns a word after the number.
		await expect(canvas.getByRole("link", { name: /^Pull requests\s*3\s*merged/u })).toBeVisible();
		const reviews = canvas.getByRole("link", { name: /^Reviews/u });
		await expect(reviews).toHaveAccessibleName(
			/^Reviews\s*18\s*11 approvals\s*,\s*3 reviews requesting changes\s*,\s*4 comment-only reviews$/u,
		);
		await expect(reviews).toHaveAccessibleDescription(
			/^18 reviews; busiest day \w+ \d+ \w+, \d+$/u,
		);
		await expect(reviews).toHaveAttribute("href", expect.stringContaining("activity%3Areviews"));
		await expect(canvas.getByRole("link", { name: /^Issues\s*1\s*opened/u })).toBeVisible();
	},
};

/** A first week: nothing opens, and no tile draws bars for nothing. */
export const Empty: Story = {
	args: { state: readyOverview(QUIET_OVERVIEW) },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
		await expect(canvasElement.querySelectorAll("[data-slot=chart]")).toHaveLength(0);
	},
};

/** Only some tiles have anything; the rest stay quiet and do not open. */
export const Sparse: Story = {
	args: { state: readyOverview(WEEK_OVERVIEW, "7d") },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual([
			expect.stringContaining("pull-requests"),
			expect.stringContaining("reviews"),
			expect.stringContaining("comments"),
		]);
	},
};

/** Twelve months, one bar per month. */
export const TwelveMonths: Story = {
	args: { state: readyOverview(YEAR_OVERVIEW, "1y") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Comments/u })).toHaveAccessibleDescription(
			/^1692 comments; busiest month \w+ \d{4}, \d+$/u,
		);
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Merge requests 3 merged/u })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** While another range loads, the previous range's tiles stay, dimmed and marked busy. */
export const Stale: Story = {
	args: {
		state: { status: "ready", overview: OVERVIEW, stale: true },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list")).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByRole("link", { name: /^Reviews/u })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list")).toHaveAttribute("aria-busy", "true");
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
