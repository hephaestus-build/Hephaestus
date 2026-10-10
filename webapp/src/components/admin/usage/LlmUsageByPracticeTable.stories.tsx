import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { horizontalScrollParentOf } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	commentQualityUsage,
	deletedPracticeUsage,
	noChargeUsage,
	notAttributedUsage,
	expectedBehaviourUsage,
	usageReport,
	withOwnProvider,
	withPrecomputeUsage,
} from "./fixtures";
import { LlmUsageByPracticeTable } from "./LlmUsageByPracticeTable";

const TABLE_NAME = "Precompute model spend by practice";

const priced = withPrecomputeUsage(withOwnProvider(usageReport()));

/** `find`, not `get`: the preview's router mounts the story after the play function starts. */
async function tableIn(canvas: StoryContext["canvas"]): Promise<HTMLElement> {
	return canvas.findByRole("table", { name: TABLE_NAME });
}

async function rowNamed(canvas: StoryContext["canvas"], name: RegExp): Promise<HTMLElement> {
	return within(await tableIn(canvas)).getByRole("row", { name });
}

/** Rendered text, the hidden zeros that line up an average column's decimal points included. */
function cellTexts(row: HTMLElement): string[] {
	return within(row)
		.getAllByRole("cell")
		.map((cell) => cell.textContent);
}

/** The practice column top to bottom, between the two header rows and the footer. */
async function practiceOrder(canvas: StoryContext["canvas"]): Promise<string[]> {
	return within(await tableIn(canvas))
		.getAllByRole("row")
		.slice(2)
		.filter((row) => row.parentElement?.tagName === "TBODY")
		.map((row) => within(row).getAllByRole("cell")[0]?.textContent ?? "");
}

/**
 * What the decision, embedding and reranking calls of each practice's precompute script cost, one
 * row per practice. That spend is already inside the run types, so this table is a split of it, never
 * an addition to it. The footer is the server's own total: one review runs several practices, so
 * its reviews do not add up from the rows.
 *
 * Each purse heads its own Spend and Avg per review, so a cell holds one figure and two people's
 * money is never read as one sum.
 */
const meta = {
	component: LlmUsageByPracticeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { report: priced, purses: ["SHARED", "OWN_PROVIDER"], workspaceSlug: "acme" },
} satisfies Meta<typeof LlmUsageByPracticeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The purse names group their columns, so only the leaf columns sort and carry `aria-sort`. With
 * every call priced, there is no *No price set* column. The two purses' Spend buttons are
 * told apart by name.
 */
export const Priced: Story = {
	play: async ({ canvas }) => {
		const table = within(await tableIn(canvas));
		const headers = table.getAllByRole("columnheader");
		const groups = headers.filter((header) => header.getAttribute("colspan") === "2");
		await expect(
			groups.map((group) => [group.textContent, group.getAttribute("colspan")]),
		).toStrictEqual([
			["Shared models", "2"],
			["Own provider", "2"],
		]);
		await expect(groups.every((group) => !group.hasAttribute("aria-sort"))).toBe(true);
		await expect(table.getByRole("columnheader", { name: "Practice" })).toHaveAttribute(
			"rowspan",
			"2",
		);
		await expect(table.getByRole("columnheader", { name: "Models" })).not.toHaveAttribute(
			"aria-sort",
		);
		await expect(
			["Spend (Shared models)", "Spend (Own provider)"].map(
				(name) => table.getByRole("columnheader", { name }).ariaSort,
			),
		).toStrictEqual(["descending", "none"]);
		await expect(table.queryByRole("columnheader", { name: "No price set" })).toBeNull();

		const row = await rowNamed(canvas, /^Comments explain why/u);
		// Nothing spent on the own provider still averages: $0.000, not a dash. Each average column
		// shows three decimals, because *Not attributed* averages half a cent.
		await expect(cellTexts(row)).toStrictEqual([
			"Comments explain why",
			"Decision model",
			"$0.36",
			"$0.020",
			"$0.00",
			"$0.000",
			"18",
			"96,400",
			"1,240",
			"412",
		]);
		const link = within(row).getByRole("link", { name: "Comments explain why" });
		const href = new URL(String(link.getAttribute("href")), window.location.origin);
		await expect(href.pathname).toBe("/w/acme/admin/practices");
		await expect(levelsOpenedBy(link)).toStrictEqual(["practice:comment-quality"]);
	},
};

/** A single row has no footer: it would restate the line above it. */
export const OneRow: Story = {
	args: { report: { ...priced, byPractice: [commentQualityUsage] } },
	play: async ({ canvas }) => {
		const table = await tableIn(canvas);
		await expect(within(table).queryByRole("row", { name: /^Total/u })).toBeNull();
		await expect(await practiceOrder(canvas)).toStrictEqual(["Comments explain why"]);
	},
};

