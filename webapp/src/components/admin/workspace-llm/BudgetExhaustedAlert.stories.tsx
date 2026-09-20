import type { Meta, StoryObj } from "@storybook/react";

import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";

const meta = {
	component: BudgetExhaustedAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { scope: "own", context: "models", workspaceSlug: "acme" },
} satisfies Meta<typeof BudgetExhaustedAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ProviderCapReached: Story = {
	args: { scope: "own", verdict: "EXHAUSTED" },
};

export const ProviderCapUnenforceable: Story = {
	args: { scope: "own", verdict: "UNVERIFIABLE", unpricedEventCount: 3 },
};

export const SharedBudgetReached: Story = {
	args: { scope: "shared", verdict: "EXHAUSTED" },
};

export const SharedBudgetUnverifiable: Story = {
	args: { scope: "shared", verdict: "UNVERIFIABLE", unpricedEventCount: 1 },
};

export const ProviderCapReachedOnUsagePage: Story = {
	args: { scope: "own", verdict: "EXHAUSTED", context: "usage" },
};

export const SharedBudgetReachedOnUsagePage: Story = {
	args: { scope: "shared", verdict: "EXHAUSTED", context: "usage" },
};
