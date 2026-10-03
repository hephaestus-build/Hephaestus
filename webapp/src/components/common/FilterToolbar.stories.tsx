import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { Button } from "@/components/ui/button";
import { expectNoPageOverflow } from "@/stories/reflow";

import { FilterToolbar } from "./FilterToolbar";

const meta = {
	component: FilterToolbar,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		hasFilter: false,
		onReset: fn(),
		children: <span>Filter controls</span>,
	},
} satisfies Meta<typeof FilterToolbar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Filter controls");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

export const Filtered: Story = {
	args: { hasFilter: true },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onReset).toHaveBeenCalledOnce();
	},
};

/** A count and two actions are wider than 320px, so they wrap under the filters instead of past them. */
export const ActionsAtReflowWidth: Story = {
	args: {
		actions: (
			<>
				<p className="text-sm whitespace-nowrap text-muted-foreground">128 observations.</p>
				<Button variant="outline">Export observations as CSV</Button>
				<Button variant="outline">Export observations as JSON</Button>
			</>
		),
	},
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: expectNoPageOverflow,
};
