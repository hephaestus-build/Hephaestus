import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { BudgetExhaustedAlert } from "./BudgetExhaustedAlert";

const meta = {
	component: BudgetExhaustedAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { scope: "own", context: "models", workspaceSlug: "acme" },
} satisfies Meta<typeof BudgetExhaustedAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A cap that is reached needs no explanation beyond the reset day, so no docs link. */
export const ProviderCapReached: Story = {
	args: { scope: "own", verdict: "EXHAUSTED" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /no confirmed cost/u })).toBeNull();
	},
};

/** "Unverified measurements" is explained only in the admin guide, so the alert links it. */
export const ProviderCapUnenforceable: Story = {
	args: { scope: "own", verdict: "UNVERIFIABLE" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: "Why usage has no confirmed cost (opens in a new tab)" }),
		).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/ai-providers#missing-prices-and-unverified-measurements$/u),
		);
	},
};

export const SharedBudgetReached: Story = {
	args: { scope: "shared", verdict: "EXHAUSTED" },
};

/** Only an instance admin can price a shared model, so the workspace admin reads why in the admin guide. */
export const SharedBudgetUnverifiable: Story = {
	args: { scope: "shared", verdict: "UNVERIFIABLE" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: "Why usage has no confirmed cost (opens in a new tab)" }),
		).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/ai-providers#missing-prices-and-unverified-measurements$/u),
		);
	},
};

export const ProviderCapReachedOnUsagePage: Story = {
	args: { scope: "own", verdict: "EXHAUSTED", context: "usage" },
};

export const SharedBudgetReachedOnUsagePage: Story = {
	args: { scope: "shared", verdict: "EXHAUSTED", context: "usage" },
};
