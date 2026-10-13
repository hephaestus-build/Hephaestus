import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	ALL_HIDDEN_DIGEST,
	WAITING_ONLY_DIGEST,
	WEEKLY_DIGEST,
	WITHDRAWN_DIGEST,
	WORKSPACE_SLUG,
} from "./fixtures";
import { WeeklyDigestCard } from "./WeeklyDigestCard";

const meta = {
	component: WeeklyDigestCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { digest: WEEKLY_DIGEST, workspaceSlug: WORKSPACE_SLUG, onReplyInThread: fn() },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof WeeklyDigestCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Feedback that reached the developer in every place, what changed on the profile, and the cards that wait. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("5–11 October 2026")).toBeVisible();
		// Headings, not landmarks: a conversation with many digests would repeat the same two names.
		await expect(canvas.queryByRole("region", { name: "Feedback that reached you" })).toBeNull();
		await expect(canvas.getByRole("heading", { name: "Feedback that reached you" })).toBeVisible();
		const [reached, changed] = canvas.getAllByRole("list");
		if (reached === undefined || changed === undefined) {
			throw new Error("Expected the feedback list and the list of changes");
		}
		await expect(within(reached).getAllByRole("listitem")).toHaveLength(3);
		await expect(within(changed).getByText("Explain significant decisions")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /On your pull request #421/u })).toHaveAttribute(
			"href",
			"/w/aet/feedback/scm.pull_request/421",
		);
		await expect(
			canvas.getByRole("link", {
				name: /On your Practice profile: Name the issue your change closes/u,
			}),
		).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: /In your conversation with Heph/u }),
		).toBeVisible();
		await expect(
			canvas.getByText("Two pieces of feedback are new on your", { exact: false }),
		).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Reply in thread (Weekly digest, 5–11 October 2026)" }),
		);
		await expect(args.onReplyInThread).toHaveBeenCalledOnce();
	},
};

/** Only unread cards: the digest still says where they wait. */
export const WaitingOnly: Story = {
	args: { digest: WAITING_ONLY_DIGEST },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(
			canvas.getByText("One piece of feedback is new on your", { exact: false }),
		).toBeVisible();
	},
};

/** Empty: everything the week held was taken back since. The digest stays, saying so. */
export const AllHidden: Story = {
	args: { digest: ALL_HIDDEN_DIGEST },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Everything from this week was taken back since.")).toBeVisible();
	},
};

/**
 * A withdrawn card keeps only its practice and links nowhere; feedback replaced since stays
 * listed, since it reached the developer that week, and links to the current feedback.
 */
export const WithdrawnItem: Story = {
	args: { digest: WITHDRAWN_DIGEST },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Withdrawn")).toBeVisible();
		await expect(canvas.queryByText("Name the issue your change closes")).toBeNull();
		await expect(canvas.getAllByRole("link")).toHaveLength(1);
		await expect(canvas.getByText(/Newer feedback replaced it/u)).toBeVisible();
	},
};

/** Error: the server could not read the digest. The thread still takes replies. */
export const Unreadable: Story = {
	args: { digest: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not show this weekly digest.")).toBeVisible();
	},
};

/** Inside its own thread, or in a user view, the digest offers no reply and does not invite one. */
export const ReadOnly: Story = {
	args: { onReplyInThread: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Reply in thread/u })).toBeNull();
		await expect(canvas.queryByText(/Reply in a thread/u)).toBeNull();
	},
};
