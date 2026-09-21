import type { Meta, StoryObj } from "@storybook/react";

import type { GitLabGroup } from "@/api/types.gen";

import { ConfigureWorkspaceStep } from "./ConfigureWorkspaceStep";
import { makeGroup, withWizardState } from "./stories-utils";

const defaultGroup: GitLabGroup = makeGroup(1, "Hephaestus", "ls1intum/hephaestus", {
	visibility: "public",
});

/**
 * Final configuration step of the GitLab workspace creation wizard.
 * Shows display name and slug inputs with live validation,
 * plus a summary panel of all prior selections.
 */
const meta = {
	component: ConfigureWorkspaceStep,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Form step for workspace name and URL slug. Auto-generates slug from display name. Shows summary of instance, group, and token owner.",
			},
		},
	},
	decorators: [
		withWizardState({
			step: 3,
			selectedGroup: defaultGroup,
			preflightResult: { valid: true, username: "admin-user" },
			serverUrl: "",
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
 * Default state — auto-populates name from group, generates slug.
 */
export const Default: Story = {};

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
