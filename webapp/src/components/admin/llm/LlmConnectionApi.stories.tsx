import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { LlmConnectionApi } from "./LlmConnectionApi";

const meta = {
	component: LlmConnectionApi,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		connection: { apiProtocol: "openai-responses", purposes: ["PRACTICE_REVIEW", "MENTOR"] },
	},
	render: (args) => (
		<p className="text-sm text-muted-foreground">
			<LlmConnectionApi {...args} />
		</p>
	),
} satisfies Meta<typeof LlmConnectionApi>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ChatApi: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Responses API")).toBeVisible();
		await expect(canvas.getByText(", for Practice reviews and Conversations")).toHaveClass(
			"sr-only",
		);
		await expect(canvasElement.querySelectorAll("[aria-hidden] svg")).toHaveLength(2);
	},
};

export const LongNameReflow: Story = {
	args: {
		connection: { apiProtocol: "anthropic-messages", purposes: ["PRACTICE_REVIEW", "MENTOR"] },
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Messages API (Anthropic)")).toBeVisible();
		await expectNoPageOverflow();
	},
};
