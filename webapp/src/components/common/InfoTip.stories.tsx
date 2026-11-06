import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { expectSolidFocusLine } from "@/stories/focus";
import { settledPopup } from "@/stories/overlay";

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

/** The panel can lead with an explanation while its button keeps a short accessible name. */
export const WithLead: Story = {
	args: { lead: "These figures describe your reviewed work." },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: args.label }));
		const panel = within(await settledPopup());
		await expect(panel.getByText("These figures describe your reviewed work.")).toBeVisible();
		await expect(panel.getByText("Counted over the last 30 days.")).toBeVisible();
		await expect(panel.queryByText(args.label, { exact: true })).toBeNull();
	},
};

export const DefaultLead: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: args.label }));
		const panel = within(await settledPopup());
		await expect(panel.getByText(args.label, { exact: true })).toBeVisible();
	},
};
