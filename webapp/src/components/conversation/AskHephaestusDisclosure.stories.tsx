import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { AskHephaestusDisclosure } from "./AskHephaestusDisclosure";
import { WORKSPACE_NAME } from "./fixtures";

/**
 * The line under every Ask Hephaestus conversation: an AI system answers, this workspace uses it,
 * and it can be wrong. It is real text, not an icon or a badge.
 */
const meta = {
	component: AskHephaestusDisclosure,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { workspaceName: WORKSPACE_NAME },
} satisfies Meta<typeof AskHephaestusDisclosure>;

export default meta;
type Story = StoryObj<typeof meta>;

/** It names the workspace that uses Hephaestus. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				`Hephaestus is an AI system that the ${WORKSPACE_NAME} workspace uses. Its answers can be wrong, so check anything important before you rely on it.`,
			),
		).toBeVisible();
	},
};

/** While the workspace's name is not read yet, it still names the workspace. */
export const WorkspaceNameLoading: Story = {
	args: { workspaceName: undefined },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/^Hephaestus is an AI system that this workspace uses\./u),
		).toBeVisible();
	},
};
