import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NavMentor } from "./NavMentor";
import { withSidebarFrame } from "./sidebar-story-frame";

/**
 * The Conversations group of the sidebar: one link to the Heph conversation. Shows a chevron on hover.
 */
const meta = {
	component: NavMentor,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"The Conversations group of the sidebar, with one link to the Heph conversation. A chevron appears on hover.",
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
 * Default view of the Conversations group with the hover chevron.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: /Heph/u });
		const mark = link.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(28);
	},
};

/**
 * The Conversations group in its hover state, with the chevron shown.
 */
export const WithHoverState: Story = {
	parameters: {
		docs: {
			description: {
				story: "Hover over the Heph link to see the chevron.",
			},
		},
	},
};
