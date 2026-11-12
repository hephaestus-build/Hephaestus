import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { AGENT_PURPOSES } from "./agent-purpose-defs";
import { ModelKindMark } from "./ModelKindMark";

const meta = {
	component: ModelKindMark,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { purpose: "PRACTICE_REVIEW", size: "sm", label: "visible" },
} satisfies Meta<typeof ModelKindMark>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Practice reviews")).toBeVisible();
		await expect(
			canvas.getByText("Practice reviews").getBoundingClientRect().width,
		).toBeGreaterThan(1);
	},
};

export const EveryKind: Story = {
	render: (args) => (
		<div className="grid gap-6 sm:grid-cols-2">
			{(["sm", "md"] as const).map((size) => (
				<ul key={size} aria-label={`Size ${size}`} className="grid gap-3 text-sm">
					{AGENT_PURPOSES.map((purpose) => (
						<li key={purpose}>
							<ModelKindMark {...args} purpose={purpose} size={size} />
						</li>
					))}
				</ul>
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const size of ["sm", "md"]) {
			const list = canvas.getByRole("list", { name: `Size ${size}` });
			await expect(list).toHaveTextContent("Practice reviewsConversations");
		}
	},
};

export const NameForScreenReaders: Story = {
	args: { purpose: "MENTOR", label: "sr-only" },
	play: async ({ canvas }) => {
		const name = canvas.getByText("Conversations");
		await expect(name.getBoundingClientRect().width).toBeLessThanOrEqual(1);
		await expect(canvas.getByText("Conversations").closest("[aria-hidden]")).toBeNull();
	},
};

export const Dark: Story = {
	...EveryKind,
	globals: { theme: "dark" },
};
