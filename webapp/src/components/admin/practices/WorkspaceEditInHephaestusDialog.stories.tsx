import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { WorkspaceEditInHephaestusDialog } from "./WorkspaceEditInHephaestusDialog";

const control = {
	repositoryName: "acme/practices",
	confirming: true,
	pending: false,
	onConfirmingChange: fn(),
	onConfirm: fn(),
};

const meta = {
	component: WorkspaceEditInHephaestusDialog,
	tags: ["autodocs"],
	args: {
		practiceName: "Explain the change",
		control,
		finalFocus: true,
	},
	argTypes: { control: { control: false }, finalFocus: { control: false } },
} satisfies Meta<typeof WorkspaceEditInHephaestusDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Confirm: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", {
			name: "Edit “Explain the change” in Hephaestus?",
		});
		await expectSettledVisible(dialog);
		await expect(
			within(dialog).getByText(/Later changes to its folder in acme\/practices no longer reach/u),
		).toBeVisible();
		await userEvent.click(within(dialog).getByRole("button", { name: "Edit in Hephaestus" }));
		await expect(args.control.onConfirm).toHaveBeenCalledOnce();
		// Confirming does not close the dialog: it stays until the practice has reloaded as editable.
		await expect(args.control.onConfirmingChange).not.toHaveBeenCalled();
	},
};

export const Cancel: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await expect(args.control.onConfirmingChange).toHaveBeenCalledWith(false);
		await expect(args.control.onConfirm).not.toHaveBeenCalled();
	},
};

/** The request and the reload of the practice run with the dialog open, so nothing hides the outcome. */
export const MakingItEditable: Story = {
	args: { control: { ...control, pending: true } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await expect(
			within(dialog).getByRole("button", { name: "Making it editable…" }),
		).toHaveAttribute("aria-disabled", "true");
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
	},
};

/** A refusal stays in the dialog, in the server's words, and the reader can try again. */
export const Refused: Story = {
	args: {
		control: {
			...control,
			error: "This practice is already edited in Hephaestus.",
		},
	},
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const alert = within(dialog).getByRole("alert");
		await expect(alert).toHaveTextContent("We could not make it editable");
		await expect(alert).toHaveTextContent("This practice is already edited in Hephaestus.");
		await userEvent.click(within(dialog).getByRole("button", { name: "Edit in Hephaestus" }));
		await expect(args.control.onConfirm).toHaveBeenCalledOnce();
	},
};

export const UnknownRepository: Story = {
	args: { control: { ...control, repositoryName: undefined } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await expect(
			within(dialog).getByText(/its folder in the practice repository no longer reach/u),
		).toBeVisible();
	},
};
