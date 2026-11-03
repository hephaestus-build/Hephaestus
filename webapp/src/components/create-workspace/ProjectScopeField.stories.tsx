import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { GitLabProject } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ProjectScopeField } from "./ProjectScopeField";

const project = (id: number, name: string, fullPath: string): GitLabProject => ({
	id,
	name,
	fullPath,
});

const PROJECTS = [
	project(11, "API", "ls1intum/hephaestus/api"),
	project(12, "Docs", "ls1intum/hephaestus/docs"),
	project(13, "Web", "ls1intum/hephaestus/web"),
];

/**
 * The configure step's choice of the group's projects to monitor. All projects needs no list;
 * selected projects lists the group's projects a page at a time.
 */
const meta = {
	component: ProjectScopeField,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		scope: "SELECTED",
		onScopeChange: fn(),
		selectedIds: [11],
		onSelectedChange: fn(),
		choices: { status: "ready", projects: PROJECTS, page: 1, hasNextPage: true, search: "" },
		onSearch: fn(),
		onPreviousPage: fn(),
		onNextPage: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ProjectScopeField>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The default: every project of the group, and no list to read. */
export const AllProjects: Story = {
	args: { scope: "ALL", selectedIds: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^All projects/u })).toBeChecked();
		await expect(canvas.queryByRole("searchbox", { name: "Search projects" })).toBeNull();
		await expect(
			canvas.getByText(/Workspace members and teams still come from the whole group/u),
		).toBeVisible();
	},
};

/** A page of the group's projects; one is chosen, and another page follows. */
export const Selected: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("checkbox", { name: "API" })).toBeChecked();
		await expect(canvas.getByText("1 project selected")).toBeVisible();
		await expect(canvas.getByRole("checkbox", { name: "Docs" })).not.toBeChecked();
		await expect(canvas.getByRole("button", { name: "Previous page" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Next page" })).toBeEnabled();
	},
};

/** Selected projects with none chosen yet: the reason the workspace cannot be created yet. */
export const NoneChosen: Story = {
	args: { selectedIds: [] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Choose at least one project, or monitor all projects."),
		).toBeVisible();
	},
};

/** The search keeps the words being typed while the current page stays visible. */
export const Search: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search projects" }), "  api ");
		await expect(canvas.getByRole("searchbox", { name: "Search projects" })).toHaveValue("  api ");
		await expect(canvas.getByRole("checkbox", { name: "API" })).toBeVisible();
	},
};

/** The list is on its way: rows in its shape, and a chosen count that is already true. */
export const Loading: Story = {
	args: { choices: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading projects…")).toBeVisible();
		await expect(canvas.queryByRole("checkbox")).toBeNull();
	},
};

/** GitLab did not answer: the reason, and a retry. The choice stays selected projects. */
export const LoadError: Story = {
	args: {
		choices: {
			status: "error",
			error: { status: 502, detail: "GitLab did not answer. Try again in a moment." },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the group’s projects")).toBeVisible();
		await expect(canvas.getByRole("radio", { name: /^Selected projects/u })).toBeChecked();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

/** The only page has no project to show, and no other page follows. */
export const Empty: Story = {
	args: {
		selectedIds: [],
		choices: { status: "ready", projects: [], page: 1, hasNextPage: false, search: "" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No projects to show on this page.")).toBeVisible();
		await expect(canvas.queryByRole("navigation", { name: "Project pages" })).toBeNull();
	},
};

/**
 * A page between others comes back empty. It says so about this page only, and both directions
 * stay open, so the projects on later pages can still be reached.
 */
export const EmptyIntermediatePage: Story = {
	args: {
		selectedIds: [],
		choices: { status: "ready", projects: [], page: 2, hasNextPage: true, search: "" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No projects to show on this page.")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Previous page" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Next page" })).toBeEnabled();
		await expect(
			canvas.getByText("Choose at least one project, or monitor all projects."),
		).toBeVisible();
	},
};

/** This search page has nothing to show. */
export const NoMatch: Story = {
	args: {
		choices: { status: "ready", projects: [], page: 1, hasNextPage: false, search: "zzz" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No matching projects on this page.")).toBeVisible();
	},
};

/** At 320px, long project paths wrap inside their row and the page does not scroll sideways. */
export const Phone: Story = {
	args: {
		choices: {
			status: "ready",
			projects: [
				project(
					31,
					"Infrastructure",
					"ls1intum/department/sub-team/a-very-long-group-path-name/infrastructure",
				),
				...PROJECTS,
			],
			page: 2,
			hasNextPage: true,
			search: "",
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async (context) => {
		await expect(window.innerWidth).toBe(320);
		await expect(context.canvas.getByRole("checkbox", { name: "Infrastructure" })).toBeVisible();
		await expectNoPageOverflow();
	},
};
