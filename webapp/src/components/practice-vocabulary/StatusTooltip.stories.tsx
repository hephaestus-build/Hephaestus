import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { settledPopup } from "@/stories/overlay";
import { expectTouchTarget } from "@/test/controls";

import { OUTCOME_DEFS } from "./outcome-defs";
import { StatusIcon } from "./StatusTooltip";

/**
 * A registry entry's icon standing alone, with the registry's sentence behind it. The icon is a
 * button so a keyboard reaches the sentence as a pointer does, and the entry's label names it,
 * since the glyph is all that tells one value from another.
 */
const meta = {
	component: StatusIcon,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { def: OUTCOME_DEFS.NOT_MET },
} satisfies Meta<typeof StatusIcon>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Tab to the icon and the sentence opens; it is the registry's words, label first. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await userEvent.tab();
		const icon = canvas.getByRole("button", { name: "Not met" });
		await expect(icon).toHaveFocus();
		// 14 px of glyph; the pointer target is still the minimum.
		await expectTouchTarget(icon);
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"Not met · The applicable practice standard is not met in the reviewed evidence.",
		);
	},
};
