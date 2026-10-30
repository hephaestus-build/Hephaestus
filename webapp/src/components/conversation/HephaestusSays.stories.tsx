import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { HephaestusSays } from "./HephaestusSays";

const meta = {
	component: HephaestusSays,
	parameters: { layout: "padded" },
	args: {
		intro:
			"I am Hephaestus. I read the work you’re already doing, give you feedback on the practices your workspace adopts, and talk it through whenever you ask.",
		narration: "Two things first.",
	},
} satisfies Meta<typeof HephaestusSays>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The bubble keeps its width while the narration line changes length under it. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const bubble = canvas.getByText(/I am Hephaestus/u).closest("[data-slot=bubble]");
		const column = bubble?.parentElement;
		await expect(bubble?.getBoundingClientRect().width).toBe(column?.getBoundingClientRect().width);
	},
};
