import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	OVERVIEW,
	PREVIOUS_TALLY,
	QUIET_OVERVIEW,
	readyOverview,
	spanOf,
	SPARSE_OVERVIEW,
	YEAR_OVERVIEW,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { weekStarts } from "./activity-tally";
import { ActivityTiles } from "./ActivityTiles";

/**
 * Four numbers of four different units, so four stat tiles rather than one chart: 142 comments on
 * the same axis as 1 issue would flatten the issue to nothing. Each tile is a value, a change on the
 * period before and its weekly bars over the range — on the tile's own scale from zero, each bar in
 * a faint track so an empty week reads as zero, with only the peak's value written on it. A shape to
 * read against the tile's own number, never against the tile beside it.
 */
const meta = {
	component: ActivityTiles,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyOverview(OVERVIEW, "30d", PREVIOUS_TALLY),
		providerType: "GITHUB",
	},
} satisfies Meta<typeof ActivityTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

/** How many columns a tile's chart draws: one per week, each with its track, empty or not. */
function columns(tile: HTMLElement): number {
	const lefts = [...tile.querySelectorAll("[data-slot=chart] .recharts-rectangle")].map((bar) =>
		Math.round(bar.getBoundingClientRect().left),
	);
	return new Set(lefts).size;
}

/** The peak labels a tile writes on its bars: the extreme only, never one per bar. */
function peakLabels(tile: HTMLElement): string[] {
	return [...tile.querySelectorAll(".recharts-label-list text")].map((label) => label.textContent);
}

/** The weeks a 30-day range touches, which every tile draws a column for. */
const MONTH_WEEKS = weekStarts(spanOf("30d").from, spanOf("30d").to).length;

/**
 * A reviewer's month: 1 issue, 3 merged, 12 pull requests reviewed and 142 comments, set against
 * the month before.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const pullRequests = canvas.getByRole("link", { name: /^Pull requests/u });
		await expect(within(pullRequests).getByText("2 more than the previous 30 days")).toBeVisible();
		await expect(pullRequests).toHaveAccessibleDescription(
			/^3 pull requests merged\. Busiest week .+\. 2 more than the previous 30 days$/u,
		);
		const reviews = canvas.getByRole("link", { name: /^Reviews/u });
		await expect(within(reviews).getByText("3 fewer than the previous 30 days")).toBeVisible();
		await expect(reviews).toHaveAccessibleName(
			/^Reviews\s*12\s*reviewed\s*3 fewer than the previous 30 days\s*11 approvals\s*,\s*3 reviews requesting changes\s*,\s*4 comment-only reviews$/u,
		);
		// The time frame under the bars ends today.
		await expect(within(reviews).getByText("Today")).toBeVisible();
		for (const tile of canvas.getAllByRole("link")) {
			await expect(peakLabels(tile)).toHaveLength(1);
			await expect(columns(tile)).toBe(MONTH_WEEKS);
		}
		await expect(canvas.getByRole("link", { name: /^Issues\s*1\s*opened/u })).toBeVisible();
	},
};

/** A quiet month: three merges in two weeks, each week a track, the busier week labelled. */
export const Sparse: Story = {
	args: { state: readyOverview(SPARSE_OVERVIEW) },
	play: async ({ canvas }) => {
		const pullRequests = canvas.getByRole("link", { name: /^Pull requests\s*3\s*merged/u });
		await expect(peakLabels(pullRequests)).toStrictEqual(["2"]);
		await expect(columns(pullRequests)).toBe(MONTH_WEEKS);
		// With no month before to set it against, the tile makes no comparison.
		await expect(within(pullRequests).queryByText(/previous 30 days/u)).not.toBeInTheDocument();
		// Nothing else happened: those tiles stay quiet and do not open.
		await expect(canvas.getAllByRole("link")).toHaveLength(2);
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

/** Twelve months, one column per week they touch. */
export const TwelveMonths: Story = {
	args: { state: readyOverview(YEAR_OVERVIEW, "1y") },
	play: async ({ canvas }) => {
		const comments = canvas.getByRole("link", { name: /^Comments/u });
		await expect(comments).toHaveAccessibleDescription(/^1692 comments\. Busiest week .+, \d+$/u);
		await expect(columns(comments)).toBe(weekStarts(spanOf("1y").from, spanOf("1y").to).length);
		// The frame starts with its year and, like every preset, ends now.
		await expect(within(comments).getByText(/^\d+ \w{3} \d{4}$/u)).toBeVisible();
		await expect(within(comments).getByText("Today")).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Merge requests\s*3\s*merged/u })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const DarkGitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** While another range loads, the previous range's tiles stay, drained of colour and marked busy. */
export const Stale: Story = {
	args: { state: { status: "ready", overview: OVERVIEW, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list")).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByRole("link", { name: /^Reviews/u })).toBeVisible();
		// A stale figure is set against nothing.
		await expect(canvas.queryByText(/previous 30 days/u)).not.toBeInTheDocument();
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
