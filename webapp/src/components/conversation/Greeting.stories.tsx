import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { WorkProviderContext } from "@/lib/work-provider-context";

import { Greeting } from "./Greeting";

/**
 * The welcome of a conversation that has not started. Its example questions are about the
 * workspace's practices, so each one works before any review of the developer's work.
 */
const meta = {
	component: Greeting,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { onAsk: fn() },
} satisfies Meta<typeof Greeting>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Selecting an example hands its words to the composer; it sends nothing. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const examples = canvas.getByRole("list", { name: "Example questions" });
		await expect(examples).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Which practices apply in this workspace?" }),
		);
		await expect(args.onAsk).toHaveBeenLastCalledWith("Which practices apply in this workspace?");
	},
};

/** On GitLab, the example names merge requests. */
export const GitLabWork: Story = {
	decorators: [
		(Story) => (
			<WorkProviderContext value="GITLAB">
				<Story />
			</WorkProviderContext>
		),
	],
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", {
				name: "What does a merge request that meets this workspace’s practices look like?",
			}),
		).toBeVisible();
	},
};

/** Without a way to ask, the welcome offers no examples. */
export const WithoutExamples: Story = {
	args: { onAsk: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list", { name: "Example questions" })).toBeNull();
	},
};
