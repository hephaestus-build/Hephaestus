import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { minutesBefore } from "@/stories/story-clock";

import { tracedSignals } from "./story-mock-data";
import { SIGNAL_STATE_REASON_LABELS } from "./trace-format";
import { TraceSignalTimeline } from "./TraceSignalTimeline";

/**
 * Everything recorded about one piece of work, oldest first.
 *
 * Each entry says how we came to know, and — when nothing followed — why. The reasons are written
 * in the third person: any member can open this page, and the occurrence is usually somebody else's.
 */
const meta = {
	component: TraceSignalTimeline,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		signals: tracedSignals,
		workspaceSlug: "demo",
		canAdminister: true,
	},
} satisfies Meta<typeof TraceSignalTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every occurrence carries its own explanation; none of them is left as a bare state word. */
export const SignalsExplainThemselves: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Marked ready for review")).toBeVisible();
		await expect(
			canvas.getByText("This work was reviewed too recently; a later change gets its own review."),
		).toBeVisible();
		await expect(canvas.getByText("It waited too long to be picked up.")).toBeVisible();
	},
};

/**
 * A fix link appears only where an admin can actually change the answer. Two of these three refusals
 * are self-healing, and offering a settings page that cannot affect them is worse than offering
 * nothing — the reader would change something to make it stop.
 */
export const RefusalsLinkToTheirFix: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open Review: When and where" })).toHaveAttribute(
			"href",
			"/w/demo/admin/practices/review?section=when-and-where",
		);
		await expect(canvas.getAllByRole("link", { name: /^Open |^Set up /u })).toHaveLength(1);
	},
};

/** The same timeline for a member: the sentence stays, the link into `/admin` does not. */
export const MembersAreOfferedNoAdminLinks: Story = {
	args: { canAdminister: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("This workspace's review settings turned it away."),
		).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /^Open |^Set up /u })).not.toBeInTheDocument();
	},
};

/**
 * Each entry can take focus, so following a "Rests on" link from a practice row lands a keyboard or
 * screen-reader user on the occurrence itself rather than near it.
 */
export const EntriesCanTakeFocus: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getAllByText("New commits pushed")).toHaveLength(2);
		for (const id of ["occurrence-sig-sync-9ab3c410", "occurrence-sig-sync-b71d0a52"]) {
			const target = canvasElement.ownerDocument.getElementById(id);
			if (!target) {
				throw new Error(`No timeline entry with id ${id}`);
			}
			await expect(target).toHaveAttribute("tabindex", "-1");
		}
	},
};

/** Nothing was ever recorded, so no practice was ever asked a question about this work. */
export const NothingRecorded: Story = {
	args: { signals: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing was recorded about this work")).toBeVisible();
	},
};

/** A live update still inside its quiet period: queued, not yet decided, no reason to explain. */
export const DeferredIssueUpdate: Story = {
	args: {
		signals: [
			{
				id: "deferred-issue-update",
				signal: "scm.issue.updated",
				displayName: "Issue metadata changed",
				revision: "digest~latest",
				occurredAt: minutesBefore(0),
				discoveredVia: "EVENT",
				state: "DEFERRED",
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Waiting to see if this keeps changing")).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

export const CoalescedIssueUpdate: Story = {
	args: {
		signals: [
			{
				id: "coalesced-issue-update",
				signal: "scm.issue.updated",
				displayName: "Issue metadata changed",
				revision: "digest~intermediate",
				occurredAt: minutesBefore(2),
				discoveredVia: "EVENT",
				state: "SUPPRESSED",
				stateReason: "COALESCED",
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(`${SIGNAL_STATE_REASON_LABELS.COALESCED}.`)).toBeVisible();
		await expect(canvas.getByText("No review started")).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};
