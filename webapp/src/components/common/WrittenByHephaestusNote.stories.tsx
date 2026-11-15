import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { WRITTEN_BY_HEPHAESTUS, WrittenByHephaestusNote } from "./WrittenByHephaestusNote";

/**
 * The AI label under text that Hephaestus wrote: observations, problems and feedback, on the
 * developer's pages and in practice reviews in workspace administration. It has no data, so it has no
 * loading or error state.
 */
const meta = {
	component: WrittenByHephaestusNote,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof WrittenByHephaestusNote>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Real words that a screen reader reads, not an icon. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(WRITTEN_BY_HEPHAESTUS)).toBeVisible();
	},
};
