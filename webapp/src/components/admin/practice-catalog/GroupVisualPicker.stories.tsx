import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Button } from "@/components/ui/button";
import { StatefulPatch } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoOverflowingElement } from "@/test/reflow";

import { GroupVisualPicker, type GroupVisualPickerProps } from "./GroupVisualPicker";

const meta = {
	component: GroupVisualPicker,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		name: "Code quality",
		onChange: fn(),
	},
	render: (args) => (
		<StatefulPatch initial={{ icon: args.icon, color: args.color }}>
			{(visual, patch) => (
				<GroupVisualPicker
					{...args}
					{...visual}
					onChange={(next) => {
						args.onChange(next);
						patch(next);
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof GroupVisualPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Edit the icon and color for Code quality" }),
		);

		await userEvent.click(await screen.findByRole("button", { name: "amber" }));
		await expect(args.onChange).toHaveBeenCalledWith({ color: "amber" });

		await userEvent.click(await screen.findByLabelText("Git branch"));
		await expect(args.onChange).toHaveBeenCalledWith({ icon: "GitBranch" });
	},
};

function DisableWhileOpen(props: GroupVisualPickerProps) {
	const [disabled, setDisabled] = useState(false);
	return (
		<div className="flex items-center gap-2">
			<GroupVisualPicker {...props} disabled={disabled} />
			<Button type="button" variant="outline" onClick={() => setDisabled(true)}>
				Disable picker
			</Button>
		</div>
	);
}

export const DisabledWhileOpen: Story = {
	render: (args) => <DisableWhileOpen {...args} />,
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Edit the icon and color for Code quality" }),
		);
		await userEvent.click(await screen.findByRole("button", { name: "amber" }));
		await expect(args.onChange).toHaveBeenCalledTimes(1);
		await userEvent.click(canvas.getByRole("button", { name: "Disable picker" }));

		await expectGenuinelyDisabled(
			canvas.getByRole("button", { name: "Edit the icon and color for Code quality" }),
		);
		await expect(screen.queryByRole("textbox", { name: "Search icons" })).not.toBeInTheDocument();
		// Counted, not merely absent: nothing in this play could reach a swatch, so a bare
		// "never called" would pass even if disabling did nothing.
		await expect(args.onChange).toHaveBeenCalledTimes(1);
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		// The seven-column icon grid is the only thing here that can outgrow 320px, and it is
		// portalled, so the assertion has to look at the document rather than the canvas.
		await userEvent.click(
			canvas.getByRole("button", { name: "Edit the icon and color for Code quality" }),
		);
		await screen.findByRole("textbox", { name: "Search icons" });
		await expectNoOverflowingElement(document.body);
	},
};
