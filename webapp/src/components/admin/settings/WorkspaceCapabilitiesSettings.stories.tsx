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

/** Activity has no switch, so it names where each kind of subject is hidden and where it is shown again. */
export const WhereToShowPeopleAgain: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Members" })).toHaveAttribute(
			"href",
			"/w/ase/admin/members",
		);
		await expect(canvas.getByRole("link", { name: "Team setup" })).toHaveAttribute(
			"href",
			"/w/ase/admin/teams",
		);
		await expect(canvas.getByRole("link", { name: "Workspace activity" })).toHaveAttribute(
			"href",
			"/w/ase/workspace-activity",
		);
		await expect(canvas.getByRole("link", { name: "Hidden people" })).toHaveAttribute(
			"href",
			"/w/ase/admin/settings#hidden-people-heading",
		);
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Off\./u)).toBeVisible();
	},
};
