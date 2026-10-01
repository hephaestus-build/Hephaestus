import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";

const meta = {
	component: WorkspaceCapabilitiesSettings,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { workspaceSlug: "ase", practicesEnabled: true },
} satisfies Meta<typeof WorkspaceCapabilitiesSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^On\./u)).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Review settings" })).toHaveAttribute(
			"href",
			"/w/ase/admin/practices/review",
		);
		await expect(canvas.getByRole("link", { name: "AI models" })).toHaveAttribute(
			"href",
			"/w/ase/admin/models",
		);
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Off\./u)).toBeVisible();
	},
};
