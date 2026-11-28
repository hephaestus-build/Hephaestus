import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { MessageStatus } from "@/components/conversation/MessageStatus";

import { FORMER_MEMBER, QUESTION, YOU } from "./fixtures";
import { TeamMemberMessage } from "./TeamMemberMessage";

const meta = {
	component: TeamMemberMessage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { message: QUESTION },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamMemberMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A teammate's question, under that teammate's own name. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Alan Turing")).toBeVisible();
		await expect(canvas.getByText(/Which user need/u)).toBeVisible();
		await expect(canvas.queryByText("AI", { exact: true })).toBeNull();
	},
};

/** The signed-in member's own message carries their name, as every teammate sees it. */
export const OwnMessage: Story = {
	args: { message: { ...QUESTION, author: YOU } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Ada Lovelace")).toBeVisible();
	},
};

/** An erased member's message keeps its place in the thread, with no name and no avatar. */
export const FormerMember: Story = {
	args: {
		message: {
			...QUESTION,
			author: { ...FORMER_MEMBER, displayName: "Grace Hopper", avatarUrl: "/avatar.png" },
		},
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Former member")).toBeVisible();
		await expect(canvas.queryByText("Grace Hopper")).toBeNull();
		await expect(canvasElement.querySelector("img")).toBeNull();
	},
};

/** A member deleted their message: the empty state of the row. */
export const Deleted: Story = {
	args: { message: { ...QUESTION, text: undefined, deleted: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This message was deleted.")).toBeVisible();
		await expect(canvas.queryByText(/Which user need/u)).toBeNull();
	},
};

/** The question waits for Hephaestus, with the personal conversation's status line and words. */
export const AnswerQueued: Story = {
	render: (args) => (
		<TeamMemberMessage {...args}>
			<MessageStatus state={{ kind: "queued", next: true }} subject="Alan Turing's question" />
		</TeamMemberMessage>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Queued. This is answered next.")).toBeVisible();
	},
};

/** The answer could not finish. The question stays, with the state text the route takes from the registry. */
export const AnswerFailed: Story = {
	render: (args) => (
		<TeamMemberMessage {...args}>
			<MessageStatus
				state={{ kind: "failed", reason: "Hephaestus could not finish this answer." }}
				subject="Alan Turing's question"
			/>
		</TeamMemberMessage>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Which user need/u)).toBeVisible();
		await expect(canvas.getByText("Hephaestus could not finish this answer.")).toBeVisible();
	},
};
