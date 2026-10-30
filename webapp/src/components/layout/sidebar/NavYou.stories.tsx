import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NavYou } from "./NavYou";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavYou,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { workspaceSlug: "aet", practicesEnabled: true, showConversation: true },
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavYou>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "You" })).toBeVisible();
		const mark = canvas.getByRole("link", { name: "Ask Hephaestus" }).querySelector("svg");
		await expect(mark).toHaveAttribute("aria-hidden", "true");
	},
};
export const ReadOnly: Story = {
	args: { readOnly: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Conversation" })).toHaveAttribute(
			"href",
			"/w/aet/conversation",
		);
		await expect(canvas.queryByRole("link", { name: "Ask Hephaestus" })).not.toBeInTheDocument();
	},
};
export const PracticesOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: "Practice profile" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Activity" })).toBeVisible();
	},
};
export const ConversationHidden: Story = {
	args: { showConversation: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: "Ask Hephaestus" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Practice profile" })).toBeVisible();
	},
};
