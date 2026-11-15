import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { LlmModelCapabilities } from "./LlmModelCapabilities";

const meta = {
	component: LlmModelCapabilities,
	args: { test: { status: "idle" }, onProbe: fn() },
	parameters: { layout: "padded" },
} satisfies Meta<typeof LlmModelCapabilities>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Unknown: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Not tested.");
		await expect(canvas.getByRole("button", { name: "Test tool choice" })).toBeEnabled();
	},
};
export const Supported: Story = {
	args: { supported: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			"Supported. Reviews can require the model to submit its result through a tool.",
		);
	},
};
export const Unsupported: Story = {
	args: { supported: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			"Not supported. Reviews let the model decide when to call tools.",
		);
	},
};
export const Testing: Story = {
	args: { test: { status: "testing" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Testing tool choice…");
		await expect(canvas.getByRole("button", { name: "Testing…" })).toBeDisabled();
	},
};
export const TestFailed: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("The provider did not answer. The previous result is kept."),
		).toBeVisible();
	},
	args: {
		test: {
			status: "failed",
			message: "The provider did not answer. The previous result is kept.",
		},
	},
};
export const SupportedAfterInconclusiveTest: Story = {
	args: {
		supported: true,
		test: { status: "failed", message: "The test was inconclusive. The previous result is kept." },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Supported.");
		await expect(
			canvas.getByText("The test was inconclusive. The previous result is kept."),
		).toBeVisible();
	},
};
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: { supported: true },
};
