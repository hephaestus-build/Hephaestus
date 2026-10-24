import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectTablePlaceholders } from "@/stories/loading-more";
import { StatefulPatch } from "@/stories/stateful";

import { ITEM_ENTRIES, LISTED_ENTRY, WITHHELD_ENTRY } from "./fixtures";
import { ScanItemTable } from "./ScanItemTable";

const ready = (entries = ITEM_ENTRIES, hasNext = false) => ({
	status: "ready" as const,
	entries,
	hasNext,
	loadingMore: false,
	onLoadMore: fn(),
});

const meta = {
	component: ScanItemTable,
	decorators: [withStandardPage],
	args: {
		workspaceSlug: "aet",
		state: ready(),
		filter: undefined,
		onFilterChange: fn(),
		progress: "complete",
	},
	argTypes: { state: { control: false } },
	render: (args) => (
		<StatefulPatch initial={{ filter: args.filter }}>
			{(value, patch) => (
				<ScanItemTable
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
} satisfies Meta<typeof ScanItemTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const [judged, reused, withheld, notReviewed, removed] = rows;
		if (!judged || !reused || !withheld || !notReviewed || !removed) {
			return;
		}
		await expect(within(judged).getByText("Not met")).toBeVisible();
		await expect(within(judged).getByText("2 problems")).toBeVisible();
		await expect(within(reused).getByText(/Reused from a review on/u)).toBeVisible();
		await expect(
			within(withheld).getByText("The assigned AI model was unavailable."),
		).toBeVisible();
		await expect(withheld).not.toHaveTextContent("MODEL_UNAVAILABLE");
		await expect(
			within(notReviewed).getByText("The scan ended before this file was reviewed."),
		).toBeVisible();
		await expect(
			within(notReviewed).getByText(/Earlier evidence, not from this scan/u),
		).toBeVisible();
		await expect(within(removed).getByText("Removed")).toBeVisible();

		// A row with this scan's result opens it over Practice reviews; earlier evidence opens nothing.
		await expect(
			within(judged).getByRole("link", {
				name: "Open observation server/src/ScanService.java, Errors are handled where they occur",
			}),
		).toHaveAttribute("href", "/w/aet/admin/practices/reviews/results?detail=result:r-1");
		await expect(within(reused).getByRole("link", { name: /^Open observation /u })).toHaveAttribute(
			"href",
			"/w/aet/admin/practices/reviews/results?detail=result:r-2",
		);
		await expect(within(withheld).queryByRole("link")).not.toBeInTheDocument();
		await expect(within(notReviewed).queryByRole("link")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("button", { name: "Withheld" }));
		await expect(args.onFilterChange).toHaveBeenCalledWith("WITHHELD");
		await expect(canvas.getByRole("button", { name: "Withheld" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
	},
};

export const Waiting: Story = {
	args: { state: ready([LISTED_ENTRY, WITHHELD_ENTRY], true) },
	play: async ({ canvas }) => {
		const [listed] = canvas.getAllByRole("row").slice(1);
		if (listed) {
			await expect(within(listed).getByText("Waiting")).toBeVisible();
			await expect(within(listed).getByText("Not reviewed yet.")).toBeVisible();
		}
		await expect(canvas.getByRole("button", { name: "Show more files" })).toBeVisible();
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { state: { ...ready(ITEM_ENTRIES, true), loadingMore: true } },
	play: async ({ canvas }) => {
		await expectTablePlaceholders(canvas.getByRole("table", { name: "Files and practices" }));
		await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
	},
};

export const LoadMoreFailed: Story = {
	args: {
		state: { ...ready([LISTED_ENTRY, WITHHELD_ENTRY], true), loadMoreError: { status: 500 } },
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByText("We could not load more files")).toBeVisible();
		await expect(canvas.getAllByRole("row")).toHaveLength(3);
		await userEvent.click(canvas.getByRole("button", { name: "Show more files" }));
		if (args.state.status === "ready") {
			await expect(args.state.onLoadMore).toHaveBeenCalledOnce();
		}
	},
};

export const NoFilesYet: Story = {
	args: { state: ready([]), progress: "active" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No files listed yet" })).toBeVisible();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

/** A scan that failed while listing: the empty list is not a repository without files. */
export const NoFilesBeforeTheScanStopped: Story = {
	args: { state: ready([]), progress: "stopped" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No files were listed" })).toBeVisible();
		await expect(
			canvas.getByText("The scan stopped before it could list the repository’s files."),
		).toBeVisible();
		await expect(canvas.queryByText(/yet/u)).not.toBeInTheDocument();
	},
};

export const NoMatches: Story = {
	args: { state: ready([]), filter: "NOT_REVIEWED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No files match this filter" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Files and practices" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the files")).toBeVisible();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};
