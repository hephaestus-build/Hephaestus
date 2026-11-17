import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { FeedbackItem } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";

import { AdminFeedbackList } from "./AdminFeedbackList";
import {
	bugReport,
	closedPublicFeedback,
	draftingFeedback,
	filedPrivateFeedback,
	filedPublicFeedback,
	heldFeedback,
	openFeedback,
	privateAllowedFeedback,
	resolvedBugReport,
	resolvedFeedback,
	waitingForModelFeedback,
} from "./fixtures";

const meta = {
	component: AdminFeedbackList,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	args: { pendingIds: new Set<string>(), onTriage: fn(), onFile: fn() },
	argTypes: { pendingIds: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminFeedbackList>;

export default meta;
type Story = StoryObj<typeof meta>;

const ready = (
	items: FeedbackItem[],
	filter: "OPEN" | "IN_FLIGHT" | "ON_GITHUB" | "RESOLVED" | "ALL",
	onPageChange = fn(),
) => ({ status: "ready" as const, items, filter, page: 0, totalPages: 1, onPageChange });

export const Open: Story = {
	args: { state: ready(openFeedback, "OPEN") },
	play: async ({ canvas, args }) => {
		await expect(canvas.getAllByText("Bug")).toHaveLength(1);
		await expect(canvas.getAllByText("Idea")).toHaveLength(1);
		await expect(canvas.getAllByText("General")).toHaveLength(2);
		// An erased account and a submission from outside any workspace still read as sentences.
		await expect(canvas.getByText("Deleted account")).toBeVisible();
		await expect(canvas.getAllByText("No workspace")).toHaveLength(1);
		await expect(canvas.getAllByRole("button", { name: "Mark resolved" })).toHaveLength(4);
		// The first card is the bug report, so the item handed back is the one the reader pressed on.
		const [firstCard] = canvas.getAllByRole("listitem");
		if (firstCard) {
			await userEvent.click(within(firstCard).getByRole("button", { name: "Mark resolved" }));
		}
		await expect(args.onTriage).toHaveBeenCalledWith(bugReport, true);
	},
};

/**
 * Hephaestus is still working, so an admin has nothing to press. The card says what the reporter
 * sees: that the report can become public, and when.
 */
export const OnItsWay: Story = {
	args: { state: ready([draftingFeedback], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Drafting")).toBeVisible();
		await expect(canvas.getByText("Can become public")).toBeVisible();
		await expect(canvas.getByText(/An AI model is drafting the issue\. Started/u)).toBeVisible();
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/, the report goes on as written and does not become public\.$/u,
		);
		await expect(
			canvas.getByText(
				/^Once drafted, it can become a public issue at about \d{1,2}:\d{2} [ap]m unless the reporter keeps it private\.$/u,
			),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Mark resolved" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "File on GitHub" })).not.toBeInTheDocument();
	},
};

/** The model is busy with other work: the card says so, and a private report names no public time. */
export const WaitingForModel: Story = {
	args: { state: ready([waitingForModelFeedback], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Private")).toBeVisible();
		await expect(
			canvas.getByText(
				/The AI model is busy with other work, so the draft waits for it\. Started/u,
			),
		).toBeVisible();
		await expect(canvas.queryByText(/becomes? a public issue/u)).not.toBeInTheDocument();
	},
};

/**
 * A held report shows when it becomes public and the model's draft: the title in view, the text
 * on request.
 */
export const Held: Story = {
	args: { state: ready([heldFeedback], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Public soon")).toBeVisible();
		await expect(canvas.getByText("Can become public")).toBeVisible();
		await expect(
			canvas.getByText(
				/becomes a public issue at about \d{1,2}:\d{2} [ap]m \(in about 4 minutes\) unless the reporter keeps it private or edits it/u,
			),
		).toBeVisible();
		await expect(canvas.getByText(heldFeedback.draftTitle)).toBeVisible();
		await expect(canvas.queryByText(heldFeedback.draftDescription)).not.toBeInTheDocument();
		await userEvent.click(
			canvas.getByRole("button", { name: `The drafted text: ${heldFeedback.draftTitle}` }),
		);
		await expectSettledVisible(await canvas.findByText(heldFeedback.draftDescription));
	},
};

export const NothingOnItsWay: Story = {
	args: { state: ready([], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing on its way")).toBeVisible();
	},
};

/** An admin sees the issue, also a private one, with the words the reporter sees. */
export const OnGitHub: Story = {
	args: { state: ready([filedPublicFeedback, filedPrivateFeedback], "ON_GITHUB") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /public issue #412/u })).toBeVisible();
		await expect(canvas.getByRole("link", { name: /private issue #18/u })).toBeVisible();
		await expect(canvas.getByText("Public")).toBeVisible();
		await expect(canvas.getByText("Private")).toBeVisible();
		await expect(
			canvas.getByText("It may be about a security problem, so it stays private."),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", {
				name: "The issue as filed: Practice page jumps to the top when the filter changes",
			}),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "The issue as filed: Report about session handling" }),
		).toBeVisible();
	},
};

/** A closed issue says how it was closed, in the reporter's words. */
export const Closed: Story = {
	args: { state: ready([closedPublicFeedback], "RESOLVED") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Filed as/u)).toHaveTextContent(
			/Filed as public issue #398 \(opens in a new tab\) · Closed as done$/u,
		);
	},
};

/**
 * A private issue whose reporter allowed a public one after it was filed: the reason recorded then
 * no longer holds, so the card says what does.
 */
export const PrivateAllowedPublic: Story = {
	args: { state: ready([privateAllowedFeedback], "ON_GITHUB") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Private")).toBeVisible();
		await expect(canvas.getByText("The reporter allows a public issue.")).toBeVisible();
		await expect(
			canvas.getByText("It stays private unless a maintainer publishes it."),
		).toBeVisible();
		await expect(canvas.queryByText(/not marked public/u)).not.toBeInTheDocument();
	},
};

export const NothingOnGitHub: Story = {
	args: { state: ready([], "ON_GITHUB") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No open issues")).toBeVisible();
	},
};

/** A saved report that a destination can take now. */
export const CanFile: Story = {
	args: { state: ready([{ ...bugReport, canFile: true }], "OPEN") },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "File on GitHub" }));
		await expect(args.onFile).not.toHaveBeenCalled();
		const confirm = within(await screen.findByRole("alertdialog"));
		await userEvent.click(confirm.getByRole("button", { name: "File on GitHub" }));
		await expect(args.onFile).toHaveBeenCalledOnce();
	},
};

