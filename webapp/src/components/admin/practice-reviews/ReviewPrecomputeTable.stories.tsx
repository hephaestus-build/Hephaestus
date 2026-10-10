import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { expectNoPageOverflow, horizontalScrollParentOf } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { reviewPrecompute } from "./fixtures";
import { ReviewPrecomputeTable } from "./ReviewPrecomputeTable";

/** The row of one practice's script, found by the practice that heads it. */
function scriptRow(table: HTMLElement, practice: string) {
	const row = within(table).getByRole("rowheader", { name: practice }).closest("tr");
	if (row === null) {
		throw new Error(`No row for ${practice}`);
	}
	return within(row);
}

/** The cell under a column header, in one script's row. */
function cellUnder(table: HTMLElement, row: ReturnType<typeof scriptRow>, column: string) {
	const index = within(table)
		.getAllByRole("columnheader")
		.findIndex((head) => head.textContent === column);
	const cell = [...row.getAllByRole("rowheader"), ...row.getAllByRole("cell")][index];
	if (cell === undefined) {
		throw new Error(`No cell under ${column}`);
	}
	return cell;
}

/** The section of the operations guide that a guide link opens. */
const guideSection = (link: HTMLElement) => new URL(link.getAttribute("href") ?? "").hash;

/**
 * What each practice's precompute script did before one review. The result is words, never a badge:
 * it is about the script, not about the developer's work.
 *
 * No cost and no runner token counts: spend is on AI usage, and the review's own model calls are on
 * the run card.
 */
const meta = {
	component: ReviewPrecomputeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		scripts: [
			reviewPrecompute.found,
			reviewPrecompute.callsNotRated,
			reviewPrecompute.limitReached,
			reviewPrecompute.skipped,
			reviewPrecompute.failed,
			reviewPrecompute.notFinished,
			reviewPrecompute.removedPractice,
		],
	},
	argTypes: { scripts: { control: false } },
} satisfies Meta<typeof ReviewPrecomputeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const EveryResult: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Practice", "Result", "Models", "Not rated", "Calls", "Time"]);
		// The counts line up on the right, under their headers.
		for (const column of ["Not rated", "Calls", "Time"]) {
			const head = within(table).getByRole("columnheader", { name: column });
			await expect(getComputedStyle(head).textAlign).toBe("right");
		}
		const results = [
			"Found 3 places",
			"Handed the review no places",
			"Did not run",
			"Failed",
			"Did not finish",
		];
		for (const result of results) {
			await expect(within(table).getByText(result)).toBeVisible();
		}
		// A script that ran out of time still handed the review what it found by then.
		await expect(within(table).getByText("Ran out of time, found 2 places")).toBeVisible();
		await expect(canvas.queryByText(/\$|USD/u)).not.toBeInTheDocument();
	},
};

export const Found: Story = {
	args: { scripts: [reviewPrecompute.found] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Thin controllers");
		await expect(row.getByText("Found 3 places")).toBeVisible();
		// The kind's name is visible, the tier's for a screen reader, beside its icon.
		await expect(row.getByText("Decision model")).toBeVisible();
		await expect(row.getByText("Cloud")).toHaveClass("sr-only");
		await expect(cellUnder(table, row, "Calls")).toHaveTextContent(/^12$/u);
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^4\.2 s$/u);
		// The practice names its row for a screen reader.
		await expect(row.getByRole("rowheader", { name: "Thin controllers" })).toHaveAttribute(
			"scope",
			"row",
		);
		await expect(levelsOpenedBy(row.getByRole("link", { name: "Thin controllers" }))).toStrictEqual(
			["practice:thin-controllers"],
		);
	},
};

/** A run of a minute or more reads in minutes, not in thousands of seconds. */
export const LongRun: Story = {
	args: { scripts: [{ ...reviewPrecompute.found, durationMs: 3_725_000 }] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Thin controllers");
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^62 min 5 s$/u);
	},
};

/**
 * The count is model calls, never places. The reasons sit under it, one per line, summed across the
 * script's models, in plain words. Only the unusable answer points at the model, so the result
 * offers that model, and the link names its practice for a screen reader.
 *
 * The script handed the review no places while calls went unrated, so the result never says that
 * it found none: the work may hold places that the unrated calls missed.
 */
