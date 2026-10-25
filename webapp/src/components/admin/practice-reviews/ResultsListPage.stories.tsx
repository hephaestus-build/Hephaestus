import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewResult } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectPlaceholderRows } from "@/stories/loading-more";
import { loadedList, narrowedList } from "@/stories/paged-list";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import {
	manyResults,
	practiceGroups,
	reviewResults,
	selects,
	workspaceMembers,
	workspacePractices,
} from "./fixtures";
import { groupFacetOptions, practiceFacetOptions } from "./ResultFilters";
import { ResultsListPage } from "./ResultsListPage";
import { type ResultsSearch, resultsQuery, REVIEW_PAGE_SIZE } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";

const PEOPLE: ReviewPeople = {
	options: workspaceMembers.map(({ account }) => ({
		accountId: account.id,
		label: account.displayName ?? `#${account.id}`,
		secondary: account.identities[0]?.login ?? undefined,
	})),
	capped: false,
	isLoading: false,
	isError: false,
};
const GROUPS: FacetSource = {
	options: groupFacetOptions(practiceGroups),
	isLoading: false,
	isError: false,
};
const PRACTICES: FacetSource = {
	options: practiceFacetOptions(workspacePractices, practiceGroups),
	isLoading: false,
	isError: false,
};

const loadMoreResults = fn();

const pool = loadedList<ReviewResult>;

// Stories filter supplied rows locally because presentational components must not install network handlers.
const SEVERITY_RANK: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, TRIVIAL: 3 };

function actionabilityKey(result: ReviewResult): number {
	if (result.outcome === "NOT_MET") {
		return SEVERITY_RANK[result.severity ?? ""] ?? 4;
	}
	return result.outcome === "MET" ? 10 : 20;
}

function inServerOrder(
	list: PagedListState<ReviewResult>,
	search: ResultsSearch,
): PagedListState<ReviewResult> {
	if (list.status !== "ready" || search.order !== "ACTIONABILITY") {
		return list;
	}
	const rows = [...list.rows].sort(
		(a, b) =>
			actionabilityKey(a) - actionabilityKey(b) || b.recordedAt.getTime() - a.recordedAt.getTime(),
	);
	return { ...list, rows };
}

function resultPage(
	list: PagedListState<ReviewResult>,
	search: ResultsSearch,
): PagedListState<ReviewResult> {
	const query = resultsQuery(search, REVIEW_PAGE_SIZE);
	return narrowedList(
		inServerOrder(list, search),
		(row) =>
			(!query.from || row.recordedAt >= new Date(query.from)) &&
			(!query.to || row.recordedAt < new Date(query.to)) &&
			selects(search.practiceSlug, row.practiceSlug) &&
			selects(query.severity, row.severity) &&
			selects(query.outcome, row.outcome) &&
			(query.reviewId === undefined || row.reviewId === query.reviewId) &&
			(search.developerId === undefined || row.developer?.accountId === search.developerId),
		REVIEW_PAGE_SIZE,
	);
}

const meta = {
	component: ResultsListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		search: {
			outcome: undefined,
			severity: undefined,
		},
		onSearchChange: fn(),
		results: pool(reviewResults),
		groups: GROUPS,
		practices: PRACTICES,
		people: PEOPLE,
	},
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onSearchChange) => (
				<ResultsListPage
					{...args}
					search={search}
					onSearchChange={(patch) => {
						args.onSearchChange(patch);
						onSearchChange(patch);
					}}
					results={resultPage(args.results, search)}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ResultsListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

// Close each multi-select before opening the next; multiple open listboxes make role queries ambiguous.
async function pickFacet(
	canvas: StoryContext["canvas"],
	userEvent: { click: (element: Element) => Promise<void> },
	facet: string,
	option: RegExp,
) {
	const trigger = canvas.getByRole("combobox", { name: facet });
	await userEvent.click(trigger);
	const listbox = await screen.findByRole("listbox", { name: `${facet} options` });
	await userEvent.click(await within(listbox).findByRole("option", { name: option }));
	await userEvent.click(trigger);
}

export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText("16 observations.");
		for (const name of ["Group", "Practice", "Outcome", "Severity"]) {
			canvas.getByRole("combobox", { name });
		}
		await expect(canvas.queryByRole("button", { name: /More filters/u })).toBeNull();
		canvas.getByRole("combobox", { name: "Developer" });
		canvas.getByRole("button", { name: "Recorded" });
		canvas.getByText("The controller delegates before it does anything else");
		canvas.getByText("The thread ends without naming what was chosen");
		canvas.getByText("The runbook opens with the one step that cannot be undone");
	},
};

export const FilterToOneSeverity: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Major/u);
		await canvas.findByText("3 observations match your filters.");
		await expect(canvas.queryByText(/leaks the ledger’s table name/u)).not.toBeInTheDocument();
	},
};

export const FilterToOnePerson: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		const listbox = await screen.findByRole("listbox");
		await userEvent.click(await within(listbox).findByRole("option", { name: /Grace Hopper/u }));
		await canvas.findByRole("combobox", { name: "Developer: Grace Hopper" });
		await canvas.findByText("3 observations match your filters.");
	},
};

