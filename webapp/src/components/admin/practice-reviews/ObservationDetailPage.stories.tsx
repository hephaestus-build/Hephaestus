import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore, hoursBefore } from "@/stories/story-clock";

import { observationDetail, reviewObservationDetail, workspacePractices } from "./fixtures";
import { ObservationDetailPage } from "./ObservationDetailPage";

/** Select complete fixture records by ID to preserve cross-field consistency. */
const meta = {
	component: ObservationDetailPage,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: {
			agentJobId: reviewObservationDetail.agentJobId,
			presence: undefined,
			assessment: undefined,
			severity: undefined,
		},
		observation: reviewObservationDetail,
		isLoading: false,
		error: undefined,
		practices: workspacePractices,
		onChangeValidity: fn(async () => undefined),
		isChangingValidity: false,
	},
} satisfies Meta<typeof ObservationDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		await canvas.findByRole("heading", {
			name: "A cache miss and a permission failure come back as the same 404",
			level: 2,
		});
		canvas.getByRole("link", { name: "in a review" });
		canvas.getByRole("heading", { name: "Why this was raised", level: 3 });
		await expect(canvas.queryByText(/Hephaestus review/u)).not.toBeInTheDocument();
		await expect(canvas.queryByText("AI-generated observation")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Technical details")).not.toBeInTheDocument();
		await expect(canvasElement.querySelector("code")?.textContent).not.toContain("citations");
		await expectNoPageOverflow();
	},
};

export const EvidenceAcrossSources: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText("3 passages from 3 sources.");
		canvas.getByRole("heading", { name: "The code changes", level: 4 });
		canvas.getByRole("heading", { name: "Files and history in the repository", level: 4 });
		canvas.getByRole("heading", { name: "Review threads on the code", level: 4 });
		await expect(canvas.queryByText(/scm\.pull-request/u)).not.toBeInTheDocument();
	},
};

/** Render the delivery channel as text and its outcome as a badge, matching FeedbackRow. */
export const LinkedFeedback: Story = {
	play: async ({ canvas }) => {
		const links = await canvas.findAllByRole("link", { name: "Feedback about this observation" });
		await expect(links).toHaveLength(2);
		await expect(links[0]).toHaveAttribute(
			"href",
			expect.stringContaining("/admin/practices/reviews/delivery/"),
		);
		canvas.getByText("Replaced by newer");
		await expect(await canvas.findAllByText("On the work")).toHaveLength(2);
	},
};

export const SupportsAnotherObservation: Story = {
	args: { observation: observationDetail("77777777-7777-7777-7777-777777777777") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "Feedback this observation supports" });
	},
};

/**
 * Historical feedback stays visible, but the page does not present it as a current claim.
 */
export const FeedbackWasWithheld: Story = {
	args: { observation: observationDetail("bbbbbbbb-2222-2222-2222-222222222222") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "Feedback about this observation" });
		canvas.getByText("Found while reviewing past work, which is measured but never sent.");
		canvas.getByText("This observation is no longer current");
	},
};

export const NoFeedbackComposed: Story = {
	args: { observation: observationDetail("cccccccc-2222-2222-2222-222222222222") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Nothing was said to anybody about this");
	},
};

/**
 * The review cannot tell whether the rules it used are still the current ones, because the work it
 * read is a chat thread with no revision to compare.
 */
export const CannotTellWhichRulesApplied: Story = {
	args: { observation: observationDetail("eeeeeeee-3333-3333-3333-333333333333") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "The thread ends without naming what was chosen",
			level: 2,
		});
		canvas.getByText("The conversation", { selector: "h4" });
	},
};

/**
 * The practice this was judged against says what it is without leaving the page. The card is the
 * half that goes quiet on its own: a page that stops being handed the practice list still renders a
 * perfectly good link.
 */
export const PracticeSaysWhatItIs: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		const errorsCarryContext = workspacePractices.find((p) => p.slug === "errors-carry-context");
		if (!errorsCarryContext) {
			throw new Error("The practice fixtures no longer cover errors-carry-context");
		}
		await userEvent.hover(await canvas.findByRole("link", { name: /Errors carry their context/u }));
		// The card is a portal, so it is looked for on the whole screen rather than in the canvas.
		await screen.findByText(errorsCarryContext.whyItMatters ?? "");
	},
};

