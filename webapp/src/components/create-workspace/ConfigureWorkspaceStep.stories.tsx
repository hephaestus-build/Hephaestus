import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { GitLabGroup } from "@/api/types.gen";

import { ConfigureWorkspaceStep } from "./ConfigureWorkspaceStep";
import { ProjectScopeField } from "./ProjectScopeField";
import { makeGroup, withWizardReducer, withWizardState } from "./stories-utils";

const defaultGroup: GitLabGroup = makeGroup(1, "Hephaestus", "ls1intum/hephaestus", {
	visibility: "public",
});

/** The project choice as the step opens: every project, no list read. */
const allProjects = (
	<ProjectScopeField
		scope="ALL"
		onScopeChange={fn()}
		selectedIds={[]}
		onSelectedChange={fn()}
		choices={{ status: "loading" }}
		onSearch={fn()}
		onPageChange={fn()}
	/>
);

/**
 * Final configuration step of the GitLab workspace creation wizard.
 * Shows display name and slug inputs with live validation, the projects to monitor,
 * plus a summary panel of all prior selections.
 */
const meta = {
	component: ConfigureWorkspaceStep,
	args: { projects: allProjects },
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Form step for workspace name, short name and the projects to monitor. Auto-generates slug from display name. Shows summary of instance, group, projects and token owner.",
			},
		},
	},
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			preflightResult: { valid: true, username: "admin-user" },
		}),
		(Story) => (
			<div className="w-96">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ConfigureWorkspaceStep>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default state — auto-populates name from group, generates slug, and monitors all projects.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^All projects/u })).toBeChecked();
		await expect(canvas.getByText("All projects", { selector: "dd" })).toBeVisible();
	},
};

/**
 * Two projects chosen: the summary counts them, whichever page they were chosen on.
 */
export const SelectedProjects: Story = {
	args: {
		projects: (
			<ProjectScopeField
				scope="SELECTED"
				onScopeChange={fn()}
				selectedIds={[11, 13]}
				onSelectedChange={fn()}
				choices={{
					status: "ready",
					projects: [
						{ id: 11, name: "API", fullPath: "ls1intum/hephaestus/api" },
						{ id: 12, name: "Docs", fullPath: "ls1intum/hephaestus/docs" },
					],
					page: 1,
					nextPage: 2,
					search: "",
				}}
				onSearch={fn()}
				onPageChange={fn()}
			/>
		),
	},
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			preflightResult: { valid: true, username: "admin-user" },
			projectScope: "SELECTED",
			selectedProjectIds: [11, 13],
		}),
	],
	play: async ({ canvas }) => {
		await expect(canvas.getByText("2 selected projects", { selector: "dd" })).toBeVisible();
		await expect(canvas.getByRole("checkbox", { name: "API" })).toBeChecked();
	},
};

/**
 * Pre-filled with a custom display name and slug already set.
 */
export const PreFilled: Story = {
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			displayName: "My Custom Workspace",
			workspaceSlug: "my-custom-workspace",
			slugManuallyEdited: true,
			preflightResult: { valid: true, username: "admin-user" },
		}),
	],
};

/**
 * Typing inside the slug keeps the caret where the typing is. The field keeps what was typed, and
 * leaving it shows the slug the workspace gets: lowercase, with hyphens for spaces.
 */
export const TypingInsideTheSlug: Story = {
	decorators: [
		withWizardReducer({
			step: 3,
			selectedGroup: defaultGroup,
			displayName: "My Custom Workspace",
			workspaceSlug: "my-custom-workspace",
			slugManuallyEdited: true,
			preflightResult: { valid: true, username: "admin-user" },
		}),
	],
	play: async ({ canvas }) => {
		const slug = canvas.getByRole<HTMLInputElement>("textbox", { name: "URL slug" });
		await userEvent.type(slug, "New ", { initialSelectionStart: 3, initialSelectionEnd: 3 });
		await expect(slug).toHaveValue("my-New custom-workspace");
		await expect(slug.selectionStart).toBe(7);
		await expect(canvas.getByText("my-new-custom-workspace")).toBeVisible();
		await userEvent.tab();
		await expect(slug).toHaveValue("my-new-custom-workspace");
		await expect(slug).toBeValid();
		// Replacing a letter with its capital normalizes to the same slug; the caret still stays.
		await userEvent.type(slug, "N", { initialSelectionStart: 3, initialSelectionEnd: 4 });
		await expect(slug).toHaveValue("my-New-custom-workspace");
		await expect(slug.selectionStart).toBe(4);
	},
};

