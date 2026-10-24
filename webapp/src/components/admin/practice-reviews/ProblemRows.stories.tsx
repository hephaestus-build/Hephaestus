import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ReviewProblem } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";

import { reviewProblems } from "./fixtures";
import { ProblemRows } from "./ProblemRows";

const [first] = reviewProblems;
if (!first) {
	throw new Error("The review fixtures must contain a problem");
}

function hiddenRow(row: ReviewProblem, id: string): ReviewProblem {
	return {
		...row,
		id,
		path: null,
		startLine: null,
		endLine: null,
		side: null,
		sourceKind: null,
		evidenceHidden: true,
	};
}

const clearFilters = fn();
const retry = fn();

const TABLE = { name: "Problems" } as const;

const meta = {
	component: ProblemRows,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		state: { status: "ready", problems: reviewProblems },
	},
} satisfies Meta<typeof ProblemRows>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(within(table).getAllByRole("link", { name: /^Open problem/u })).toHaveLength(
			reviewProblems.length,
		);
		within(table).getByText(
			"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java:116–119",
		);
		// Severity reads in words on the row, not only as the time column's glyph.
		const [shown] = within(table).getAllByText(SEVERITY_DEFS[first.severity].label, {
			ignore: ".sr-only, script, style",
		});
		await expect(shown).toBeVisible();
		await expectNoPageOverflow();
	},
};

const code = reviewProblems.find((row) => row.sourceKind === "scm.pull-request.diff");
const codePath = code?.path;
if (!code || codePath === undefined || codePath === null) {
	throw new Error("The review fixtures must contain a problem cited on the diff");
}

export const Places: Story = {
	args: {
		state: {
			status: "ready",
			problems: [
				code,
				{
					...code,
					id: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
					side: "OLD",
					startLine: 40,
					endLine: 40,
				},
				{
					...code,
					id: "ffffffff-ffff-ffff-ffff-ffffffffffff",
					sourceKind: "scm.review-threads",
					path: "context/review_threads.json",
					startLine: 3,
					endLine: 3,
					side: null,
				},
			],
		},
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		within(table).getByText(`${codePath}:40`);
		await expect(within(table).getByText("Before")).toBeVisible();
		await expect(within(table).queryByText("After")).toBeNull();
		// A thread is read from the review's capture, which is no place in the work: its row names none.
		await expect(within(table).queryByText(/review_threads\.json/u)).toBeNull();
		await expect(within(table).getAllByRole("link", { name: /^Open problem/u })).toHaveLength(3);
	},
};

export const EvidenceHidden: Story = {
	args: {
		state: {
			status: "ready",
			problems: [
				hiddenRow(first, "cccccccc-cccc-cccc-cccc-cccccccccccc"),
				{
					...hiddenRow(first, "dddddddd-dddd-dddd-dddd-dddddddddddd"),
					resultId: "eeeeeeee-0000-0000-0000-000000000000",
					item: { ...first.item, container: "platform/billing-service" },
				},
			],
		},
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(within(table).getAllByText("Details hidden")).toHaveLength(2);
		for (const [role, name] of [
			["link", /^Open problem Details hidden/u],
			["button", /^Track observation Details hidden/u],
			["link", /^Trace observation Details hidden/u],
		] as const) {
			const controls = within(table).getAllByRole(role, { name });
			await expect(
				new Set(controls.map((control) => control.getAttribute("aria-label"))).size,
			).toBe(2);
		}
	},
};

export const ResultMarkedIncorrect: Story = {
	args: {
		state: {
			status: "ready",
			problems: [{ ...first, markedIncorrectAt: first.recordedAt }, ...reviewProblems.slice(1, 3)],
		},
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(within(table).getByText("Marked incorrect")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", TABLE)).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	play: async ({ canvas }) => {
		await expect(
			within(canvas.getByRole("table", TABLE)).getByText("No problems yet"),
		).toBeVisible();
	},
};

export const FilteredToNothing: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: clearFilters } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await expect(clearFilters).toHaveBeenCalled();
	},
};

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 500, detail: "The problem index is unavailable." },
			onRetry: retry,
		},
	},
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not load problems");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalled();
	},
};
