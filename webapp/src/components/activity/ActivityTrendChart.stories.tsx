import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	MONTH_OVERVIEW,
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
import { ActivityTrendChart } from "./ActivityTrendChart";

/**
 * A category's figures, then one small chart per kind on one shared scale, then the same numbers as
 * a table. Small multiples rather than one stacked or grouped chart: each kind keeps its own shape,
 * no two state colours touch, and a year of three kinds is never a hundred and fifty slivers.
 */
const meta = {
	component: ActivityTrendChart,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyOverview(MONTH_OVERVIEW, "30d", PREVIOUS_TALLY),
		category: "reviews",
		providerType: "GITHUB",
	},
} satisfies Meta<typeof ActivityTrendChart>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The figure a term names, as the summary row writes it. */
function figure(root: HTMLElement, term: string): string | null | undefined {
	const terms = [...root.querySelectorAll("dt")];
	return terms.find((dt) => dt.textContent === term)?.nextElementSibling?.textContent;
}

export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		// Each pull request reviewed counts once, however many verdicts it got.
		await expect(figure(canvasElement, "Reviewed")).toBe("12");
		await expect(figure(canvasElement, "Average per week")).toMatch(/^\d+\.\d$/u);
		await expect(figure(canvasElement, "vs the previous 30 days")).toBe("−3");
		const chart = canvas.getByRole("figure");
		await expect(chart).toHaveAccessibleName(/^12 pull requests reviewed\. Busiest week .+, \d+$/u);
		// One chart per kind, each headed by its name and total.
		const rows = within(chart).getAllByRole("listitem");
		await expect(rows.map((row) => row.firstElementChild?.textContent)).toStrictEqual([
			"Approved11",
			"Changes requested3",
			"Commented4",
		]);
		await expect(canvasElement.querySelectorAll("[data-slot=chart]")).toHaveLength(3);
	},
};

/** The table twin: every week's count per kind. */
export const AsTable: Story = {
	args: { state: readyOverview(SPARSE_OVERVIEW, "30d"), category: "pull-requests" },
	play: async ({ canvas, canvasElement, userEvent }) => {
		await expect(figure(canvasElement, "Busiest week")).toMatch(/^2/u);
		// A kind that did not happen keeps its row, and says so in it.
		await expect(canvas.getAllByText("None in this range")).toHaveLength(2);
		await userEvent.click(canvas.getByRole("button", { name: "Show as table" }));
		const table = canvas.getByRole("table", { name: "Pull requests by week" });
		const [header, ...rows] = within(table).getAllByRole("row");
		// Opened, merged and closed are steps of one lifecycle: they add up to no total.
		await expect(
			within(header ?? table)
				.getAllByRole("columnheader")
				.map((cell) => cell.textContent),
		).toStrictEqual(["Week", "Opened", "Merged", "Closed"]);
		await expect(rows).toHaveLength(weekStarts(spanOf("30d").from, spanOf("30d").to).length);
		// A zero stays in its cell, stepped back, so the counts that are there stand out.
		const [firstWeek] = rows;
		await expect(within(firstWeek ?? table).getAllByRole("cell")[2]).toHaveClass(
			"text-muted-foreground",
		);
		const merged = rows.map((row) => within(row).getAllByRole("cell")[2]?.textContent);
		await expect(merged.filter((count) => count !== "0")).toStrictEqual(["1", "2"]);
	},
};

/** Reviews count pull requests, not verdicts, so their table carries its own column for them. */
export const ReviewsAsTable: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show as table" }));
		const table = canvas.getByRole("table", { name: "Reviews by week" });
		await expect(within(table).getByRole("columnheader", { name: "Reviewed" })).toBeVisible();
	},
};

/** Two kinds of one neutral tone: each in its own row, in the accent, never grey. */
export const Comments: Story = { args: { category: "comments" } };

export const TwelveMonths: Story = {
	args: {
		state: readyOverview(YEAR_OVERVIEW, "1y"),
		category: "pull-requests",
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(figure(canvasElement, "Average per week")).toMatch(/^\d+\.\d$/u);
		// A year of weeks starts with its year, so September never reads twice.
		const [firstTick] = canvasElement.querySelectorAll(".recharts-xAxis-tick-labels text");
		await expect(firstTick?.textContent).toMatch(/^\d+ \w{3} \d{4}$/u);
		await expect(canvas.getByRole("figure")).toHaveAccessibleName(/Busiest week /u);
	},
};

export const Empty: Story = {
	args: { state: readyOverview(QUIET_OVERVIEW, "30d") },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByRole("figure")).toHaveAccessibleName("0 pull requests reviewed");
		await expect(figure(canvasElement, "Busiest week")).toBeUndefined();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB", category: "pull-requests" },
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("figure")).not.toBeInTheDocument();
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
