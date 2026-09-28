import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	practiceReviewOverview,
	quietPracticeReviewOverview,
	readyReviewOverview,
	workspacePractices,
} from "./fixtures";
import { PracticeCountsTable } from "./PracticeCountsTable";

/**
 * Each practice the reviews checked in the range, busiest first, as plain counts with what each is
 * out of — never a bar, and never a share without its whole. The whole row opens the practice's
 * level, whose counts open their rows; below the table's `@md` only the name and the total stay,
 * and the level carries the rest. Practices that could have been reviewed and recorded nothing are
 * one line at the end, pointing at Practice setup.
 */
const meta = {
	component: PracticeCountsTable,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { workspaceSlug: "demo", state: readyReviewOverview(), practices: workspacePractices },
	argTypes: { state: { control: false }, practices: { control: false } },
} satisfies Meta<typeof PracticeCountsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

function cellsOf(row: HTMLElement) {
	return within(row)
		.getAllByRole("cell")
		.slice(1)
		.map((cell) => cell.textContent);
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).not.toHaveAttribute("aria-busy");
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((header) => header.textContent),
		).toEqual([
			"Practice",
			"Observations",
			"Negative outcomes",
			"Marked incorrect",
			"Feedback delivered",
		]);
		// Only what an admin found wrong is marked, and the column must not read as if the rest were
		// confirmed correct.
		await expect(
			canvas.getByRole("columnheader", { name: "Marked incorrect" }),
		).toHaveAccessibleDescription(
			"Marked incorrect: Only observations an admin found wrong are marked; an unmarked one is not confirmed correct.",
		);
		const busiest = canvas.getByRole("row", { name: /Thin controllers/u });
		await expect(levelsOpenedBy(within(busiest).getByRole("link"))).toEqual([
			"practice:thin-controllers",
		]);
		await expect(within(busiest).getAllByRole("link")).toHaveLength(1);
		// 18 + 9 + 4 + 2 observations; 7 of 12 pieces of feedback citing it were delivered.
		await expect(cellsOf(busiest)).toEqual(["33", "9 of 33", "1 of 33", "7 of 12"]);
		// A practice that fired twice and led to no feedback still reads as a row, and says so.
		await expect(cellsOf(canvas.getByRole("row", { name: /Product language/u }))).toEqual([
			"2",
			"1 of 2",
			"0 of 2",
			"None",
		]);
		// "Decisions are written down" runs and recorded nothing: one line, and a way to where it is
		// set up. The count opens nothing it counts, so it is words rather than the link.
		canvas.getByText(/^1 practice recorded nothing in this range\./u);
		await expect(canvas.queryByRole("link", { name: /recorded nothing/u })).not.toBeInTheDocument();
		const setup = canvas.getByRole<HTMLAnchorElement>("link", { name: "Open Practice setup" });
		await expect(new URL(setup.href).pathname).toBe("/w/demo/admin/practices");
	},
};

/** A practice that is turned off is not expected to record anything, so it is not counted as quiet. */
export const EveryRunningPracticeRecorded: Story = {
	args: {
		practices: workspacePractices.map((practice) =>
			practice.slug === "decisions-are-written-down"
				? { ...practice, autonomy: { ...practice.autonomy, effective: "OFF" as const } }
				: practice,
		),
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/recorded nothing/u)).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("link", { name: "Open Practice setup" }),
		).not.toBeInTheDocument();
	},
};

/**
 * A practice withdrawn from automated review is never reviewed, whatever its autonomy says, so it is
 * not counted as quiet either.
 */
export const WithdrawnPracticeIsNotQuiet: Story = {
	args: {
		practices: workspacePractices.map((practice) =>
			practice.slug === "decisions-are-written-down"
				? {
						...practice,
						automatedReviewWithdrawal: {
							code: "DECISION_RECORD_NOT_CAPTURED",
							description: "Nothing Hephaestus collects shows where the decision was written down.",
						},
					}
				: practice,
		),
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/recorded nothing/u)).not.toBeInTheDocument();
	},
};

/**
 * The table keeps the name and the total, and fits the phone: a long practice name wraps, as does
 * the line counting quiet practices, rather than setting the table's width.
 */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		// Hidden, and so out of the accessibility tree: the practice level carries these.
		await expect(canvas.queryByRole("columnheader", { name: "Negative outcomes" })).toBeNull();
		canvas.getByRole("columnheader", { name: "Observations" });
		// The sentence is wider than the viewport, so it wraps: its link starts on a later line.
		const quiet = canvas.getByText(/^1 practice recorded nothing in this range\./u);
		const sentence = document.createRange();
		sentence.selectNodeContents(quiet);
		await expect(
			within(quiet).getByRole("link", { name: "Open Practice setup" }).getBoundingClientRect().top,
		).toBeGreaterThan(sentence.getClientRects()[0]?.bottom ?? Number.POSITIVE_INFINITY);
		const table = canvas.getByRole("table");
		await expect(table.scrollWidth).toBeLessThanOrEqual(table.parentElement?.clientWidth ?? 0);
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

/**
 * The previous range recorded nothing: that proves nothing about the range just chosen, so the rows
 * wait in their shape rather than claiming this range is empty.
 */
export const StaleNothingChecked: Story = {
	args: { state: { status: "ready", overview: quietPracticeReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
		await expect(
			canvas.queryByText("No practice was checked in this range"),
		).not.toBeInTheDocument();
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
	args: { state: { status: "loading" }, practices: undefined },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).toHaveAttribute("aria-busy", "true");
		canvas.getByRole("columnheader", { name: "Marked incorrect" });
		await expect(within(table).queryByRole("link")).not.toBeInTheDocument();
	},
};
