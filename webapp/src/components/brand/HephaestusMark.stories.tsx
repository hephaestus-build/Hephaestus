import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { HephaestusMark } from "./HephaestusMark";

const meta = {
	component: HephaestusMark,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { size: 32 },
	argTypes: { size: { control: { type: "range", min: 12, max: 80, step: 2 } } },
} satisfies Meta<typeof HephaestusMark>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).not.toBeInTheDocument();
	},
};

export const LightAndDark: Story = {
	render: () => (
		<div className="grid overflow-hidden rounded-2xl border sm:grid-cols-2">
			<div className="flex size-48 items-center justify-center bg-background text-muted-foreground">
				<HephaestusMark size={112} pad={4} />
			</div>
			{/* The palette comes from a wrapper, not the theme global, so one page shows both. */}
			<div className="dark flex size-48 items-center justify-center bg-background text-muted-foreground">
				<HephaestusMark size={112} pad={4} />
			</div>
		</div>
	),
};

export const Informative: Story = {
	args: { label: "Ask Hephaestus" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Ask Hephaestus" })).toBeVisible();
	},
};
