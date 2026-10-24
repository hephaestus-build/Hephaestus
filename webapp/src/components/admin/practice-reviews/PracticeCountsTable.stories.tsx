import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewOverview } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	feedbackCounts,
	practiceReviewOverview,
	quietPracticeReviewOverview,
	readyReviewOverview,
	workspacePractices,
} from "./fixtures";
import { PracticeCountsTable } from "./PracticeCountsTable";

/**
 * Each practice the reviews checked in the range, its counts plain, never a share or a bar. Each
 * column's (i) says how its count is reached. The practices that need a look lead by default, each
 * with one marker; the rest follow busiest first. The whole row opens the practice's
 * sheet; it has no Track or Trace. It is drawn as the Reviews table, without the days: the same
 * head, row height and padding, counts aligned on the right.
 */
const meta = {
	component: PracticeCountsTable,
	parameters: { layout: "padded" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyReviewOverview(),
		practices: workspacePractices,
	},
	argTypes: { state: { control: false }, practices: { control: false } },
} satisfies Meta<typeof PracticeCountsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

const TABLE = { name: "Practices, the ones that need a look first" } as const;

/** The x of an element's middle, so a centred head and its figures can be compared. */
function middle(rect: DOMRect | undefined) {
	return rect ? rect.left + rect.width / 2 : 0;
}

function cellsOf(row: HTMLElement) {
	return within(row)
		.getAllByRole("cell")
		.slice(1)
		.map((cell) => cell.textContent);
}

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(table).not.toHaveAttribute("aria-busy");
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((header) => header.textContent.trim()),
		).toEqual([
			"Practice",
			"Observations",
			"Not met",
			"Does not apply",
			"Could not determine",
			"Marked incorrect",
			"Feedback delivered",
			"Open",
		]);
		// Each head takes at most two lines, centred like its figures, and all heads share one
		// height, so the row of heads stays level.
		const heads = within(table).getAllByRole("columnheader").slice(1, 7);
		for (const header of heads) {
			await expect(getComputedStyle(header).textAlign).toBe("center");
		}
		const headHeights = new Set(
			heads.map((head) => Math.round(head.getBoundingClientRect().height)),
		);
		await expect(headHeights.size).toBe(1);
		const notMetHead = within(table).getByRole("columnheader", { name: "Not met" });
		const notMetCell = within(canvas.getByRole("row", { name: /Thin controllers/u })).getAllByRole(
			"cell",
		)[2];
		await expect(
			Math.abs(
				middle(notMetHead.getBoundingClientRect()) - middle(notMetCell?.getBoundingClientRect()),
			),
		).toBeLessThan(1);
		// Every row keeps one height, whatever its figures.
		const rowHeights = new Set(
			within(table)
				.getAllByRole("row")
				.slice(1)
				.map((row) => Math.round(row.getBoundingClientRect().height)),
		);
		await expect(rowHeights.size).toBe(1);
		// Each count's head explains it in an (i) that opens on keyboard focus: only what an admin
		// found wrong is marked, and the column must not read as if the rest were confirmed correct.
		await expect(canvas.queryByText(/^Marked incorrect:/u)).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "How Marked incorrect is counted" }));
		await expect(
			await screen.findByRole("dialog", { name: "How Marked incorrect is counted" }),
		).toHaveTextContent("An unmarked one is not confirmed correct.");
		await userEvent.keyboard("{Escape}");
		const busiest = canvas.getByRole("row", { name: /Thin controllers/u });
		// The row opens the practice's sheet; the overview table has no Track or Trace.
		await expect(
			levelsOpenedBy(within(busiest).getByRole("link", { name: "Open practice Thin controllers" })),
		).toEqual(["practice:thin-controllers"]);
		await expect(
			within(busiest).queryByRole("button", { name: /^Track/u }),
		).not.toBeInTheDocument();
		await expect(within(busiest).queryByRole("link", { name: /^Trace/u })).not.toBeInTheDocument();
		await expect(cellsOf(busiest).slice(0, 6)).toEqual([
			"33",
			"9 of 33",
			"4 of 33",
			"2 of 33",
			"1 of 33",
			"7 of 12",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "How Observations is counted" }));
		await expect(
			await screen.findByRole("dialog", { name: "How Observations is counted" }),
		).not.toHaveTextContent("percentage");
		await userEvent.keyboard("{Escape}");
		// A practice that fired twice and led to no feedback still reads as a row, its feedback a
		// plain 0, with nothing composed to be out of.
		await expect(
			cellsOf(canvas.getByRole("row", { name: /Product language/u })).slice(0, 6),
		).toEqual(["2", "1 of 2", "1 of 2", "0 of 2", "0 of 2", "0"]);
		// The last row holds the figures of the tiles above. Feedback delivered counts each piece
		// once, so where a piece about two practices counts in both rows, the line under the table
		// says so.
		await expect(cellsOf(canvas.getByRole("row", { name: /^All practices/u })).slice(0, 6)).toEqual(
			["78", "27 of 78", "10 of 78", "6 of 78", "3 of 78", "14 of 24"],
		);
		await expect(
			canvas.getByText(/^Feedback delivered adds up to 16 across the practices, but 14 pieces/u),
		).toBeVisible();
		await expect(
			within(table)
				.getAllByRole("row")
				.slice(1, 6)
				.map((row) => row.querySelector("td .font-semibold")?.textContent),
		).toEqual([
			"Errors carry their context",
			"Thin controllers",
			"The change explains itself",
			"Tests name the behaviour",
			"Product language",
		]);
		await expect(canvas.queryByText("Often could not determine")).not.toBeInTheDocument();
		// A practice that recorded nothing is not a row, and no line under the table counts it.
		await expect(canvas.queryByText(/recorded nothing/u)).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("link", { name: "Open Practice setup" }),
		).not.toBeInTheDocument();
	},
};

