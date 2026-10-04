import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { NotFoundPage } from "./NotFoundPage";

const meta = {
	component: NotFoundPage,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof NotFoundPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Go to home page" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};
