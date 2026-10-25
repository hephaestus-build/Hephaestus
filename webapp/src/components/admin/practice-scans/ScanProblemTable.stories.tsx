import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ScanReportProblem } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectTablePlaceholders } from "@/stories/loading-more";
import { StatefulPatch } from "@/stories/stateful";

import { PROBLEM_ENTRIES } from "./fixtures";
import { ScanProblemTable } from "./ScanProblemTable";

function citedFrom(
	entry: ScanReportProblem,
	citation: Partial<ScanReportProblem["problem"]["citations"][number]>,
): ScanReportProblem {
	return {
		...entry,
		problem: {
			...entry.problem,
			citations: entry.problem.citations.map((cited) => ({ ...cited, ...citation })),
		},
	};
}

// A scan cites the scanned file from the repository tree, as the source catalog declares.
const TREE_ENTRIES = PROBLEM_ENTRIES.map((entry) =>
	citedFrom(entry, { sourceKind: "scm.repository.tree" }),
);

const ready = (entries = TREE_ENTRIES) => ({
	status: "ready" as const,
	entries,
	hasNext: false,
	loadingMore: false,
	onLoadMore: fn(),
});

const meta = {
	component: ScanProblemTable,
	decorators: [withStandardPage],
	args: {
		workspaceSlug: "aet",
		state: ready(),
		filter: undefined,
		onFilterChange: fn(),
		changes: { new: 1, unchanged: 1, updated: 1, absent: 2 },
		pairsWithoutResult: 0,
		progress: "complete",
	},
	argTypes: { state: { control: false }, changes: { control: false } },
	render: (args) => (
		<StatefulPatch initial={{ filter: args.filter }}>
			{(value, patch) => (
				<ScanProblemTable
					{...args}
					filter={value.filter}
					onFilterChange={(filter) => {
						patch({ filter });
						args.onFilterChange(filter);
					}}
				/>
			)}
		</StatefulPatch>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ScanProblemTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		const table = canvas.getByRole("table", { name: "Problems in this scan" });
		await expect(within(table).getByText("New")).toBeVisible();
		await expect(within(table).getByText("Unchanged")).toBeVisible();
		await expect(within(table).getByText("Updated")).toBeVisible();
		await expect(within(table).getByText("Absent")).toBeVisible();
		await expect(within(table).getAllByText("Now met")).toHaveLength(1);
		await expect(within(table).getByText(/That alone does not prove a fix\./u)).toBeVisible();
		await expect(within(table).getAllByText("server/src/ScanService.java:42–48")[0]).toBeVisible();

		// Each row opens its problem over Practice reviews.
		const open = within(table).getByRole("link", {
			name: "Open problem A failed upload is logged and then ignored., server/src/ScanService.java:42–48, Errors are handled where they occur",
		});
		await expect(open).toHaveAttribute(
			"href",
			"/w/aet/admin/practices/reviews/problems?detail=problem:p-1",
		);
		await expect(within(table).getAllByRole("link", { name: /^Open problem /u })).toHaveLength(5);

		await userEvent.click(canvas.getByRole("button", { name: "Absent (2)" }));
		await expect(args.onFilterChange).toHaveBeenCalledWith("ABSENT");
	},
};

const [first] = TREE_ENTRIES;
if (first === undefined) {
	throw new globalThis.Error("The scan fixtures must contain a problem");
}

/**
 * A code citation without a path of its own stands in the scanned file and keeps its real lines; a
 * citation into captured input names only the scanned file, with no file or line of that capture.
 */
export const WhereEachProblemSits: Story = {
	args: {
		state: ready([
			citedFrom(first, { path: null }),
			citedFrom(
				{ ...first, problem: { ...first.problem, id: "p-object" } },
				{
					sourceKind: "hephaestus.result-history",
					path: "context/result_history.json",
					startLine: 7,
					endLine: 7,
				},
			),
		]),
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Problems in this scan" });
		await expect(within(table).getByText("server/src/ScanService.java:42–48")).toBeVisible();
		await expect(within(table).getByText("server/src/ScanService.java")).toBeVisible();
		await expect(within(table).queryByText(/result_history\.json/u)).not.toBeInTheDocument();
		await expect(within(table).queryByText(/ScanService\.java:7/u)).not.toBeInTheDocument();
	},
};

export const NoMatches: Story = {
	args: { state: ready([]), filter: "UPDATED" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "No problems match this filter" }),
		).toBeVisible();
	},
};

// Withheld pairs prevent a scan with no problems from claiming that all practices were met.
export const NoProblemsWithWithholding: Story = {
	args: {
		state: ready([]),
		changes: { new: 0, unchanged: 0, updated: 0, absent: 0 },
		pairsWithoutResult: 4,
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "This scan recorded no problems" }),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"That does not mean every practice is met: 4 practice and file pairs have no observation in this scan.",
			),
		).toBeVisible();
	},
};

/**
 * A scan that failed, was cancelled or closed partial, with no problem recorded. It covers only the work
 * it reached, which may be none or every file with some results withheld, so the state claims neither a
 * stop nor a clean result.
 */
export const NoProblemsInTheWorkItReached: Story = {
	args: {
		state: ready([]),
		changes: { new: 0, unchanged: 0, updated: 0, absent: 0 },
		progress: "stopped",
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "No problems recorded in the work it reached" }),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"Its observations cover only the work it reached, so this does not show that the code is clean.",
			),
		).toBeVisible();
		await expect(canvas.queryByText("This scan recorded no problems")).not.toBeInTheDocument();
	},
};

export const NoProblemsYet: Story = {
	args: {
		state: ready([]),
		changes: { new: 0, unchanged: 0, updated: 0, absent: 0 },
		progress: "active",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No problems recorded yet" })).toBeVisible();
	},
};

export const NotCompared: Story = {
	args: {
		state: ready(PROBLEM_ENTRIES.slice(0, 2).map((entry) => ({ ...entry, baselineState: null }))),
		changes: { new: 0, unchanged: 0, updated: 0, absent: 0 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Not compared")).toHaveLength(2);
		await expect(canvas.queryByText("Now met")).not.toBeInTheDocument();
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { state: { ...ready(), hasNext: true, loadingMore: true } },
	play: async ({ canvas }) => {
		await expectTablePlaceholders(canvas.getByRole("table", { name: "Problems in this scan" }));
		await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Problems in this scan" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the problems")).toBeVisible();
	},
};
