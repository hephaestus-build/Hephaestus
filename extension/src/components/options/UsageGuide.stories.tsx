import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { UsageGuide } from "~/components/options/UsageGuide";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const meta = { component: UsageGuide, tags: ["autodocs"] } satisfies Meta<typeof UsageGuide>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "On the work itself" })).toBeVisible();
		await expect(canvas.getByRole("heading", { name: "Changes are confirmed" })).toBeVisible();
	},
};

export const Narrow: Story = {
	parameters: { reflow: true },
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};
