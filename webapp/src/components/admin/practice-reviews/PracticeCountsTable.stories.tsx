import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	practiceReviewOverview,
	quietPracticeReviewOverview,
	readyReviewOverview,
} from "./fixtures";
import { PracticeCountsTable } from "./PracticeCountsTable";

/**
 * Each practice the reviews checked in the range, busiest first. A row's counts are words, not
 * links: the whole row opens the practice's level, whose counts open their rows.
 */
const meta = {
	component: PracticeCountsTable,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { workspaceSlug: "demo", state: readyReviewOverview() },
	argTypes: { state: { control: false } },
} satisfies Meta<typeof PracticeCountsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

function cellsOf(row: HTMLElement) {
	return within(row)
		.getAllByRole("cell")
		.slice(2)
		.map((cell) => cell.textContent);
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).not.toHaveAttribute("aria-busy");
		const [, ...rows] = within(table).getAllByRole("row");
		// Busiest first, by observations, as the server ranks them; people are never ranked.
		await expect(rows.map((row) => within(row).getByRole("link").textContent)).toEqual([
			"Thin controllers",
			"Errors carry their context",
			"Tests name the behaviour",
			"The change explains itself",
			"Product language",
		]);
		const busiest = canvas.getByRole("row", { name: /Thin controllers/u });
		await expect(levelsOpenedBy(within(busiest).getByRole("link"))).toEqual([
			"practice:thin-controllers",
		]);
		await expect(
			within(busiest)
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toEqual(["18 strengths", "9 improvements", "4 not applicable", "2 undetermined"]);
		await expect(within(busiest).queryAllByRole("link")).toHaveLength(1);
		// Feedback that cited it, what of that was delivered, and what an admin marked incorrect.
		await expect(cellsOf(busiest)).toEqual(["12", "7", "1"]);
		// A practice that fired twice and led to nothing still reads as a row, with its zeroes.
		await expect(cellsOf(canvas.getByRole("row", { name: /Product language/u }))).toEqual([
			"0",
			"0",
			"0",
		]);
	},
};

/** The table keeps its columns and scrolls inside its own box, rather than dragging the page. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectTablesScrollInPlace();
		await expectNoPageOverflow();
	},
};

/** The previous range's rows stand in, drained of colour and marked busy, while the next loads. */
export const Stale: Story = {
	args: { state: { status: "ready", overview: practiceReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
		canvas.getByRole("link", { name: "Thin controllers" });
	},
};

export const NoPracticeChecked: Story = {
	args: { state: readyReviewOverview(quietPracticeReviewOverview) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice was checked in this range")).toBeVisible();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

/** The header stands and the rows are drawn in their shape, so nothing jumps when they arrive. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).toHaveAttribute("aria-busy", "true");
		canvas.getByRole("columnheader", { name: "Marked incorrect" });
		await expect(within(table).queryByRole("link")).not.toBeInTheDocument();
	},
};
