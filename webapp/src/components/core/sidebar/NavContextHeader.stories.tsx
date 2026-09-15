import type { Meta, StoryObj } from "@storybook/react";
import { Link } from "@tanstack/react-router";
import { expect } from "storybook/test";

import { NavContextHeader } from "./NavContextHeader";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavContextHeader,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		title: "Mentor",
		backLink: <Link to="/" />,
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavContextHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const LongTitle: Story = {
	args: { title: "A context whose name is far too long to fit in the sidebar" },
	play: async ({ canvas }) => {
		const button = canvas.getByRole("link");
		await expect(button.scrollWidth).toBeLessThanOrEqual(button.clientWidth);
	},
};