/**
 * Most shared-model spend first; any leaf column re-sorts, a figure biggest first. *Not attributed*
 * is not a practice, so it stays last in both directions.
 */
export const Sorted: Story = {
	play: async ({ canvas, userEvent }) => {
		await expect(await practiceOrder(canvas)).toStrictEqual([
			"Comments explain why",
			"Issues state the expected behaviour",
			"Names say what they hold",
			"Not attributed",
		]);
		const table = within(await tableIn(canvas));
		const calls = table.getByRole("columnheader", { name: "Calls" });
		await userEvent.click(within(calls).getByRole("button", { name: "Calls" }));

		await expect(calls).toHaveAttribute("aria-sort", "descending");
		await expect(
			table.getByRole("columnheader", { name: "Spend (Shared models)" }),
		).toHaveAttribute("aria-sort", "none");
		await expect(await practiceOrder(canvas)).toStrictEqual([
			"Comments explain why",
			"Names say what they hold",
			"Issues state the expected behaviour",
			"Not attributed",
		]);

		await userEvent.click(within(calls).getByRole("button", { name: "Calls" }));
		await expect(calls).toHaveAttribute("aria-sort", "ascending");
		await expect(await practiceOrder(canvas)).toStrictEqual([
			"Issues state the expected behaviour",
			"Names say what they hold",
			"Comments explain why",
			"Not attributed",
		]);
	},
};

/** Two purses on one practice: each averaged in its own column, never summed. */
export const SharedAndOwnProvider: Story = {
	args: { report: { ...priced, byPractice: [expectedBehaviourUsage] } },
	play: async ({ canvas }) => {
		const row = await rowNamed(canvas, /^Issues state the expected behaviour/u);
		await expect(within(row).getByText("Embedding model")).toBeVisible();
		await expect(within(row).getByText("Reranking model")).toBeVisible();
		await expect(cellTexts(row).slice(2, 6)).toStrictEqual(["$0.12", "$0.01", "$0.24", "$0.02"]);
	},
};

/** A workspace without a provider of its own reads no columns of $0. */
export const OwnProviderAbsent: Story = {
	args: {
		purses: ["SHARED"],
		report: {
			byPractice: [commentQualityUsage, noChargeUsage],
			precomputeTotal: {
				reviews: 27,
				calls: 552,
				inputTokens: 148_400,
				outputTokens: 1240,
				instanceTotalCostUsd: 0.36,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
			},
		},
	},
	play: async ({ canvas }) => {
		const table = within(await tableIn(canvas));
		await expect(table.queryByText("Own provider")).toBeNull();
		await expect(table.getAllByRole("columnheader", { name: /^Spend/u })).toHaveLength(1);
	},
};

/**
 * A reranker that reports no tokens: the reviews that called it are counted under *No price set*,
 * and the row has no average: its $0.00 is not a known cost.
 * The column is there only while some row has such a review. Its header wraps onto two lines, so at
 * the width of a 1280 px screen the table still shows every column without scrolling.
 */
export const UnpricedReviews: Story = {
	decorators: [
		(Story) => (
			<div className="w-312">
				<Story />
			</div>
		),
	],
	args: {
		report: {
			byPractice: [commentQualityUsage, deletedPracticeUsage],
			precomputeTotal: {
				reviews: 20,
				calls: 433,
				inputTokens: 96_400,
				outputTokens: 1240,
				instanceTotalCostUsd: 0.36,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 2,
			},
		},
	},
	play: async ({ canvas }) => {
		const table = within(await tableIn(canvas));
		const header = table.getByRole("columnheader", { name: "No price set" });
		await expect(header).toHaveAttribute("rowspan", "2");
		const scroller = horizontalScrollParentOf(await tableIn(canvas));
		await expect(scroller.scrollWidth).toBeLessThanOrEqual(scroller.clientWidth + 1);
		const cells = cellTexts(await rowNamed(canvas, /small-pull-requests/u));
		await expect(cells[7]).toBe("2");
		await expect(cells[3]).toBe("—");
		// The footer's label is a row header, so its cells start at Models.
		await expect(cellTexts(await rowNamed(canvas, /^Total/u))[6]).toBe("2");
	},
};

/** *No metered API cost*: a confirmed $0.00 with nothing missing, which averages to $0.00. */
export const NoChargeConfirmed: Story = {
	args: { report: { ...priced, byPractice: [noChargeUsage] } },
	play: async ({ canvas }) => {
		const cells = cellTexts(await rowNamed(canvas, /^Names say what they hold/u));
		await expect(cells.slice(2, 6)).toStrictEqual(["$0.00", "$0.00", "$0.00", "$0.00"]);
	},
};

