import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { ProductFeatureList } from "./ProductFeatureList";

const meta = {
	component: ProductFeatureList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeatureList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(6);
	},
};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
