import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectAmountRejected } from "@/test/budget-amount-field";
import { expectControlOnScreen, expectDialogFitsViewport } from "@/test/reflow";

import { SetOwnProviderBudgetDialog } from "./SetOwnProviderBudgetDialog";

const capDialog = async () => within(await screen.findByRole("dialog"));

/** The workspace's cap on spend through its own provider — their money, so theirs to change. */
const meta = {
	component: SetOwnProviderBudgetDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		currentCapUsd: 25,
		isPending: false,
		serverError: null,
		onOpenChange: fn(),
		onSubmit: fn(),
	},
} satisfies Meta<typeof SetOwnProviderBudgetDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithExistingCap: Story = {};

export const Uncapped: Story = {
	args: { currentCapUsd: null },
};

export const Pending: Story = {
	args: { isPending: true },
};

/** The rejection lands beside the value that caused it, not in a toast that outlives it. */
export const ServerRejectionSitsAtTheField: Story = {
	args: { serverError: "Monthly cap must not exceed 99999999.99." },
	play: async () => {
		const dialog = await capDialog();
		await expect(dialog.getByRole("alert")).toHaveTextContent(/must not exceed/iu);
	},
};

export const PassesItsFieldAndButtonCopyThrough: Story = {
	play: async ({ args }) =>
		await expectAmountRejected({
			fieldLabel: /monthly cap/iu,
			submitLabel: /save cap/iu,
			typed: "",
			reason: /enter an amount/iu,
			onSubmit: args.onSubmit,
		}),
};

/**
 * WCAG 2.2 SC 1.4.10 at 320 px: with a cap in force the footer stacks, so the height bound matters
 * as much as the width.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		const dialog = await capDialog();
		await expectDialogFitsViewport();
		for (const name of [/save cap/iu, /remove cap/iu, /^cancel$/iu, /^close$/iu]) {
			await expectControlOnScreen(dialog.getByRole("button", { name }));
		}
	},
};

/** Removing submits `null` — uncapped, not `0`, which would pause everything instead. */
export const RemoveCap: Story = {
	play: async ({ args }) => {
		const dialog = await capDialog();

		await expect(dialog.getByLabelText(/monthly cap/iu)).toHaveValue(25);

		await userEvent.click(dialog.getByRole("button", { name: /remove cap/iu }));

		await expect(args.onSubmit).toHaveBeenCalledWith(null);
	},
};

export const UncappedOffersNoRemoval: Story = {
	args: { currentCapUsd: null },
	play: async () => {
		const dialog = await capDialog();
		await expect(dialog.getByLabelText(/monthly cap/iu)).toHaveValue(null);
		await expect(dialog.queryByRole("button", { name: /remove cap/iu })).toBeNull();
	},
};
