import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { HephIcon } from "./HephIcon";

const meta = {
	component: HephIcon,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { size: 32, animated: true },
	argTypes: { size: { control: { type: "range", min: 12, max: 80, step: 2 } } },
} satisfies Meta<typeof HephIcon>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).not.toBeInTheDocument();
	},
};

export const Streaming: Story = { args: { streaming: true } };

export const Static: Story = { args: { animated: false } };

export const LightAndDark: Story = {
	render: () => (
		<div className="grid overflow-hidden rounded-2xl border sm:grid-cols-2">
			<div className="flex size-48 items-center justify-center bg-background text-muted-foreground">
				<HephIcon size={112} pad={4} animated={false} />
			</div>
			{/* The palette comes from a wrapper, not the theme global, so one page shows both. */}
			<div className="dark flex size-48 items-center justify-center bg-background text-muted-foreground">
				<HephIcon size={112} pad={4} animated={false} />
			</div>
		</div>
	),
};

export const Informative: Story = {
	args: { label: "Heph, AI mentor" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Heph, AI mentor" })).toBeVisible();
	},
};