/** The busiest first, then two practices that a course would want to look at. */
const MARKED_PRACTICES: ReviewOverview = {
	...practiceReviewOverview,
	practices: [
		...practiceReviewOverview.practices,
		{
			feedback: feedbackCounts([]),
			group: null,
			practiceSlug: "wiki-docs",
			practiceName: "Wiki pages are kept up to date",
			results: { met: 0, notMet: 0, doesNotApply: 29, couldNotDetermine: 1 },
			resultsMarkedIncorrect: 0,
		},
		{
			feedback: feedbackCounts([]),
			group: null,
			practiceSlug: "resolve-open-threads",
			practiceName: "Open threads are resolved",
			results: { met: 8, notMet: 4, doesNotApply: 1, couldNotDetermine: 9 },
			resultsMarkedIncorrect: 0,
		},
	],
};

export const NeedsALookFirst: Story = {
	args: {
		state: readyReviewOverview(MARKED_PRACTICES),
		// Both run in the workspace, so neither is hidden as turned off.
		practices: workspacePractices.flatMap((practice, index) =>
			index === 0
				? [
						practice,
						{ ...practice, slug: "wiki-docs" },
						{ ...practice, slug: "resolve-open-threads" },
					]
				: [practice],
		),
	},
	play: async ({ canvas, userEvent }) => {
		const names = () =>
			canvas
				.getAllByRole("row")
				.slice(1, 4)
				.map((row) => row.querySelector("td .font-semibold")?.textContent);
		await expect(names()).toEqual([
			"Wiki pages are kept up to date",
			"Open threads are resolved",
			"Errors carry their context",
		]);
		const wiki = canvas.getByRole("row", { name: /Wiki pages/u });
		await expect(
			within(wiki).getByText("Mostly does not apply").closest("[data-marker]"),
		).toHaveAttribute(
			"title",
			"Does not apply in 29 of 30 observations. Consider turning it off for this workspace.",
		);
		const threads = canvas.getByRole("row", { name: /Open threads/u });
		await expect(
			within(threads).getByText("Often could not determine").closest("[data-marker]"),
		).toHaveAttribute(
			"title",
			"Could not determine in 9 of 22 observations. The review may lack the evidence to decide.",
		);

		const sort = canvas.getByRole("combobox", { name: "Sort" });
		await userEvent.click(sort);
		// The list opens below the select and ends where it ends, so it neither covers the select nor
		// runs past the page's edge.
		const list = await screen.findByRole("listbox", { name: "Sort" });
		const trigger = sort.getBoundingClientRect();
		// Measured once its opening animation, which scales it from 95%, has finished.
		await waitFor(async () => {
			const popup = list.getBoundingClientRect();
			await expect(popup.top).toBeGreaterThanOrEqual(trigger.bottom);
			await expect(Math.abs(popup.right - trigger.right)).toBeLessThan(1);
		});
		await userEvent.click(await screen.findByRole("option", { name: "Busiest first" }));
		canvas.getByRole("table", { name: "Practices, busiest first" });
		await expect(sort).toHaveTextContent("Busiest first");
		await expect(names()).toEqual([
			"Thin controllers",
			"Errors carry their context",
			"Tests name the behaviour",
		]);

		// The keyboard reaches the same choice: open, move up, take it.
		sort.focus();
		await userEvent.keyboard("{ArrowDown}");
		await screen.findByRole("listbox", { name: "Sort" });
		await userEvent.keyboard("{ArrowUp}{Enter}");
		await expect(await canvas.findByRole("table", TABLE)).toBeVisible();
		await expect(sort).toHaveTextContent("Needs a look first");
		await expect(names()).toEqual([
			"Wiki pages are kept up to date",
			"Open threads are resolved",
			"Errors carry their context",
		]);
	},
};

/**
 * A long name and a marker do not make a row taller: every row keeps one line, the name truncates
 * with the whole of it on hover, and the marker stays beside it.
 */
