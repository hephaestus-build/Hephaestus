import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { EvidenceRevision } from "./EvidenceRevision";

const meta = {
	title: "Shared/Practice vocabulary/Evidence revision",
	component: EvidenceRevision,
	tags: ["autodocs"],
	args: { revision: "a".repeat(40) },
} satisfies Meta<typeof EvidenceRevision>;
export default meta;
type Story = StoryObj<typeof meta>;

export const PinnedCommit: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("a".repeat(40))).toBeVisible();
	},
};

export const NoRevision: Story = {
	args: { revision: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/Commit/)).not.toBeInTheDocument();
	},
};
