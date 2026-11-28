import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { REVISIT } from "./fixtures";
import { TeamRevisit } from "./TeamRevisit";

const meta = {
	component: TeamRevisit,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		revisit: REVISIT,
		anchorLink: () => <a href="#version-2" />,
		decisionLink: <a href="#decision" />,
	},
} satisfies Meta<typeof TeamRevisit>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A change the decision named shows in the new version; what stays uncertain is said, not resolved. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Change shown")).toBeVisible();
		await expect(canvas.getByText(/still unverified/u)).toBeVisible();
		await expect(canvas.queryByText(/resolved/iu)).toBeNull();
		await expect(canvas.getByRole("link", { name: "Open the decision" })).toBeVisible();
	},
};

export const StillUncertain: Story = {
	args: { revisit: { ...REVISIT, finding: "STILL_UNCERTAIN" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Still uncertain")).toBeVisible();
	},
};

export const TradeOff: Story = {
	args: { revisit: { ...REVISIT, finding: "TRADE_OFF" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Trade-off")).toBeVisible();
	},
};

export const FullyEvidenced: Story = {
	args: { revisit: { ...REVISIT, finding: "FULLY_EVIDENCED" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Fully evidenced")).toBeVisible();
	},
};
