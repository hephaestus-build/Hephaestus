import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	OVERVIEW,
	PREVIOUS_SUMMARY,
	QUIET_OVERVIEW,
	readyOverview,
	SPARSE_OVERVIEW,
	YEAR_OVERVIEW,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityTrendChart } from "./ActivityTrendChart";

/**
 * A category's figures, then one small chart per kind on one shared scale, then the same numbers as
 * a table. Small multiples rather than one stacked or grouped chart: each kind keeps its own shape,
 * no two state colours touch, and thirty days of three kinds is never ninety slivers.
 */
const meta = {
	component: ActivityTrendChart,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyOverview(OVERVIEW, "30d", PREVIOUS_SUMMARY),
		category: "reviews",
		range: "30d",
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
		await expect(figure(canvasElement, "Total")).toBe("18");
		await expect(figure(canvasElement, "Average per day")).toBe("0.6");
		await expect(figure(canvasElement, "vs the previous 30 days")).toBe("−3");
		const chart = canvas.getByRole("figure");
		await expect(chart).toHaveAccessibleName(/^18 reviews; busiest day \w+ \d+ \w+, \d+$/u);
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

/** The table twin: every bucket's count per kind, and — since reviews partition — their total. */
export const AsTable: Story = {
	args: { state: readyOverview(SPARSE_OVERVIEW), category: "pull-requests" },
	play: async ({ canvas, canvasElement, userEvent }) => {
		await expect(figure(canvasElement, "Busiest day")).toMatch(/^2/u);
		// A kind that did not happen keeps its row, and says so in it.
		await expect(canvas.getAllByText("None in the last 30 days")).toHaveLength(2);
		await userEvent.click(canvas.getByRole("button", { name: "Show as table" }));
		const table = canvas.getByRole("table", { name: "Pull requests by day" });
		const [header, ...rows] = within(table).getAllByRole("row");
		// Opened, merged and closed are steps of one lifecycle: they add up to no total.
		await expect(
			within(header ?? table)
				.getAllByRole("columnheader")
				.map((cell) => cell.textContent),
		).toStrictEqual(["Day", "Opened", "Merged", "Closed"]);
		await expect(rows).toHaveLength(30);
		// A zero stays in its cell, stepped back, so the counts that are there stand out.
		const [firstDay] = rows;
		await expect(within(firstDay ?? table).getAllByRole("cell")[2]).toHaveClass(
			"text-muted-foreground",
		);
		const merged = rows.map((row) => within(row).getAllByRole("cell")[2]?.textContent);
		await expect(merged.filter((count) => count !== "0")).toStrictEqual(["1", "2"]);
	},
};

/** Reviews partition: their table carries a total column. */
export const ReviewsAsTable: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show as table" }));
		const table = canvas.getByRole("table", { name: "Reviews by day" });
		await expect(within(table).getByRole("columnheader", { name: "Total" })).toBeVisible();
	},
};

/** Two kinds of one neutral tone: each in its own row, in the accent, never grey. */
export const Comments: Story = { args: { category: "comments" } };

export const TwelveMonths: Story = {
	args: {
		state: readyOverview(YEAR_OVERVIEW, "1y"),
		category: "pull-requests",
		range: "1y",
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(figure(canvasElement, "Average per month")).toMatch(/^\d+\.\d$/u);
		// A year of months starts with its year, so September never reads twice.
		const [firstTick] = canvasElement.querySelectorAll(".recharts-xAxis-tick-labels text");
		await expect(firstTick?.textContent).toMatch(/^\w{3} \d{4}$/u);
		await expect(canvas.getByRole("figure")).toHaveAccessibleName(/busiest month \w+ \d{4}/u);
	},
};

export const Empty: Story = {
	args: { state: readyOverview(QUIET_OVERVIEW) },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByRole("figure")).toHaveAccessibleName("0 reviews");
		await expect(figure(canvasElement, "Busiest day")).toBeUndefined();
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
