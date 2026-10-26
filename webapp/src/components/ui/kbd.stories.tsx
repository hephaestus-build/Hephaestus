import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, waitFor } from "storybook/test";

import { Button } from "@/components/ui/button";
import { Kbd, KbdGroup } from "@/components/ui/kbd";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/**
 * A key that the reader can press. The app shows it where a control has a keyboard shortcut, such
 * as the sidebar toggle's tooltip. Inside a tooltip the key takes the tooltip's inverted colours.
 */
const meta = {
	component: Kbd,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { children: "⌘B" },
} satisfies Meta<typeof Kbd>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One key, driven by the controls panel. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("⌘B").tagName).toBe("KBD");
	},
};

/** A key with no label still keeps its square, so an icon-only key does not collapse. */
export const Empty: Story = {
	args: { children: undefined },
	play: async ({ canvasElement }) => {
		const key = canvasElement.querySelector("[data-slot=kbd]");
		await expect(key?.getBoundingClientRect().width).toBeGreaterThan(0);
	},
};

/** A chord of several keys reads as one shortcut. */
export const Group: Story = {
	render: (args) => (
		<KbdGroup>
			<Kbd>Ctrl</Kbd>
			<Kbd {...args} />
		</KbdGroup>
	),
	args: { children: "B" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Ctrl").closest("[data-slot=kbd-group]")).toBe(
			canvas.getByText("B").closest("[data-slot=kbd-group]"),
		);
	},
};

/** The sidebar toggle's tooltip names the action and then the key. */
export const InTooltip: Story = {
	render: (args) => (
		<Tooltip>
			<TooltipTrigger render={<Button variant="outline" aria-keyshortcuts="Control+B Meta+B" />}>
				Toggle sidebar
			</TooltipTrigger>
			<TooltipContent side="bottom">
				<span className="flex items-center gap-2">
					Toggle sidebar <Kbd {...args} />
				</span>
			</TooltipContent>
		</Tooltip>
	),
	play: async ({ canvas, userEvent }) => {
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Toggle sidebar" })).toHaveFocus();
		const key = await screen.findByText("⌘B");
		await expect(key.closest("[data-slot=tooltip-content]")).not.toBeNull();
		await waitFor(async () => {
			await expect(key).toBeVisible();
		});
	},
};
