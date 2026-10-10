import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { CapMeter } from "./CapMeter";

const sharedBudgetMeter = (canvas: StoryContext["canvas"]) =>
	canvas.getByRole("progressbar", { name: "Shared-model budget used" });

const meta = {
	component: CapMeter,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		spendUsd: 12.4,
		capUsd: 50,
		percent: 24.8,
		paused: false,
		label: "Shared-model budget used",
	},
	decorators: [
		(Story) => (
			<div className="max-w-xs">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof CapMeter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(sharedBudgetMeter(canvas)).toHaveAttribute(
			"aria-valuetext",
			"25% used, $12.40 of $50",
		);
	},
};

export const NearCap: Story = {
	args: { spendUsd: 41, capUsd: 50, percent: 82 },
};

export const PausedClampsTheBarButNotTheAnnouncement: Story = {
	args: { spendUsd: 50.4, capUsd: 50, percent: 100.8, paused: true },
	play: async ({ canvas }) => {
		const meter = sharedBudgetMeter(canvas);
		await expect(meter).toHaveAttribute("aria-valuenow", "100");
		await expect(meter).toHaveAttribute("aria-valuetext", "101% used, $50.40 of $50");
	},
};

/** A $0 cap is a cap, not "no cap" — and the meter must not divide by it. */
export const ZeroCap: Story = {
	args: { spendUsd: 0, capUsd: 0, percent: 100, paused: true },
	play: async ({ canvas }) => {
		await expect(sharedBudgetMeter(canvas)).toHaveAttribute(
			"aria-valuetext",
			"100% used, $0.00 of $0",
		);
	},
};

export const ProviderCap: Story = {
	args: {
		spendUsd: 27.6,
		capUsd: 30,
		percent: 92,
		label: "Provider cap used by Acme",
	},
};
