import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectPlaceholderRows } from "@/stories/loading-more";
import { loadedList } from "@/stories/paged-list";
import { StatefulPatch } from "@/stories/stateful";

import { practiceGroups, reviewProblems, workspacePractices } from "./fixtures";
import { ProblemsListPage } from "./ProblemsListPage";
import { groupFacetOptions, practiceFacetOptions } from "./ResultFilters";

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

const meta = {
	component: ProblemsListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		search: { severity: undefined },
		onSearchChange: fn(),
		problems: loadedList(reviewProblems),
		groups: GROUPS,
		practices: PRACTICES,
	},
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onSearchChange) => (
				<ProblemsListPage
					{...args}
					search={search}
					onSearchChange={(patch) => {
						args.onSearchChange(patch);
						onSearchChange(patch);
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ProblemsListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText(`${reviewProblems.length} problems.`);
		for (const name of ["Group", "Practice", "Severity"]) {
			canvas.getByRole("combobox", { name });
		}
		canvas.getByRole("button", { name: "Recorded" });
	},
};

/** A staff list names no developer (ADR 0125): no Developer column, no filter by person. */
export const NamesNoDeveloper: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText(`${reviewProblems.length} problems.`);
		await expect(canvas.queryByRole("combobox", { name: "Developer" })).toBeNull();
		await expect(canvas.queryAllByRole("columnheader", { name: "Developer" })).toHaveLength(0);
		await expect(canvas.queryByText("Unavailable developer")).toBeNull();
	},
};

export const FilterToOneSeverity: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ args, canvas, userEvent }) => {
		const trigger = canvas.getByRole("combobox", { name: "Severity" });
		await userEvent.click(trigger);
		const listbox = await screen.findByRole("listbox", { name: "Severity options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Major/u }));
		await userEvent.click(trigger);
		await expect(args.onSearchChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ severity: ["MAJOR"] }),
		);
	},
};

export const Loading: Story = {
	args: { problems: { status: "loading" } },
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { problems: loadedList(reviewProblems, { hasMore: true, isLoadingMore: true }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, canvasElement }) => {
		await expectPlaceholderRows(canvasElement, canvas.getByRole("table", { name: "Problems" }));
	},
};

export const Empty: Story = {
	args: { problems: loadedList([]) },
	play: async ({ canvas }) => {
		await canvas.findByText("No problems yet");
	},
};

export const LoadFailed: Story = {
	args: {
		problems: {
			status: "error",
			error: { status: 500, detail: "The problem index is unavailable." },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("We could not load problems")).toBeVisible();
	},
};

const retryRefresh = fn();
export const RefreshFailed: Story = {
	args: {
		problems: loadedList(reviewProblems, {
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: retryRefresh },
		}),
	},
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not refresh problems");
		await expect(canvas.getByRole("table").querySelectorAll("tbody tr").length).toBeGreaterThan(0);
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retryRefresh).toHaveBeenCalledOnce();
	},
};
