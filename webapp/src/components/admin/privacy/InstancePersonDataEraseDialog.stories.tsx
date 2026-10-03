import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { InstancePersonDataEraseDialog } from "./InstancePersonDataEraseDialog";

const meta = {
	component: InstancePersonDataEraseDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		resume: false,
		rowCount: 14,
		storeCount: 5,
		externalDeliveryCount: 0,
		onOpenChange: fn(),
		onConfirm: fn(),
	},
} satisfies Meta<typeof InstancePersonDataEraseDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const TypeToConfirm: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await waitFor(async () => expect(dialog.getByText(/14 rows in 5 stores/u)).toBeVisible());
		const phrase = dialog.getByLabelText(/to confirm/iu);
		const submit = dialog.getByRole("button", { name: "Erase data" });

		await userEvent.type(phrase, "erase");
		await userEvent.click(submit);
		await expect(args.onConfirm).not.toHaveBeenCalled();
		await waitFor(async () => expect(phrase).toHaveAccessibleDescription("Type ERASE exactly."));

		await userEvent.clear(phrase);
		await userEvent.type(phrase, "ERASE");
		await userEvent.click(submit);
		await expect(args.onConfirm).toHaveBeenCalledWith(false);
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
	},
};

export const ProviderCopiesRequireConfirmation: Story = {
	args: { externalDeliveryCount: 2 },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		const removed = dialog.getByRole("checkbox", { name: /removed the 2 feedback copies/iu });
		await userEvent.type(dialog.getByLabelText(/to confirm/iu), "ERASE");
		await userEvent.click(dialog.getByRole("button", { name: "Erase data" }));
		await expect(args.onConfirm).not.toHaveBeenCalled();
		await waitFor(async () =>
			expect(removed).toHaveAccessibleDescription(/does not remove provider comments/iu),
		);

		await userEvent.click(removed);
		await userEvent.click(dialog.getByRole("button", { name: "Erase data" }));
		await expect(args.onConfirm).toHaveBeenCalledWith(true);
	},
};

export const Resume: Story = {
	args: { resume: true },
	play: async () => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await waitFor(async () =>
			expect(dialog.getByRole("heading", { name: "Resume erasure?" })).toBeVisible(),
		);
		await expect(dialog.getByRole("button", { name: "Resume erasure" })).toBeEnabled();
	},
};
