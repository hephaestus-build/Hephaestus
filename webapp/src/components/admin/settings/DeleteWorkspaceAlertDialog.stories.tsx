import type { Meta, StoryObj } from "@storybook/react-vite";
import { type ComponentProps, useState } from "react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { Button } from "@/components/ui/button";

import { DeleteWorkspaceAlertDialog } from "./DeleteWorkspaceAlertDialog";

const meta = {
	component: DeleteWorkspaceAlertDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		workspaceSlug: "acme-corp",
		isDeleting: false,
		onOpenChange: fn(),
		onConfirm: fn(),
	},
} satisfies Meta<typeof DeleteWorkspaceAlertDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

function FocusStory(args: ComponentProps<typeof DeleteWorkspaceAlertDialog>) {
	const [open, setOpen] = useState(false);
	return (
		<>
			<Button onClick={() => setOpen(true)}>Open deletion dialog</Button>
			<DeleteWorkspaceAlertDialog
				{...args}
				open={open}
				onOpenChange={(next) => {
					setOpen(next);
					args.onOpenChange(next);
				}}
			/>
		</>
	);
}

export const TypeToConfirm: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		const gate = dialog.getByLabelText(/to confirm/iu);
		const submit = dialog.getByRole("button", { name: /delete workspace/iu });

		await userEvent.type(gate, " acme-corp");
		await userEvent.click(submit);
		await expect(args.onConfirm).not.toHaveBeenCalled();
		await waitFor(async () => expect(gate).toHaveAttribute("aria-invalid", "true"));
		await expect(gate).toHaveAccessibleDescription(/does not match/iu);
		dialog.getByText(/that does not match/iu);

		await userEvent.clear(gate);
		await userEvent.type(gate, "acme-corp");
		await userEvent.click(submit);
		await expect(args.onConfirm).toHaveBeenCalledTimes(1);
	},
};

export const ComplexContentStartsFocused: Story = {
	render: (args) => <FocusStory {...args} />,
	play: async () => {
		await userEvent.click(screen.getByRole("button", { name: /open deletion dialog/iu }));
		await screen.findByRole("alertdialog");
		await waitFor(async () =>
			expect(screen.getByRole("heading", { name: /permanently delete/iu })).toHaveFocus(),
		);
	},
};

export const Deleting: Story = {
	args: { isDeleting: true },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByRole("button", { name: /deleting/iu })).toBeDisabled();
		await expect(dialog.getByLabelText(/to confirm/iu)).toBeDisabled();
		await userEvent.keyboard("{Escape}");
		await expect(args.onOpenChange).not.toHaveBeenCalled();
	},
};

export const LongWorkspaceSlug: Story = {
	args: { workspaceSlug: "averylongworkspaceslugwithoutnaturalbreakpointsthatmustwraponsmall" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
};
