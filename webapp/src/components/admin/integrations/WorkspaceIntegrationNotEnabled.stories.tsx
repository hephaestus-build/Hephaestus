import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { WorkspaceIntegrationNotEnabled } from "./WorkspaceIntegrationNotEnabled";

const meta = {
	component: WorkspaceIntegrationNotEnabled,
	args: { displayName: "Outline", doc: "outlineIntegration" },
} satisfies Meta<typeof WorkspaceIntegrationNotEnabled>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Names the integration and points at the guide an operator follows to enable it. */
export const Outline: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Outline is not enabled on this instance")).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: /enables the Outline integration/u }),
		).toHaveAttribute("href", expect.stringContaining("admin/connect-outline"));
	},
};

export const Slack: Story = {
	args: { displayName: "Slack", doc: "slackIntegration" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Slack is not enabled on this instance")).toBeVisible();
	},
};
