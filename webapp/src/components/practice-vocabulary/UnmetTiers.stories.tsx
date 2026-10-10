import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { UnmetTiers } from "./UnmetTiers";

/**
 * The members a needed model misses: one icon per tier, then one phrase in tier order, whatever
 * order the server sent. AI models shows it under a practice in *Used by*, and a practice's panel
 * under the model it needs.
 */
const meta = {
	component: UnmetTiers,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { tiers: ["IN_HOUSE"] },
} satisfies Meta<typeof UnmetTiers>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not set for In-house members")).toBeVisible();
	},
};

/** The phrase follows the page's column order, whatever order the server sent. */
export const SeveralTiers: Story = {
	args: { tiers: ["UNDECLARED", "CLOUD"] },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Not set for Cloud members and members who have not chosen"),
		).toBeVisible();
	},
};

/** The longest phrase wraps beside its icons at 320 px. */
export const EveryTier: Story = {
	args: { tiers: ["CLOUD", "UNDECLARED", "IN_HOUSE"] },
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Not set for In-house and Cloud members and members who have not chosen"),
		).toBeVisible();
		await expectNoPageOverflow();
	},
};

/** A model that misses no one has nothing to say, so nothing renders. */
export const MissesNoOne: Story = {
	args: { tiers: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^Not set for/u)).toBeNull();
	},
};
