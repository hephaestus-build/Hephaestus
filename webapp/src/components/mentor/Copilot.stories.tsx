import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor } from "storybook/test";

import type { ChatMessage } from "@/lib/types";
import { expectDismissed, expectSettledVisible } from "@/stories/overlay";

import { Chat } from "./Chat";
import { Copilot } from "./Copilot";
import { CONVERSATION, CONVERSATION_VOTES } from "./fixtures";

/** Long enough to scroll in the panel, so the jump to the end has somewhere to go. */
const LONG_CONVERSATION: ChatMessage[] = [
	...CONVERSATION.map((message) => ({ ...message, id: `earlier-${message.id}` })),
	...CONVERSATION,
];

function chat(messages: ChatMessage[], placeholder?: string) {
	return (
		<Chat
			messages={messages}
			votes={CONVERSATION_VOTES}
			turn={{ kind: "ready" }}
			onMessageSubmit={fn()}
			onStop={fn()}
			onMessageEdit={fn()}
			onCopy={fn()}
			onVote={fn()}
			inputPlaceholder={placeholder}
		/>
	);
}

const meta = {
	component: Copilot,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		onNewChat: fn(),
		onOpenFullChat: fn(),
		children: chat([]),
	},
	render: (args) => (
		<div className="relative h-screen w-full bg-background">
			<main className="p-8">
				<h1 className="text-2xl font-bold">Workspace overview</h1>
				<p className="mt-2 text-muted-foreground">Review recent activity and team progress.</p>
			</main>
			<Copilot {...args} />
		</div>
	),
} satisfies Meta<typeof Copilot>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		const mark = launcher.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(56);
	},
};

export const WithConversation: Story = {
	args: {
		children: chat(CONVERSATION, "Continue the conversation…"),
	},
};

export const Opened: Story = {
	args: WithConversation.args,
	play: async ({ canvas, userEvent }) => {
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		await userEvent.click(launcher);
		const panel = await screen.findByRole("dialog", { name: /Heph/u });
		await expectSettledVisible(panel);
		await expect(panel.contains(document.activeElement)).toBe(true);
		await expect(getComputedStyle(document.body).overflow).toBe("hidden");
		await userEvent.keyboard("{Escape}");
		await expectDismissed();
		await expect(getComputedStyle(document.body).overflow).not.toBe("hidden");
		await expect(launcher).toHaveFocus();
	},
};

export const Busy: Story = {
	args: {
		hasMessages: true,
		children: (
			<Chat
				messages={LONG_CONVERSATION}
				turn={{ kind: "error", failure: "busy" }}
				onMessageSubmit={fn()}
				onStop={fn()}
				onCopy={fn()}
				onReload={fn()}
			/>
		),
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Open Heph, AI mentor" }));
		const panel = await screen.findByRole("dialog", { name: /Heph/u });
		await expectSettledVisible(panel);
		await expect(screen.getByText("Heph is busy", { exact: true })).toBeVisible();
		await expect(screen.getByRole("button", { name: "Try again" })).toBeEnabled();

		const conversation = screen.getByRole("region", { name: "Conversation with Heph" });
		conversation.scrollTop = 0;
		const scroll = screen.getByRole("button", { name: "Scroll to end" });
		await waitFor(async () => expect(scroll).toHaveAttribute("data-active", "true"));
		await expect(scroll.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			screen.getByRole("alert").getBoundingClientRect().top,
		);
		await userEvent.click(scroll);
		await waitFor(async () => expect(scroll).toHaveAttribute("data-active", "false"));
	},
};
