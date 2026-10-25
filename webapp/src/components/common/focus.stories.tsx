import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { InfoTip } from "@/components/common/InfoTip";
import { expectSolidFocusLine } from "@/stories/focus";

/**
 * A hand-made control with no border, such as an (i) button, focuses like a `Button`: a solid line
 * in the ring colour as its outline, under the same half-transparent ring. The halo alone does not
 * reach 3:1 against the page.
 */
const meta = {
	parameters: { layout: "padded", controls: { disable: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const BorderlessControlDrawsASolidLine: Story = {
	render: () => (
		<p className="flex items-center gap-1 text-sm text-muted-foreground">
			Your figures
			<InfoTip label="About your figures">Counted over the last 30 days.</InfoTip>
		</p>
	),
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "About your figures" });
		await expect(getComputedStyle(button).outlineStyle).toBe("none");
		await userEvent.tab();
		await expect(button).toHaveFocus();
		await expectSolidFocusLine(button);
	},
};
