import { CodeReviewIcon } from "@primer/octicons-react";
import type { Meta, StoryObj } from "@storybook/react";
import { FileQuestion, GitPullRequest } from "lucide-react";
import { expect } from "storybook/test";

import { Button } from "@/components/ui/button";

import { EmptyState } from "./EmptyState";

const meta = {
	title: "Shared/Empty state",
	component: EmptyState,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		icon: <FileQuestion className="size-6" />,
		title: "No content found",
		description: "There is no content to display at the moment.",
	},
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithAction: Story = {
	args: {
		icon: <GitPullRequest className="size-6" />,
		title: "No pull requests",
		description: "There are no pull requests to display.",
		action: <Button>Create pull request</Button>,
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Create pull request" })).toHaveFocus();
	},
};

export const WithoutDescription: Story = {
	args: {
		icon: <CodeReviewIcon className="size-6" size={24} />,
		title: "No review activity",
		description: undefined,
	},
};

export const LongDescription: Story = {
	args: {
		className: "w-64",
		description:
			"There is no review activity that counts in this timeframe. Try a wider timeframe to see earlier reviews, or come back after reviewing an open pull request in one of your workspace’s connected repositories.",
		action: <Button>View repositories</Button>,
	},
	play: async ({ canvas }) => {
		const action = canvas.getByRole("button", { name: "View repositories" });
		const card = action.closest('[data-slot="card"]');
		if (!card) {
			throw new Error("Empty state card is missing");
		}
		await expect(action.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			card.getBoundingClientRect().bottom,
		);
	},
};
