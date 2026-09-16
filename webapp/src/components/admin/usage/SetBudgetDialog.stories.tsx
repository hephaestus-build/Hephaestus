import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { expectAmountRejected } from "@/test/budget-amount-field";

import { SetBudgetDialog } from "./SetBudgetDialog";

const meta = {
	component: SetBudgetDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		workspace: {
			workspaceSlug: "example-workspace",
			displayName: "Example Workspace",
			instanceMonthlyBudgetUsd: 25,
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 0,
			events: 118,
			instanceBudgetVerdict: "EXHAUSTED",
			ownProviderBudgetVerdict: "WITHIN" as const,
			ownProviderPaused: false,
			instancePaused: false,
		},
		isPending: false,
		onOpenChange: fn(),
		onSubmit: fn(),
	},
} satisfies Meta<typeof SetBudgetDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithExistingCap: Story = {};

export const Uncapped: Story = {
	args: {
		workspace: {
			workspaceSlug: "sandbox",
			displayName: "Sandbox",
			instanceTotalCostUsd: 0.42,
			ownProviderTotalCostUsd: 0,
			events: 3,
			instanceBudgetVerdict: "WITHIN",
			ownProviderBudgetVerdict: "WITHIN" as const,
			ownProviderPaused: false,
			instancePaused: false,
		},
	},
};

export const Pending: Story = {
	args: { isPending: true },
};

/** The caller names the field and the button; an empty amount is refused with the caller's words. */
export const PassesItsFieldAndButtonCopyThrough: Story = {
	// The dialog is portalled, so {@link expectAmountRejected} queries the document, not the canvas.
	play: async ({ args }) =>
		await expectAmountRejected({
			fieldLabel: /monthly budget/iu,
			submitLabel: /save budget/iu,
			typed: "",
			reason: /enter an amount/iu,
			onSubmit: args.onSubmit,
		}),
};