/** The reason is required: an invalidation without one is not something the developer could read. */
export const MarkAsIncorrect: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(await canvas.findByRole("button", { name: "Mark as incorrect" }));
		const reason = await screen.findByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		const submit = screen.getAllByRole("button", { name: "Mark as incorrect" }).at(-1);
		await expect(submit).toBeDisabled();
		await userEvent.type(reason, "  The 404 comes from the router, not the cache.  ");
		if (submit) {
			await userEvent.click(submit);
		}
		await expect(args.onChangeValidity).toHaveBeenCalledWith(
			false,
			"The 404 comes from the router, not the cache.",
		);
		await waitFor(async () => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
	},
};

/** A refused change keeps the form open with the reason the admin wrote, ready for another try. */
export const MarkAsIncorrectFails: Story = {
	args: {
		onChangeValidity: fn(async () => {
			throw new Error("409");
		}),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(await canvas.findByRole("button", { name: "Mark as incorrect" }));
		const reason = await screen.findByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		await userEvent.type(reason, "The 404 comes from the router.");
		const submit = screen.getAllByRole("button", { name: "Mark as incorrect" }).at(-1);
		if (submit) {
			await userEvent.click(submit);
		}
		await expect(args.onChangeValidity).toHaveBeenCalledOnce();
		await expect(screen.getByRole("textbox", { name: "Reason" })).toHaveValue(
			"The 404 comes from the router.",
		);
	},
};

/**
 * A comment already on the work could not be changed, so the page says so rather than implying the
 * correction reached it.
 */
export const MarkedIncorrect: Story = {
	args: {
		observation: {
			...reviewObservationDetail,
			invalidations: [
				{
					id: "inv-2",
					reason: "The 404 comes from the router, not the cache.",
					invalidatedAt: hoursBefore(2),
					invalidatedBy: "Ada Admin",
					providerCopy: "INLINE_REMAINS",
				},
				{
					id: "inv-1",
					reason: "Looked like a false positive at first.",
					invalidatedAt: daysBefore(3),
					invalidatedBy: "Ada Admin",
					restorationReason: "The diff does conflate the two after all.",
					restoredAt: daysBefore(2),
					providerCopy: "UPDATED",
				},
			],
		},
	},
	play: async ({ canvas, canvasElement }) => {
		await canvas.findByText("Marked as incorrect");
		const header = canvasElement.querySelector("header");
		if (!header) {
			throw new Error("The page has no header");
		}
		await expect(within(header).getByText("Marked incorrect")).toBeVisible();
		await expect(
			canvas.getByText(
				/Inline comments Hephaestus posted about it are still on the work unchanged/u,
			),
		).toBeVisible();
		await expect(
			canvas.getByText("The correction notice was removed from posted comments."),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Restore observation" })).toBeEnabled();
		const corrections = within(canvas.getByRole("list", { name: "Corrections" }));
		await expect(corrections.getAllByRole("listitem")).toHaveLength(3);
		// The erased restorer is named as such, not left blank.
		await expect(corrections.getByText(/an account that no longer exists/u)).toBeVisible();
	},
};

export const Loading: Story = {
	args: { observation: undefined, isLoading: true },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "Observations" });
		await expect(canvas.queryByText("Couldn't load this observation")).not.toBeInTheDocument();
	},
};

/**
 * The error arrives as a prop, so nothing here depends on a request failing at the right moment. A
 * status-less error is the one that reads "check your connection" — see `QueryErrorAlert`.
 */
export const LoadFailed: Story = {
	args: { observation: undefined, error: { status: 500, detail: "Something went wrong." } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Couldn't load this observation");
	},
};

/**
 * No record and nothing that failed. A deleted observation answers 404 and reads as an error; this
 * is the other case — a fetch that never came back — and it says so rather than guessing a cause.
 */
export const NeverArrived: Story = {
	args: { observation: undefined, error: undefined },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This observation hasn't loaded")).toBeVisible();
		await expect(canvas.queryByText("Couldn't load this observation")).toBeNull();
		await expect(canvas.getByRole("link", { name: "Observations" })).toBeVisible();
	},
};

/** Conversation channel and outcome share an icon; show only the outcome as a badge. */
export const RaisedInConversation: Story = {
	args: { observation: observationDetail("ffffffff-3333-3333-3333-333333333333") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const row = within(await canvas.findByRole("list", { name: "Feedback from this observation" }));
		// Tag names, not text: both strings were on the row before this change too — as two badges.
		// The place is prose in the meta line now, so it is the paragraph itself; the outcome is the
		// badge's own label span. A row that put the place back in a badge would fail here.
		await expect(row.getByText("In conversation").tagName).toBe("P");
		await expect(row.getByText("Delivered in conversation").tagName).toBe("SPAN");
	},
};
