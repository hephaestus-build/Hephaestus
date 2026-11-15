import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { ModelAssignment } from "@/api/types.gen";
import { ReviewReadinessBanner } from "./ReviewReadinessBanner";

const readyModel = {
	binding: {
		dataHandlingTier: "IN_HOUSE",
		purpose: "PRACTICE_REVIEW",
		enabled: true,
		ready: true,
		servedTiers: ["IN_HOUSE", "CLOUD"],
	} satisfies ModelAssignment,
};

const meta = {
	component: ReviewReadinessBanner,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		readiness: { enabled: true, model: { status: "ready", ...readyModel } },
	},
} satisfies Meta<typeof ReviewReadinessBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas }) => {
		const status = canvas.getByRole("note", { name: "Practice review readiness" });
		await expect(status).toHaveTextContent("Reviews can start");
		await expect(status).toHaveTextContent("a review model declared as In-house is ready");
	},
};

export const Checking: Story = {
	args: { readiness: { enabled: true, model: { status: "loading" } } },
};

export const Unconfirmed: Story = {
	args: { readiness: { enabled: true, model: { status: "error" } } },
};

export const Blocked: Story = {
	args: { readiness: { enabled: true, model: { status: "ready" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("note", { name: "Practice review readiness" })).toHaveTextContent(
			"Reviews cannot start",
		);
	},
};

export const Off: Story = {
	args: {
		readiness: { enabled: false, model: { status: "ready", ...readyModel } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("note", { name: "Practice review readiness" })).toHaveTextContent(
			"Reviews are off",
		);
	},
};
