import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { AdminAccountView } from "@/api/types.gen";

import { ImpersonateDialog } from "./ImpersonateDialog";

const target: AdminAccountView = {
	id: 2,
	displayName: "Bob User",
	primaryEmail: "bob@example.com",
	appRole: "USER",
	status: "ACTIVE",
};

/**
 * Collects the mandatory audit reason before starting impersonation. The server contract requires a
 * non-empty reason, so the confirm button stays disabled until one is typed.
 */
const meta = {
	component: ImpersonateDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { user: target, onOpenChange: fn(), onConfirm: fn(), isPending: false },
} satisfies Meta<typeof ImpersonateDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The reason gate: confirm is disabled until a reason is entered, then fires with the trimmed text. */
export const ReasonGate: Story = {
	play: async ({ args }) => {
		await screen.findByRole("dialog");
		const confirm = screen.getByRole("button", { name: "Impersonate" });
		await expect(confirm).toBeDisabled();

		await userEvent.type(screen.getByLabelText("Reason"), "  Investigating ticket #1234  ");
		await expect(confirm).toBeEnabled();
		await userEvent.click(confirm);
		await expect(args.onConfirm).toHaveBeenCalledWith(target, "Investigating ticket #1234");
	},
};

/** In flight — the confirm action shows a spinner and is disabled (name gains the spinner label). */
export const Pending: Story = {
	args: { isPending: true },
	play: async () => {
		await screen.findByRole("dialog");
		await expect(screen.getByRole("button", { name: /impersonate/iu })).toBeDisabled();
	},
};
