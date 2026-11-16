import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { WorkspaceProviderChoice } from "./WorkspaceProviderChoice";

/**
 * The first step of creating a workspace. Each provider card is a link named by the provider it
 * shows, so a speech-input user can say what they see. CI runs with GitLab off, so the GitLab card
 * reaches the accessibility check only through these stories.
 */
const meta = {
	component: WorkspaceProviderChoice,
	args: { providers: ["github", "gitlab"] },
	decorators: [
		(Story) => (
			<div className="w-[42rem] max-w-full">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof WorkspaceProviderChoice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Both providers. Each link is named by the text on its card, the provider first. */
export const GitHubAndGitLab: Story = {
	play: async ({ canvas }) => {
		const gitlab = canvas.getByRole("link", { name: /^GitLab Connect with an access token/u });
		await expect(gitlab).toHaveAttribute("href", "/workspaces/new/gitlab");
		await expect(gitlab).toHaveAccessibleName(
			"GitLab Connect with an access token and select a group to monitor.",
		);
		await expect(
			canvas.getByRole("link", { name: /^GitHub Install the Hephaestus GitHub App/u }),
		).toHaveAttribute("href", "/workspaces/new/github");
	},
};

/** GitLab alone, as on an instance without a GitHub App. */
export const GitLabOnly: Story = {
	args: { providers: ["gitlab"] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: /^GitLab Connect with an access token/u }),
		).toBeVisible();
		await expect(
			canvas.queryByRole("link", { name: /^GitHub Install the Hephaestus GitHub App/u }),
		).toBeNull();
	},
};

export const Loading: Story = { args: { providers: [], loading: true } };

export const NoProviders: Story = {
	args: { providers: [] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No providers are available. Contact an instance admin."),
		).toBeVisible();
	},
};

export const AdminOnly: Story = {
	args: { adminOnly: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Workspace creation is admin-only")).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { providers: [], failed: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the provider options")).toBeVisible();
		await expect(
			canvas.queryByText("No providers are available. Contact an instance admin."),
		).toBeNull();
	},
};
