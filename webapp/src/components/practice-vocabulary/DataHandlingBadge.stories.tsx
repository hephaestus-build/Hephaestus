import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { DATA_HANDLING_TIERS } from "./data-handling-defs";
import { DataHandlingBadge } from "./DataHandlingBadge";

/**
 * The badge every model table, picker and binding row shows for a model's declared data handling.
 * The three declared tiers share one neutral tone on purpose: none of them is a warning, and the
 * icon is what tells them apart. Only *Not declared* escalates, because it is an admin to-do.
 */
const meta = {
	title: "Shared/Practice vocabulary/Data handling badge",
	component: DataHandlingBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { tier: "PROVIDER_NOT_KEPT" },
} satisfies Meta<typeof DataHandlingBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const EveryTier: Story = {
	render: (args) => (
		<div className="flex flex-wrap gap-2">
			{DATA_HANDLING_TIERS.map((tier) => (
				<DataHandlingBadge key={tier} {...args} tier={tier} />
			))}
		</div>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Stays in-house")).toBeVisible();
		await expect(canvas.getByText("Provider, nothing kept")).toBeVisible();
		await expect(canvas.getByText("Provider, kept for safety checks")).toBeVisible();
		await expect(canvas.getByText("Not declared")).toBeVisible();
	},
};

export const Dark: Story = {
	...EveryTier,
	globals: { theme: "dark" },
};
