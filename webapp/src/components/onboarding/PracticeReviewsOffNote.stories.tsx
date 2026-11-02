import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { PracticeReviewsOffNote } from "./PracticeReviewsOffNote";

const meta = {
	component: PracticeReviewsOffNote,
	tags: ["autodocs"],
	args: {
		workspaceName: "Acme",
		settingsLink: <a href="/w/acme/admin/settings" />,
	},
} satisfies Meta<typeof PracticeReviewsOffNote>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Practice reviews are off in Acme/u)).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Workspace settings" })).toHaveAttribute(
			"href",
			"/w/acme/admin/settings",
		);
	},
};

export const LongWorkspaceName: Story = {
	args: { workspaceName: "The Applied Education Technologies Research Group of the Department" },
	globals: { viewport: { value: "reflow" } },
};
