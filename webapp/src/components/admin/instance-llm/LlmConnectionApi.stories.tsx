import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { LlmConnectionApi } from "./LlmConnectionApi";

/**
 * The API a connection speaks, under its name in both consoles. The marks show each kind of model
 * the API can serve, and a screen reader hears the kinds once, as a phrase.
 */
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

/** A chat API serves practice reviews and Heph: two marks, one phrase. */
export const ChatApi: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Responses API")).toBeVisible();
		await expect(canvas.getByText(", for Practice reviews and Heph")).toHaveClass("sr-only");
		await expect(canvasElement.querySelectorAll("[aria-hidden] svg")).toHaveLength(2);
	},
};

/** A precompute API serves one kind of model: one mark. */
export const PrecomputeApi: Story = {
	args: { connection: { apiProtocol: "openai-embeddings", purposes: ["PRACTICE_EMBEDDING"] } },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Embeddings API")).toBeVisible();
		await expect(canvas.getByText(", for Embedding model")).toHaveClass("sr-only");
		await expect(canvasElement.querySelectorAll("[aria-hidden] svg")).toHaveLength(1);
	},
};

/** The longest API name stays on one line inside a 320 px viewport. */
export const LongNameReflow: Story = {
	args: { connection: { apiProtocol: "cohere-rerank", purposes: ["PRACTICE_RERANKING"] } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Rerank API (Cohere-compatible)")).toBeVisible();
		await expectNoPageOverflow();
	},
};
