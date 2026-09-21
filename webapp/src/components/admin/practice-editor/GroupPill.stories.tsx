import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { GroupPill } from "./GroupPill";

const meta = {
	component: GroupPill,
	parameters: { layout: "centered" },
	args: { slug: "review-ready-work", name: "Review-ready work" },
	tags: ["autodocs"],
} satisfies Meta<typeof GroupPill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Sizes: Story = {
	render: (args) => (
		<div className="flex items-center gap-3">
			<GroupPill {...args} size="sm" />
			<GroupPill {...args} size="md" />
			<GroupPill {...args} size="lg" />
		</div>
	),
};

export const Unassigned: Story = {
	args: { slug: undefined, name: undefined },
};

export const Announced: Story = {
	args: { srLabel: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Review-ready work")).toHaveClass("sr-only");
		await expect(canvas.getByTitle("Review-ready work")).not.toHaveAttribute("aria-hidden");
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