export const LongNamesAndMarkers: Story = {
	args: {
		state: readyReviewOverview({
			...MARKED_PRACTICES,
			practices: MARKED_PRACTICES.practices.map((practice) =>
				practice.practiceSlug === "wiki-docs"
					? {
							...practice,
							practiceName:
								"Wiki pages and the architecture decision records are kept up to date with every change to the code",
						}
					: practice,
			),
		}),
		practices: NeedsALookFirst.args?.practices,
	},
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const heights = new Set(rows.map((row) => Math.round(row.getBoundingClientRect().height)));
		await expect(heights.size).toBe(1);
		const name = canvas.getByText(/^Wiki pages and the architecture/u);
		await expect(name).toHaveAttribute("title", expect.stringContaining("with every change"));
		await expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
		// The marker sits on the name's line, not under it.
		const marker = canvas.getByText("Mostly does not apply").closest("[data-marker]");
		await expect(marker?.parentElement).toBe(name.parentElement);
	},
};

export const TurnedOffPracticeHidden: Story = {
	args: {
		practices: workspacePractices.map((practice) =>
			practice.slug === "thin-controllers"
				? { ...practice, feedbackMode: { ...practice.feedbackMode, effective: "OFF" } }
				: practice,
		),
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByRole("row", { name: /Thin controllers/u })).toBeNull();
		const show = canvas.getByRole("switch", { name: /^Show turned-off practices/u });
		await expect(show).toHaveAccessibleName("Show turned-off practices (1 hidden)");
		await expect(show).not.toBeChecked();
		await userEvent.click(show);
		canvas.getByRole("row", { name: /Thin controllers/u });
		await expect(show).toHaveAccessibleName("Show turned-off practices");
	},
};

/** A practice that is no longer in the workspace is turned off too, and hidden the same way. */
export const RemovedPracticeHidden: Story = {
	args: {
		practices: workspacePractices.filter((practice) => practice.slug !== "product-language"),
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("row", { name: /Product language/u })).toBeNull();
		canvas.getByRole("switch", { name: "Show turned-off practices (1 hidden)" });
	},
};

/** While the practices load, the table hides nothing: it cannot know what is turned off yet. */
export const PracticesStillLoading: Story = {
	args: { practices: undefined },
	play: async ({ canvas }) => {
		canvas.getByRole("row", { name: /Product language/u });
		canvas.getByRole("switch", { name: "Show turned-off practices" });
	},
};

/** The Group filter narrows the rows to the practices in the chosen groups. */
export const FilteredByGroup: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Group" }));
		await userEvent.click(await screen.findByRole("option", { name: "Code quality" }));
		await expect(canvas.getByRole("combobox", { name: /^Group/u })).toHaveAccessibleName(
			"Group: Code quality",
		);
		const rows = within(canvas.getByRole("table")).getAllByRole("row").slice(1);
		await expect(rows.map((row) => within(row).getAllByRole("cell")[0]?.textContent)).toEqual([
			expect.stringContaining("Errors carry their context"),
			expect.stringContaining("Thin controllers"),
			"All practices",
		]);
		// The totals stay those of every practice, and the line under the table says what is hidden.
		await expect(
			canvas.getByText(
				"The totals include 3 practices that the filters hide, with 24 observations.",
			),
		).toBeVisible();
	},
};

/**
 * A group from the URL that no practice of this range belongs to, for example kept from another
 * range, filters nothing: the facet could neither show nor clear it.
 */
export const GroupNotInRange: Story = {
	args: { filter: { groups: ["no-such-group"], showTurnedOff: false, onChange: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: /^Group/u })).toHaveAccessibleName("Group");
		canvas.getByRole("row", { name: /Thin controllers/u });
	},
};

/** The number of hidden practices counts only those the Group filter would show. */
export const TurnedOffInAnotherGroup: Story = {
	args: {
		practices: workspacePractices.map((practice) =>
			practice.slug === "thin-controllers"
				? { ...practice, feedbackMode: { ...practice.feedbackMode, effective: "OFF" } }
				: practice,
		),
		filter: { groups: ["testing"], showTurnedOff: false, onChange: fn() },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("switch", { name: "Show turned-off practices" });
	},
};

/** On a phone the table keeps every column and scrolls sideways in its frame; the page does not. */
export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("columnheader", { name: "Not met" });
		const table = canvas.getByRole("table", TABLE);
		await expect(table.parentElement).toHaveAttribute("data-slot", "table-container");
		await expectNoPageOverflow();
	},
};

/** The group pills, the counts and the head keep their contrast on dark. */
export const Dark: Story = {
	globals: { theme: "dark" },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		canvas.getByRole("table", TABLE);
	},
};

/** The previous range's rows stand in, drained of colour and marked busy, while the next loads. */
export const Stale: Story = {
	args: { state: { status: "ready", overview: practiceReviewOverview, stale: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
		canvas.getByRole("link", { name: "Open practice Thin controllers" });
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
		canvas.getByRole("columnheader", { name: /^Marked incorrect/u });
		await expect(within(table).queryByRole("link")).not.toBeInTheDocument();
	},
};