export const CallsNotRated: Story = {
	args: { scripts: [reviewPrecompute.callsNotRated] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Errors carry their context");
		await expect(cellUnder(table, row, "Result")).toHaveTextContent(
			/^Handed the review no places/u,
		);
		await expect(row.queryByText(/found no places/iu)).not.toBeInTheDocument();
		const notRated = cellUnder(table, row, "Not rated");
		await expect(notRated).toHaveTextContent(/^6/u);
		await expect(
			within(notRated)
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toStrictEqual(["5 too slow", "1 wrong format"]);
		// The reasons line up under the count, on the right.
		await expect(getComputedStyle(within(notRated).getByRole("list")).textAlign).toBe("right");
		await expect(cellUnder(table, row, "Calls")).toHaveTextContent(/^11$/u);
		const check = row.getByRole("link", {
			name: "Check the decision model for Errors carry their context",
		});
		await expect(check).toHaveAttribute("href", "/w/demo/admin/models?purpose=PRACTICE_DECISION");
		// The fix sits in the result's own cell, under the result it fixes.
		await expect(check.closest("td")).toBe(cellUnder(table, row, "Result"));
	},
};

/**
 * A spent limit is nothing a model change fixes, and nothing on this page either: the result links
 * the guide that says who raises which limit.
 */
export const LimitReached: Story = {
	args: { scripts: [reviewPrecompute.limitReached] },
	play: async ({ canvas }) => {
		const row = scriptRow(canvas.getByRole("table"), "Small pull requests");
		await expect(row.getByText("3 limit reached")).toBeVisible();
		const guide = row.getByRole("link", { name: /^Why calls go unrated for Small pull requests/u });
		await expect(guideSection(guide)).toBe("#calls-that-were-not-rated");
		await expect(row.queryByRole("link", { name: /model/u })).not.toBeInTheDocument();
	},
};

/**
 * The review had no model the script required, so the script did not run, asked nothing and took no
 * time.
 */
export const SkippedWithLink: Story = {
	args: { scripts: [reviewPrecompute.skipped] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Product language");
		await expect(row.getByText("Did not run")).toBeVisible();
		await expect(row.getByText("No model")).toBeVisible();
		await expect(cellUnder(table, row, "Calls")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Time")).toHaveTextContent("—none");
		await expect(
			row.getByRole("link", { name: "Assign a decision model for Product language" }),
		).toHaveAttribute("href", "/w/demo/admin/models?purpose=PRACTICE_DECISION");
	},
};

/**
 * The first line of the script's error, so its author can fix it without a log. It sits under the
 * result and wraps there, so a narrow screen shows all of it.
 */
export const FailedWithError: Story = {
	args: { scripts: [reviewPrecompute.failed] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Tests name the behaviour");
		await expect(row.getByText("Failed")).toBeVisible();
		// A script that stopped at once still ran, so it does not read as 0.0 s.
		await expect(cellUnder(table, row, "Time")).toHaveTextContent(/^< 0\.1 s$/u);
		const error = row.getByText(/^TypeError: change\.files is not iterable/u);
		await expect(error).toBeVisible();
		await expect(error.closest("td")).toBe(cellUnder(table, row, "Result"));
		const edit = row.getByRole("link", { name: "Edit the script for Tests name the behaviour" });
		await expect(edit.getAttribute("href")).toMatch(/^\/w\/demo\/admin\/practices\?/u);
		await expect(levelsOpenedBy(edit)).toStrictEqual(["practice-edit:tests-name-the-behaviour"]);
	},
};

/**
 * The stage stopped before the script reported, so the run has no time to show. Nothing on this
 * page fixes that, so the result links the guide that says what to raise or speed up. The proxy
 * still counted the calls, but the run names no models: the row shows each kind that ran, with no
 * tier and no claim that none was assigned.
 */
export const NotFinished: Story = {
	args: { scripts: [reviewPrecompute.notFinished] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		const row = scriptRow(table, "Decisions are written down");
		await expect(row.getByText("Did not finish")).toBeVisible();
		await expect(cellUnder(table, row, "Time")).toHaveTextContent("—none");
		await expect(cellUnder(table, row, "Calls")).toHaveTextContent(/^9$/u);
		await expect(cellUnder(table, row, "Models")).toHaveTextContent(/^Decision model$/u);
		const guide = row.getByRole("link", {
			name: /^Why scripts stop early for Decisions are written down/u,
		});
		await expect(guideSection(guide)).toBe("#the-trace-line");
	},
};

/**
 * A practice deleted since the review keeps its row under its identifier. Its script ran out of
 * time, so the guide is still the way out.
 */
export const RemovedPractice: Story = {
	args: { scripts: [reviewPrecompute.removedPractice] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not a current practice")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "retired-practice" })).not.toBeInTheDocument();
		await expect(guideSection(canvas.getByRole("link", { name: /^Why scripts stop early/u }))).toBe(
			"#the-trace-line",
		);
	},
};

/**
 * The table scrolls inside its own frame, and the page does not. The practice and its result fit
 * the screen, so a failed script's error wraps inside the result's column and shows all of it
 * before any scrolling. The figures to the right scroll into view.
 */
export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table");
		await expect(table).toBeVisible();
		await expectNoPageOverflow();
		const error = within(table).getByText(/^TypeError:/u);
		const cell = error.closest("td");
		await expect(cell).not.toBeNull();
		await expect(error.getBoundingClientRect().right).toBeLessThanOrEqual(
			cell?.getBoundingClientRect().right ?? 0,
		);
		// The whole result column is inside the frame before it scrolls: nothing of the error is cut.
		const frame = horizontalScrollParentOf(table);
		await expect(frame.scrollLeft).toBe(0);
		await expect(cell?.getBoundingClientRect().right ?? Infinity).toBeLessThanOrEqual(
			frame.getBoundingClientRect().right,
		);
		// More than one line: it wraps rather than running out of its column.
		await expect(error.getBoundingClientRect().height).toBeGreaterThan(
			Number.parseFloat(getComputedStyle(error).lineHeight) * 1.5,
		);
	},
};
