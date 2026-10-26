import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { PRODUCT_FEATURES } from "./product-features";
import { ProductFeatureList } from "./ProductFeatureList";

const meta = {
	component: ProductFeatureList,
	parameters: { layout: "padded" },
	args: { features: PRODUCT_FEATURES },
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeatureList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(PRODUCT_FEATURES.length);
	},
};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
