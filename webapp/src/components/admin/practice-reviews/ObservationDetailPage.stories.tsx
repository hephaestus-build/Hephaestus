import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, within } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { ObservationDetailPage } from "./ObservationDetailPage";
import { observationDetail, reviewObservationDetail, workspacePractices } from "./story-mock-data";

/**
 * The route fetches the record and the workspace's practice list; this screen only draws what it is
 * handed. Every story opens a record that exists in the fixture, by id — a hand-patched copy of one
 * detail, `{...detail, claimCurrentness: "STALE"}`, can describe a record no review could have
 * produced.
 */
const meta = {
	title: "Workspace admin/Practice reviews/Observation details",
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
		await expect(canvas.queryByText(/Hephaestus review/)).not.toBeInTheDocument();
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
		await expect(canvas.queryByText(/scm\.pull-request/)).not.toBeInTheDocument();
	},
};

/**
 * A row is named by what the feedback is to the observation. Where it went reads as plain text in
 * the meta line and only what became of it is a tag — the same division `FeedbackRow` makes on the
 * Delivery list, so two rows built from one record have one layout.
 *
 * As two badges they said one thing twice: on the conversation lane the place and the outcome
 * resolve to the same icon under "In conversation" and "Delivered in conversation".
 */
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
 * Judged against a version of the practice that has since been edited, which the page has to say so
 * a reader does not measure today's rules against yesterday's answer.
 */
export const FeedbackWasWithheld: Story = {
	args: { observation: observationDetail("bbbbbbbb-2222-2222-2222-222222222222") },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "Feedback about this observation" });
		canvas.getByText("Found while reviewing past work, which is measured but never sent.");
		canvas.getByText("This was judged against an older version of the practice");
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
		if (!errorsCarryContext)
			throw new Error("The practice fixtures no longer cover errors-carry-context");
		await userEvent.hover(await canvas.findByRole("link", { name: /Errors carry their context/ }));
		// The card is a portal, so it is looked for on the whole screen rather than in the canvas.
		await screen.findByText(errorsCarryContext.whyItMatters ?? "");
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

/**
 * The row shape this page used to get wrong. A unit raised in the mentor conversation has a place
 * and an outcome that resolve to the *same* icon, and "Delivered in conversation" is word for word a
 * refinement of "In conversation" — every lane-specific outcome label must begin with the state it
 * refines, so a place badge beside it can only ever restate it.
 *
 * So the place is text and the outcome is the badge, which is also how the Delivery list draws it.
 */
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