export const NotAttributed: Story = {
	args: {
		report: {
			byPractice: [commentQualityUsage, notAttributedUsage],
			precomputeTotal: {
				reviews: 19,
				calls: 426,
				inputTokens: 99_500,
				outputTokens: 1280,
				instanceTotalCostUsd: 0.37,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
			},
		},
	},
	play: async ({ canvas }) => {
		const row = await rowNamed(canvas, /^Not attributed/u);
		await expect(within(row).queryByRole("link")).toBeNull();
	},
};

/** A practice deleted since its reviews ran keeps its row under its slug, without a link. */
export const NotACurrentPractice: Story = {
	args: { report: { ...priced, byPractice: [commentQualityUsage, deletedPracticeUsage] } },
	play: async ({ canvas }) => {
		const row = await rowNamed(canvas, /small-pull-requests/u);
		await expect(within(row).getByText("Not a current practice")).toBeVisible();
		await expect(within(row).queryByRole("link")).toBeNull();
	},
};

/**
 * The footer reads the server's total: 31 reviews, where the rows add up to 41, because a review
 * that ran several scripts counts once. Its averages are the total's own, and its label is a row
 * header.
 */
export const Totals: Story = {
	play: async ({ canvas }) => {
		const footer = await rowNamed(canvas, /^Total/u);
		await expect(within(footer).getByRole("rowheader", { name: "Total" })).toBeVisible();
		await expect(cellTexts(footer)).toStrictEqual([
			"",
			"$0.49",
			"$0.016",
			"$0.24",
			"$0.008",
			"31",
			"361,500",
			"1,280",
			"662",
		]);
	},
};

/**
 * A cheap embedding model costs a fraction of a cent per review, which is what the average is for.
 * Its column shows the four decimals that model needs on every row and the total, so the decimal
 * points line up; the spend beside it stays in cents.
 */
export const SubCentAverage: Story = {
	args: {
		purses: ["SHARED"],
		report: {
			byPractice: [
				commentQualityUsage,
				notAttributedUsage,
				{ ...noChargeUsage, instanceTotalCostUsd: 0.0042 },
			],
			precomputeTotal: {
				reviews: 29,
				calls: 566,
				inputTokens: 151_500,
				outputTokens: 1280,
				instanceTotalCostUsd: 0.3742,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
			},
		},
	},
	play: async ({ canvas }) => {
		const rows = [
			/^Comments explain why/u,
			/^Names say what they hold/u,
			/^Not attributed/u,
			/^Total/u,
		];
		const spendAndAverage = await Promise.all(
			rows.map(async (name) => cellTexts(await rowNamed(canvas, name)).slice(-6, -4)),
		);
		await expect(spendAndAverage).toStrictEqual([
			["$0.36", "$0.0200"],
			["<$0.01", "$0.0005"],
			["$0.01", "$0.0050"],
			["$0.37", "$0.0129"],
		]);
	},
};

export const Loading: Story = {
	args: { report: undefined },
	play: async ({ canvas }) => {
		const table = await tableIn(canvas);
		// Two header rows over three placeholder rows.
		await expect(within(table).getAllByRole("row")).toHaveLength(5);
		await expect(within(table).queryByRole("link")).toBeNull();
	},
};

/**
 * WCAG 2.2 SC 1.4.10: the table takes the data-table exception and scrolls inside its own box; the
 * page itself never scrolls sideways. Token and call counts give way below `lg`, and the practice
 * column stays in view while the money scrolls under it.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas, canvasElement }) => {
		const table = await tableIn(canvas);
		const scroller = horizontalScrollParentOf(table);
		await expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth + 1);
		await expect(within(table).queryByRole("columnheader", { name: "Calls" })).toBeNull();

		const practice = within(table).getByRole("link", { name: "Comments explain why" });
		const before = practice.getBoundingClientRect().left;
		scroller.scrollLeft = scroller.scrollWidth;
		await expect(practice.getBoundingClientRect().left).toBe(before);

		// Nothing that scrolled under the practice column shows through it: a header's sort icon
		// included. Each cell of the column is on top across its whole width, in every row.
		const firstCells = [...table.querySelectorAll<HTMLElement>("th, td")].filter(
			(cell) => getComputedStyle(cell).position === "sticky",
		);
		// The header, four practices and the total.
		await expect(firstCells).toHaveLength(6);
		const showingThrough = firstCells.flatMap((cell) => {
			const box = cell.getBoundingClientRect();
			const points: string[] = [];
			// Clear of the frame's rounded corner, which clips the corner cells.
			for (let x = box.left + 4; x < box.right - 1; x += 3) {
				for (let y = box.top + 4; y < box.bottom - 1; y += 3) {
					const hit = document.elementFromPoint(x, y);
					if (!cell.contains(hit)) {
						points.push(`${hit?.tagName ?? "nothing"} at ${Math.round(x)},${Math.round(y)}`);
					}
				}
			}
			return points;
		});
		await expect(showingThrough).toStrictEqual([]);
	},
};
