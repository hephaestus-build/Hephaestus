import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { HephaestusLogo } from "~/components/brand/HephaestusLogo";

const meta = {
	component: HephaestusLogo,
	tags: ["autodocs"],
} satisfies Meta<typeof HephaestusLogo>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Heph")).toBeVisible();
		const mark = canvasElement.querySelector("img");
		await expect(mark).toHaveAttribute("alt", "");
		await expect(mark?.naturalWidth).toBeGreaterThan(0);
	},
};
