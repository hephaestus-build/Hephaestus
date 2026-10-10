import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { DATA_HANDLING_TIERS } from "./data-handling-defs";
import { DataHandlingMark } from "./DataHandlingMark";

/**
 * A model's tier beside its name: the tier's own icon, muted, with its name for a screen reader.
 * The same icon heads the tier columns on AI models, so a reader learns it once.
 */
const meta = {
	component: DataHandlingMark,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { tier: "CLOUD", label: "sr-only" },
} satisfies Meta<typeof DataHandlingMark>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Beside a model's name, the icon alone shows; a screen reader hears the tier. */
export const BesideAModel: Story = {
	render: (args) => (
		<span className="flex items-center gap-2 text-sm">
			gpt-5-nano
			<DataHandlingMark {...args} />
		</span>
	),
	play: async ({ canvas }) => {
		const name = canvas.getByText("Cloud");
		await expect(name).toHaveClass("sr-only");
	},
};

/** Every tier with its name on screen, for a list of the members a need misses. */
export const EveryTier: Story = {
	args: { label: "visible" },
	render: (args) => (
		<ul className="grid gap-2 text-sm">
			{DATA_HANDLING_TIERS.map((tier) => (
				<li key={tier}>
					<DataHandlingMark {...args} tier={tier} />
				</li>
			))}
		</ul>
	),
	play: async ({ canvas }) => {
		for (const label of ["In-house", "Cloud", "Not declared"]) {
			await expect(canvas.getByText(label)).toBeVisible();
		}
	},
};
