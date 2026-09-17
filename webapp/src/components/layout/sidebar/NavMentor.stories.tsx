import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NavMentor } from "./NavMentor";
import { withSidebarFrame } from "./sidebar-story-frame";

/**
 * Navigation component for AI Mentor features, providing access to the AI
 * mentoring system. Shows a chevron arrow on hover to indicate expandable behavior.
 */
const meta = {
	component: NavMentor,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Mentor navigation sidebar component with links to AI mentoring features. Displays a chevron on hover to indicate it will transform the sidebar into mentor mode.",
			},
		},
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "aet",
	},
	argTypes: {
		workspaceSlug: {
			control: "text",
			description: "Active workspace slug",
		},
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavMentor>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default view of the mentor navigation with hover chevron indicator.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		// SidebarMenuButton sizes every descendant svg to 16px; the mark is larger than a nav icon.
		const link = canvas.getByRole("link", { name: /Heph/u });
		const mark = link.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(28);
	},
};

/**
 * Shows the mentor navigation in hover state, revealing the chevron arrow.
 */
export const WithHoverState: Story = {
	parameters: {
		docs: {
			description: {
				story:
					"Hover over the mentor item to see the chevron arrow that indicates clicking will transform the sidebar into mentor mode.",
			},
		},
	},
};
