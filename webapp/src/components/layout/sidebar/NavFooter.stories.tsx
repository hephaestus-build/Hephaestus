import type { Meta, StoryObj } from "@storybook/react";

import { NavFooter } from "./NavFooter";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavFooter,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavFooter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
