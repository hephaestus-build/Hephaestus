import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Button } from "~/components/common/Button";
import { Card } from "~/components/common/Card";

const meta = {
	component: Card,
	tags: ["autodocs"],
	args: {
		title: "Sites",
		description: "The practice review appears only on the sites you allow.",
		children: <p className="text-sm">Content</p>,
	},
} satisfies Meta<typeof Card>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Sites" })).toBeVisible();
	},
};

export const WithAction: Story = {
	args: {
		action: (
			<Button size="sm" variant="outline">
				Refresh
			</Button>
		),
	},
};

export const Untitled: Story = {
	args: { title: undefined, description: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading")).toBeNull();
	},
};
