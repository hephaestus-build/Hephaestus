import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	GATED_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceTiles } from "./WorkspaceTiles";

const meta = {
	component: WorkspaceTiles,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { overview: ACROSS_WORKSPACE, showWorkspace: true },
} satisfies Meta<typeof WorkspaceTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(/^Most developers here have/u)).toHaveLength(3);
		await expect(canvas.getByText("11 to 21")).toBeVisible();
		await expect(canvas.getAllByText("of your 18 practices")).toHaveLength(2);
	},
};

/** Too few developers observed: the reader's own figures stand, the workspace's say why they do not. */
export const NeedsMoreData: Story = {
	args: { overview: GATED_WORKSPACE },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(3);
	},
};

export const WorkspaceHidden: Story = {
	args: { showWorkspace: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/Most developers here/u)).toBeNull();
		await expect(canvas.getByText("17")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { overview: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading the figures")).toHaveClass("sr-only");
	},
};
