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
		const names = canvas
			.getAllByRole("heading", { level: 3 })
			.map((heading) => heading.textContent);
		await expect(names).toEqual([
			"Practices",
			"Feedback on the Work",
			"Practice Page",
			"Conversations",
			"Feedback Mode",
			"Your Stack",
		]);
	},
};
