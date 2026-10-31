import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent } from "storybook/test";

import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
	DialogTrigger,
} from "@/components/ui/dialog";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

// Test-only, hidden from Storybook (`!dev`): these stories assert that an overlay is visible the moment
// it opens, which holds only under the story tests' zero animation time (`.storybook/vitest.setup.ts`).
// In Storybook the fade runs, and they would fail. Each asserts in the overlay's first frame, with no
// wait: without that rule, `toBeVisible()` reads an opening overlay as hidden there.
const meta = {
	tags: ["!dev"],
	parameters: { chromatic: { disableSnapshot: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const message = "Turning off silent mode sends the held feedback.";

export const DialogVisibleWhenItOpens: Story = {
	render: () => (
		<Dialog>
			<DialogTrigger>Turn off silent mode</DialogTrigger>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Turn off silent mode?</DialogTitle>
					<DialogDescription>{message}</DialogDescription>
				</DialogHeader>
			</DialogContent>
		</Dialog>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Turn off silent mode" }));
		await expect(screen.getByText(message)).toBeVisible();
	},
};

export const PopoverVisibleWhenItOpens: Story = {
	render: () => (
		<Popover>
			<PopoverTrigger>About silent mode</PopoverTrigger>
			<PopoverContent aria-label="About silent mode">{message}</PopoverContent>
		</Popover>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "About silent mode" }));
		await expect(screen.getByText(message)).toBeVisible();
	},
};
