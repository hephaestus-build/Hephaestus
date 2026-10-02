import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { MessageText } from "./MessageText";

/**
 * One message part as the reader sees it, in the web app and in the browser extension's Heph panel:
 * the mentor's markdown.
 */
const meta = {
	component: MessageText,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { text: "Your description says what changed. Say **why**, too." },
} satisfies Meta<typeof MessageText>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Plain prose with emphasis. */
export const Prose: Story = {};

/** A confidential client never fetches images from model output. */
export const NoRemoteImages: Story = {
	args: { text: "![Tracking pixel](https://example.test/pixel.png)", allowImages: false },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector("img")).toBeNull();
	},
};

/** Lists, inline code and a fenced block, as a mentor reply carries them. */
export const Markdown: Story = {
	args: {
		text: "Two things to check:\n\n1. The `retry` branch.\n2. The empty state.\n\n```ts\nconst done = true;\n```",
	},
};

