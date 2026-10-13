import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen } from "storybook/test";

import { Button } from "@/components/ui/button";
import { expectSettledVisible } from "@/stories/overlay";

import { DEVELOPER, ready, SLACK_ANSWER, SLACK_QUESTION, WORKSPACE_SLUG } from "./fixtures";
import { HephComposer } from "./HephComposer";
import { HephMessage } from "./HephMessage";
import { HephTimeline } from "./HephTimeline";
import { HephTimelineSkeleton } from "./HephTimelineSkeleton";
import { ThreadGone, ThreadPanel, type ThreadPanelProps } from "./ThreadPanel";

const REPLY = { ...SLACK_ANSWER, parentEntryId: SLACK_QUESTION.id };

function thread() {
	return (
		<HephTimeline
			label="Thread"
			workspaceSlug={WORKSPACE_SLUG}
			state={ready({ entries: [REPLY] })}
			developer={DEVELOPER}
			onCopy={fn()}
			lead={<HephMessage message={SLACK_QUESTION} developer={DEVELOPER} />}
			composer={
				<HephComposer label="Reply in thread" placeholder="Reply in thread" onSend={fn()} />
			}
		/>
	);
}

function OpensFromButton(args: ThreadPanelProps) {
	const [open, setOpen] = useState(false);
	return (
		<div className="flex h-dvh">
			<div className="flex-1 p-6">
				<Button variant="link" onClick={() => setOpen(true)}>
					3 replies
				</Button>
			</div>
			{open && (
				<ThreadPanel
					{...args}
					focusHeading
					onClose={() => {
						args.onClose();
						setOpen(false);
					}}
				/>
			)}
		</div>
	);
}

const meta = {
	component: ThreadPanel,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { id: "thread-panel", onClose: fn(), children: thread() },
	render: (args) => (
		<div className="flex h-dvh">
			<div className="flex-1 p-6 text-muted-foreground">
				The conversation stays beside the thread.
			</div>
			<ThreadPanel {...args} />
		</div>
	),
} satisfies Meta<typeof ThreadPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** From `md`, a column beside the conversation: not a dialog, so the conversation stays usable. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const panel = canvas.getByRole("complementary", { name: "Thread" });
		await expect(panel).toHaveAttribute("id", "thread-panel");
		await userEvent.click(canvas.getByRole("textbox", { name: "Reply in thread" }));
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalledOnce();
	},
};

/** Escape hands focus back to the control that opened the thread. */
export const ReturnsFocus: Story = {
	render: (args) => <OpensFromButton {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		const opener = canvas.getByRole("button", { name: "3 replies" });
		await userEvent.click(opener);
		await expect(canvas.getByRole("heading", { name: "Thread" })).toHaveFocus();
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalledOnce();
		await expect(opener).toHaveFocus();
	},
};

/** Below `md` there is no room beside the conversation, so the thread is a drawer over it. */
export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "mobile" } },
	play: async () => {
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
	},
};

export const Loading: Story = {
	args: { children: <HephTimelineSkeleton rows={2} /> },
};

/** The message the thread replied to was deleted. */
export const DeletedParent: Story = {
	args: { children: <ThreadGone /> },
};
