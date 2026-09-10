import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { ReviewRunningBanner } from "./ReviewRunningBanner";

const readyModel = {
	binding: {
		processingLocation: "UNCLASSIFIED",
		purpose: "PRACTICE_REVIEW",
		enabled: true,
		ready: true,
	} as const,
};

const meta = {
	title: "Workspace admin/Practices/Review/Running banner",
	component: ReviewRunningBanner,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		running: { enabled: true, model: { status: "ready", ...readyModel } },
	},
} satisfies Meta<typeof ReviewRunningBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Running: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Reviews are running");
	},
};

export const Checking: Story = {
	args: { running: { enabled: true, model: { status: "loading" } } },
};

export const Unconfirmed: Story = {
	args: { running: { enabled: true, model: { status: "error" } } },
};

export const Blocked: Story = {
	args: { running: { enabled: true, model: { status: "ready" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Reviews can't start");
	},
};

export const Off: Story = {
	args: {
		running: { enabled: false, model: { status: "ready", ...readyModel } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Reviews are off");
	},
};
