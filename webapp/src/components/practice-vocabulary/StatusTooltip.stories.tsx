import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { StatusBadgeWithSentence } from "./StatusTooltip";

/**
 * A registry entry as its badge with the registry's sentence behind it, for any status a surface
 * shows without the sentence beside it. The badge is a button so a keyboard reaches the sentence
 * as a pointer does; `StatusTooltip` underneath is the same tooltip over a bare icon.
 */
const meta = {
	component: StatusBadgeWithSentence,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { def: ASSESSMENT_DEFS.GOOD },
} satisfies Meta<typeof StatusBadgeWithSentence>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Tab to the badge and the sentence opens; it is the registry's words, label first. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: args.def.label })).toHaveFocus();
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(`${args.def.label} · ${args.def.description}`);
	},
};