export const NarrowedToTheSelectedResult: Story = {
	args: {
		search: {
			outcome: undefined,
			severity: undefined,
			reviewId: "11111111-1111-1111-1111-111111111111",
			resultId: "66666666-6666-6666-6666-666666666666",
		},
	},
	parameters: {
		router: {
			initialUrl:
				"/?reviewId=11111111-1111-1111-1111-111111111111&resultId=66666666-6666-6666-6666-666666666666",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Select/u })).not.toBeInTheDocument();
		await expect(
			within(canvas.getByRole("table", { name: "Observations" }))
				.getAllByRole("row")
				.filter((row) => within(row).queryAllByRole("cell").length > 0),
		).toHaveLength(4);
	},
};

export const FilteredToNothing: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Critical/u);
		await pickFacet(canvas, userEvent, "Outcome", /Met/u);
		await canvas.findByText("No observations match these filters");
		canvas.getByRole("button", { name: "Reset" });
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await canvas.findByText("16 observations.");
	},
};

// The server owns actionability order; this control only changes the requested sort.
export const SortByActionability: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await userEvent.click(canvas.getByRole("combobox", { name: /Sort/u }));
		await userEvent.click(await screen.findByRole("option", { name: "Most actionable first" }));
		await expect(args.onSearchChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ order: "ACTIONABILITY" }),
		);
		const table = canvas.getByRole("table", { name: "Observations" });
		await waitFor(async () =>
			expect(
				[...table.querySelectorAll("th[scope=rowgroup]")].map((heading) => heading.textContent),
			).toContain("Met"),
		);
		const headings = [...table.querySelectorAll("th[scope=rowgroup]")].map(
			(heading) => heading.textContent,
		);
		await expect(headings.slice(0, 5)).toStrictEqual([
			"Not met · Critical",
			"Not met · Major",
			"Not met · Minor",
			"Not met · Trivial",
			"Met",
		]);
		await expect(new Set(headings).size).toBe(headings.length);
		await expect(table.querySelectorAll("tbody")).toHaveLength(headings.length);
	},
};

// Narrow facet controls collapse labels to counts; applied-value pills must retain the selected names.
export const MobileAppliedFilters: Story = {
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Major/u);
		await canvas.findByText("3 observations match your filters.");
		await canvas.findByTitle("Severity: Major");
		await userEvent.click(canvas.getByLabelText("Clear severity filter (Major)"));
		await canvas.findByText("16 observations.");
		await expectNoPageOverflow();
	},
};

export const PickADateRange: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("16 observations.");
		await userEvent.click(canvas.getByRole("button", { name: "Recorded" }));
		const dialog = await screen.findByRole("dialog");
		// Testing Library exposes calendar td elements as cells even inside a grid.
		const grid = await within(dialog).findByRole("grid");
		const days = within(grid).getAllByRole("button");
		const [rangeStart, rangeEnd] = [days[4], days[10]];
		if (!rangeStart || !rangeEnd) {
			throw new Error("The month grid rendered too few days");
		}
		await userEvent.click(rangeStart);
		await userEvent.click(rangeEnd);
		await canvas.findByText(/–/u);
	},
};

export const SeverityFacetOpen: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Severity" }));
		const listbox = await screen.findByRole("listbox");
		await within(listbox).findByRole("option", { name: /Critical/u });
		within(listbox).getByRole("option", { name: /Trivial/u });
	},
};

export const LoadsMoreAtTheEnd: Story = {
	args: { results: pool(manyResults(64), { onLoadMore: loadMoreResults }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("64 observations.");
		const more = canvas.getByRole("button", { name: "Show more observations" });
		more.scrollIntoView();
		await waitFor(async () => expect(loadMoreResults).toHaveBeenCalled());
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { results: pool(reviewResults, { hasMore: true, isLoadingMore: true }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, canvasElement }) => {
		await expectPlaceholderRows(canvasElement, canvas.getByRole("table", { name: "Observations" }));
	},
};

/** Ranked by actionability, the rows sit under ranks, and the placeholders still follow the last. */
export const LoadingMoreByActionability: Story = {
	args: {
		search: { outcome: undefined, severity: undefined, order: "ACTIONABILITY" },
		results: pool(reviewResults, { hasMore: true, isLoadingMore: true }),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, canvasElement }) => {
		await expectPlaceholderRows(canvasElement, canvas.getByRole("table", { name: "Observations" }));
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("16 observations.");
		await expectNoPageOverflow();
	},
};

export const LoadFailed: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: {
		results: { status: "error", error: { status: 500 }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("We could not load observations");
	},
};

export const Loading: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { results: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByRole("table", { name: "Observations" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

// An empty review history needs different wording from filters that matched no results.
export const NoResultsYet: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { results: pool([]) },
	play: async ({ canvas }) => {
		await canvas.findByText("No observations yet");
		await expect(
			canvas.getByText("Observations appear after a practice review completes."),
		).toBeVisible();
	},
};

export const RefreshFailed: Story = {
	args: {
		results: loadedList(reviewResults, {
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		}),
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("We could not refresh observations")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};
