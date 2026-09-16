import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectAmountRejected } from "@/stories/budget-amount-field";
import { expectSettledVisible } from "@/stories/overlay";

import { BudgetAmountDialog } from "./BudgetAmountDialog";
import type { Fx } from "./fx";

const capDialog = async () => within(await screen.findByRole("dialog"));

const EUR: Fx = {
	currencyCode: "EUR",
	ratePerUsd: 0.878966,
	rateDate: new Date("2026-07-24T00:00:00.000Z"),
	source: "ECB",
};

const meta = {
	component: BudgetAmountDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		title: "Set monthly cap",
		description: "At the cap, that work pauses until the month resets. $0 pauses now.",
		fieldLabel: "Monthly cap (USD)",
		currentValueUsd: 25,
		isPending: false,
		serverError: null,
		onOpenChange: fn(),
		onSubmit: fn(),
	},
} satisfies Meta<typeof BudgetAmountDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithExistingCap: Story = {};

export const Uncapped: Story = {
	args: { currentValueUsd: null },
};

export const Pending: Story = {
	args: { isPending: true },
};

export const ServerRejection: Story = {
	args: { serverError: "Monthly cap must not exceed 99999999.99." },
};

const rejects =
	(typed: string, reason: RegExp): Story["play"] =>
	async ({ args }) =>
		expectAmountRejected({
			fieldLabel: /monthly cap/iu,
			submitLabel: /save cap/iu,
			typed,
			reason,
			onSubmit: args.onSubmit,
		});

export const InvalidEmptyValue: Story = { play: rejects("", /enter an amount/iu) };

/** Rejected in the field, not by a native browser bubble. */
export const InvalidSubCentValue: Story = { play: rejects("25.005", /two decimal places/iu) };

export const InvalidNegativeValue: Story = { play: rejects("-5", /\$0 or more/iu) };

/** The estimate rounds to whole units: to the cent beside a round `$50` it would overclaim. */
export const WithLiveCurrencyHint: Story = {
	args: { currentValueUsd: 50, fx: EUR, isCurrentMonth: true },
	play: async ({ args }) => {
		const dialog = await capDialog();
		await expectSettledVisible(await dialog.findByText(/at today's rate\./u));
		dialog.getByLabelText("approximately 44 euros");

		const input = dialog.getByLabelText(/monthly cap/iu);
		await userEvent.clear(input);
		await userEvent.type(input, "120");
		await expectSettledVisible(await dialog.findByLabelText("approximately 105 euros"));

		// An empty field has nothing to estimate, so the hint leaves rather than reading "≈ €0".
		await userEvent.clear(input);
		await expect(dialog.queryByText(/at today's rate/u)).toBeNull();
		await expect(args.onSubmit).not.toHaveBeenCalled();
	},
};

export const OnAClosedMonthTheHintIsWithdrawn: Story = {
	args: { currentValueUsd: 50, fx: EUR, isCurrentMonth: false },
};

export const WithoutCurrencyHint: Story = {
	play: async () => {
		const dialog = await capDialog();
		await expect(dialog.queryByText(/at today's rate/u)).toBeNull();
	},
};

/** `0` is an amount, not the empty field it resembles — and not a second way to remove a cap. */
export const ZeroPausesImmediately: Story = {
	play: async ({ args }) => {
		const dialog = await capDialog();
		const input = dialog.getByLabelText(/monthly cap/iu);
		await userEvent.clear(input);
		await userEvent.type(input, "0");
		await userEvent.click(dialog.getByRole("button", { name: /save cap/iu }));

		await expect(input).toHaveAttribute("aria-invalid", "false");
		await expect(dialog.queryByRole("alert")).toBeNull();
		dialog.getByRole("button", { name: /remove cap/iu });
		await expect(args.onSubmit).toHaveBeenCalledWith(0);
	},
};