/**
 * Self-hosted GitLab instance — summary shows custom server URL.
 */
export const SelfHosted: Story = {
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			serverUrl: "https://gitlab.example.com",
			preflightResult: { valid: true, username: "devops-lead" },
		}),
	],
};

/**
 * Group with a long name to test truncation in the summary panel.
 */
export const LongGroupName: Story = {
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: {
				...defaultGroup,
				name: "Very Long Department Name That Should Truncate",
				fullPath: "org/department/sub-team/very-long-group-path-name",
			},
			preflightResult: { valid: true, username: "admin-user" },
		}),
	],
};

/** The short-slug problem, as a reader sees it under the field. */
const shortSlug = "Enter a short name of 3 to 51 characters: a to z, 0 to 9, or hyphens";

/** A group with a short name, as the step opens for it: the slug made from the name is too short. */
const shortName = withWizardState({
	step: 3,
	selectedGroup: makeGroup(2, "QA", "ls1intum/qa"),
	displayName: "QA",
	workspaceSlug: "qa",
	preflightResult: { valid: true, username: "admin-user" },
});

/**
 * A short name makes a slug that is too short. The step opens quietly, and the slug says what to fix
 * once the reader leaves it.
 */
export const SlugTooShortFromName: Story = {
	decorators: [shortName],
	play: async ({ canvas }) => {
		const slug = canvas.getByRole("textbox", { name: "Short name" });
		await expect(slug).toHaveValue("qa");
		await expect(slug).toBeValid();
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();

		await userEvent.click(slug);
		await userEvent.tab();
		await expect(slug).toBeInvalid();
		await expect(slug).toHaveAccessibleDescription(`Used in URLs: /w/qa ${shortSlug}`);
	},
};

/** The same step after the reader pressed "Create workspace": every field shows its problem. */
export const SlugTooShortAfterCreate: Story = {
	args: { showErrors: true },
	decorators: [shortName],
	play: async ({ canvas }) => {
		const slug = canvas.getByRole("textbox", { name: "Short name" });
		await expect(slug).toBeInvalid();
		await expect(slug).toHaveAccessibleDescription(`Used in URLs: /w/qa ${shortSlug}`);
		await expect(canvas.getByRole("alert")).toHaveTextContent(shortSlug);
		await expect(canvas.getByRole("textbox", { name: "Display name" })).toBeValid();
	},
};

/**
 * A name with no letters from a to z makes no slug at all. After "Create workspace", the field says
 * what to enter, and the address under it is only an example.
 */
export const SlugEmptyFromNonLatinNameAfterCreate: Story = {
	args: { showErrors: true },
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: makeGroup(3, "Команда", "ls1intum/komanda"),
			displayName: "Команда",
			workspaceSlug: "",
			preflightResult: { valid: true, username: "admin-user" },
		}),
	],
	play: async ({ canvas }) => {
		const slug = canvas.getByRole("textbox", { name: "Short name" });
		await expect(slug).toHaveValue("");
		await expect(slug).toBeInvalid();
		await expect(slug).toHaveAccessibleDescription(
			`Used in URLs, such as /w/my-workspace. ${shortSlug}`,
		);
	},
};

/** A slug the reader enters stays quiet until they leave the field, then says what to fix. */
export const SlugEnteredNotYetLeft: Story = {
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			displayName: "Hephaestus",
			workspaceSlug: "he",
			slugManuallyEdited: true,
			preflightResult: { valid: true, username: "admin-user" },
		}),
	],
	play: async ({ canvas }) => {
		const slug = canvas.getByRole("textbox", { name: "Short name" });
		await expect(slug).toBeValid();
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
		await expect(slug).toHaveAccessibleDescription("Used in URLs: /w/he");

		await userEvent.click(slug);
		await userEvent.tab();
		await expect(slug).toBeInvalid();
		await expect(slug).toHaveAccessibleDescription(`Used in URLs: /w/he ${shortSlug}`);
	},
};
