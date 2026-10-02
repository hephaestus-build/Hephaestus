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

/** Three to a row: the fourth tile, open feedback, starts the second. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(/^Most developers here:/u)).toHaveLength(4);
		await expect(canvas.getByText("11 to 21")).toBeVisible();
		await expect(canvas.getAllByText("of your 18 practices")).toHaveLength(2);
		await expect(canvas.getByText("pieces open now")).toBeVisible();
		await expect(
			canvas.getByRole("img", {
				name: "You: 3. The middle half of developers here: 1 to 4.",
			}),
		).toBeVisible();
		await expect(
			canvas.getAllByText("Middle half of 24 developers observed this term"),
		).toHaveLength(4);
	},
};

/** Too few developers observed: the reader's own figures stand, the workspace's say why they do not. */
export const NeedsMoreData: Story = {
	args: { overview: GATED_WORKSPACE },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(4);
		await expect(canvas.queryByRole("img")).toBeNull();
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
