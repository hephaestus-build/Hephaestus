import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectSolidFocusLine } from "@/stories/focus";

import { InfoTip } from "./InfoTip";

const meta = {
	component: InfoTip,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { label: "About your figures", children: "Counted over the last 30 days." },
	render: (args) => (
		<p className="flex items-center gap-1 text-sm text-muted-foreground">
			Your figures
			<InfoTip {...args} />
		</p>
	),
} satisfies Meta<typeof InfoTip>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The (i) button has no border, so its focus line is the outline: a solid line in the ring colour
 * under the same half-transparent ring a `Button` wears.
 */
export const KeyboardFocus: Story = {
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "About your figures" });
		await expect(getComputedStyle(button).outlineStyle).toBe("none");
		await userEvent.tab();
		await expectSolidFocusLine(button);
	},
};