/** Its author was told only about the admins, so it moves as written and no model reads it. */
export const SavedBeforeDisclosure: Story = {
	args: { state: ready([{ ...bugReport, canFile: true, savedBeforeDisclosure: true }], "OPEN") },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "File on GitHub" }));
		const confirm = within(await screen.findByRole("alertdialog"));
		await expectSettledVisible(confirm.getByText(/No AI model reads it/u));
	},
};

export const Filing: Story = {
	args: {
		state: ready([{ ...bugReport, canFile: true }], "OPEN"),
		filingIds: new Set([bugReport.id]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Filing…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Resolved: Story = {
	args: { state: ready(resolvedFeedback, "RESOLVED") },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText(/Resolved by Ada Lovelace/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Reopen" }));
		await expect(args.onTriage).toHaveBeenCalledWith(resolvedBugReport, false);
	},
};

export const Saving: Story = {
	args: {
		state: ready(openFeedback, "OPEN"),
		pendingIds: new Set([bugReport.id]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(canvas.getAllByRole("button", { name: "Mark resolved" })).toHaveLength(3);
	},
};

export const Empty: Story = {
	args: { state: ready([], "OPEN") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing waits for you")).toBeVisible();
	},
};

export const EmptyAll: Story = {
	args: { state: ready([], "ALL") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No feedback yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Error: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
};

/** Full report text, the browser string and the issue copy wrap on a narrow screen. */
export const Reflow: Story = {
	args: { state: ready([heldFeedback, filedPrivateFeedback], "ALL") },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
