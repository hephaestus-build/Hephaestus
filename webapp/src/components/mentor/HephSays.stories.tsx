import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { HephSays } from "./HephSays";

const meta = {
	component: HephSays,
	parameters: { layout: "padded" },
	args: {
		intro:
			"I am Heph, the mentor in Hephaestus. I read the work you already do, give you feedback on the practices your project cares about, and talk it through whenever you ask.",
		narration: "Two things first.",
	},
} satisfies Meta<typeof HephSays>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The bubble keeps its width while the narration line changes length under it. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const bubble = canvas.getByText(/I am Heph/u).closest("[data-slot=bubble]");
		const column = bubble?.parentElement;
		await expect(bubble?.getBoundingClientRect().width).toBe(column?.getBoundingClientRect().width);
	},
};
