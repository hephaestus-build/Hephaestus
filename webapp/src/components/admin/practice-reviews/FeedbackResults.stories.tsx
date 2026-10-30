import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore, hoursBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import { FeedbackResults } from "./FeedbackResults";
import { PRIVATE_FEEDBACK_TEXT, reviewFeedback } from "./fixtures";

const [firstFeedback] = reviewFeedback;
if (!firstFeedback) {
	throw new Error("The review fixtures must contain a piece of feedback");
}

const postedFeedback = reviewFeedback.find(
	(item) => item.destination === "ON_WORK" && item.state === "DELIVERED",
);
if (!postedFeedback) {
	throw new Error("The review fixtures must contain delivered feedback on the work");
}

/** Storybook resets a spy that appears in `args` between runs, so one instance is enough. */
const clearFilters = fn();
const retry = fn();

const TABLE = { name: "Feedback" } as const;

/** The fixture's feedback spread over a week, about three a day. */
const overAWeek = reviewFeedback.map((item, index) => ({
	...item,
	createdAt: index < 3 ? hoursBefore(index + 1) : daysBefore(Math.floor(index / 3)),
}));

/**
 * The Feedback stream, drawn as the Reviews table: one body per day under its heading, the time and
 * the delivery as an icon, the first line of the text, where it went, the work, who it was for, and
 * what became of it. The whole row opens the feedback's sheet; Track and Trace end it.
 */
const meta = {
	component: FeedbackResults,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: { state: { status: "ready", feedback: reviewFeedback } },
} satisfies Meta<typeof FeedbackResults>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			within(canvas.getByRole("table", TABLE))
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Composed", "Destination", "Work", "Developer", "Delivery", "Actions", "Open"]);
		await expect(canvas.getAllByText("Ready for conversation").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("Delivered in conversation").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("Failed to deliver").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("Replaced by newer").length).toBeGreaterThan(0);
		await expect(canvas.getAllByRole("button")).toHaveLength(reviewFeedback.length + 1);
		await expect(canvas.queryByRole("link", { name: "a review" })).not.toBeInTheDocument();
		await expect(canvas.getAllByRole("link")).toHaveLength(reviewFeedback.length * 2);
		// A withheld row says why in the words the "Why withheld" filter offers, and its precise reason
		// on hover.
		await expect(canvas.getAllByText("The work moved on").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("The developer’s choice").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("Housekeeping").length).toBeGreaterThan(0);
		canvas.getByTitle("The work was already merged, so a note on it would arrive too late.");
		canvas.getByTitle("Feedback to this person is turned off in this workspace.");
		canvas.getByTitle("It said nothing the developer was not already told.");
		// No text is previewed, private or not.
		await expect(canvas.queryByText(/Before this thread scrolls away/u)).toBeNull();
		await expect(canvas.queryByText(PRIVATE_FEEDBACK_TEXT)).toBeNull();
		const names = canvas
			.getAllByRole("link", { name: /^Open feedback /u })
			.map((link) => link.getAttribute("aria-label"));
		await expect(names).toHaveLength(20);
		await expect(
			new Set(
				canvas
					.getAllByRole("link", { name: /^Open feedback /u })
					.map((link) => link.getAttribute("href")),
			).size,
		).toBe(20);
		await expect(names[0]).toMatch(/^Open feedback .+/u);
		// A row opens its feedback over the list rather than navigating away from it.
		await expect(canvas.getAllByRole("link").map(levelsOpenedBy)).toContainEqual([
			`feedback:${firstFeedback.id}`,
		]);
	},
};

/** A long note previews nothing in the row: its text is on its sheet. */
export const LongFeedback: Story = {
	args: {
		state: {
			status: "ready",
			feedback: reviewFeedback.filter((item) => item.bodyTruncated),
		},
	},
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: /^Open feedback /u });
		await expect(canvas.queryByText(/2 issues to tighten/u)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/```/u)).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

/** A week of feedback: one heading per day, newest first, and each row carries only its time. */
export const ManyDays: Story = {
	args: { state: { status: "ready", feedback: overAWeek } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const days = within(canvas.getByRole("table", TABLE)).getAllByRole("rowheader");
		await expect(days.length).toBeGreaterThan(2);
	},
};

/** On a phone the table scrolls sideways in its frame; the page does not. */
export const Narrow: Story = {
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("table", TABLE);
		await expectNoPageOverflow();
	},
};

/** Feedback posted with Windows line endings: the row stays named, and the hidden marker never shows. */
export const PostedWithCrLf: Story = {
	args: {
		state: {
			status: "ready",
			feedback: [
				{
					...postedFeedback,
					bodyPreview:
						"<!-- hephaestus:practice-review:00000000-0000-0000-0000-000000000001 -->\r\nKeep the lookup focused.\r\n",
					bodyTruncated: false,
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Open feedback /u })).toBeVisible();
		await expect(canvas.queryByText(/hephaestus:practice-review/u)).toBeNull();
	},
};

export const WithoutAPreview: Story = {
	args: {
		state: {
			status: "ready",
			feedback: [{ ...firstFeedback, bodyPreview: null, bodyTruncated: false }],
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: /^Open feedback /u });
		await expect(link.getAttribute("aria-label")).toContain(" for ");
	},
};

const githubItem = reviewFeedback.find((item) => item.location === "GITHUB")?.item ?? null;
const gitlabItem = reviewFeedback.find((item) => item.location === "GITLAB")?.item ?? null;

/**
 * One row per location. Each names where its feedback went in words and with the provider's mark: a
 * comment on the GitHub pull request or GitLab merge request, a conversation with Hephaestus in the app or
 * in Slack, the next conversation, or a card on the developer's practice pages.
 */
export const EveryLocation: Story = {
	args: {
		state: {
			status: "ready",
			feedback: [
				{
					...firstFeedback,
					id: "location-github",
					destination: "ON_WORK",
					location: "GITHUB",
					item: githubItem,
				},
				{
					...firstFeedback,
					id: "location-gitlab",
					destination: "ON_WORK",
					location: "GITLAB",
					item: gitlabItem,
				},
				{
					...firstFeedback,
					id: "location-other-work",
					destination: "ON_WORK",
					location: "OTHER_WORK",
					item: null,
				},
				{
					...firstFeedback,
					id: "location-heph",
					destination: "CONVERSATION",
					location: "HEPH",
					state: "DELIVERED",
				},
				{
					...firstFeedback,
					id: "location-slack",
					destination: "CONVERSATION",
					location: "SLACK",
					state: "DELIVERED",
				},
				{
					...firstFeedback,
					id: "location-next-conversation",
					destination: "CONVERSATION",
					location: "NEXT_CONVERSATION",
					state: "READY",
				},
				{
					...firstFeedback,
					id: "location-practice-pages",
					destination: "PRACTICE_PAGE",
					location: "PRACTICE_PAGES",
					state: "DELIVERED",
				},
			],
		},
	},
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("On GitHub")).toBeVisible();
		await expect(canvas.getByText("On GitLab")).toBeVisible();
		canvas.getByText("On the work");
		canvas.getByText("In conversation in the app");
		canvas.getByText("In conversation in Slack");
		canvas.getByText("In conversation");
		canvas.getByText("On the practice page");
		await expectNoPageOverflow();
	},
};

/**
 * Withdrawn feedback that was still ready reads as withdrawn on every destination, in the row and in
 * its accessible name. Feedback withdrawn after delivery keeps its receipt and gains the flag.
 */
export const WithdrawnFeedback: Story = {
	args: {
		state: {
			status: "ready",
			feedback: [
				{
					...firstFeedback,
					id: "ready-conversation",
					destination: "CONVERSATION",
					location: "NEXT_CONVERSATION",
					state: "READY",
					stopReason: null,
					withdrawn: false,
				},
				{
					...firstFeedback,
					id: "withdrawn-ready-conversation",
					destination: "CONVERSATION",
					location: "NEXT_CONVERSATION",
					state: "READY",
					stopReason: null,
					withdrawn: true,
				},
				{
					...firstFeedback,
					id: "withdrawn-ready-practice-pages",
					destination: "PRACTICE_PAGE",
					location: "PRACTICE_PAGES",
					state: "READY",
					stopReason: null,
					withdrawn: true,
				},
				{
					...firstFeedback,
					id: "withdrawn-ready-on-work",
					destination: "ON_WORK",
					location: "GITHUB",
					item: githubItem,
					state: "READY",
					stopReason: null,
					withdrawn: true,
				},
				{
					...firstFeedback,
					id: "withdrawn-delivered-conversation",
					destination: "CONVERSATION",
					location: "HEPH",
					state: "DELIVERED",
					stopReason: null,
					withdrawn: true,
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Ready, then withdrawn")).toHaveLength(3);
		await expect(canvas.getAllByText("Ready for conversation")).toHaveLength(1);
		await expect(canvas.queryByText("Ready for their practice pages")).toBeNull();
		await expect(canvas.queryByText("Ready to send")).toBeNull();
		canvas.getByText("Delivered in conversation");
		// Only the delivered piece needs the separate flag.
		await expect(canvas.getAllByText("Withdrawn")).toHaveLength(1);
		const names = canvas
			.getAllByRole("link", { name: /^Open feedback /u })
			.map((link) => link.getAttribute("aria-label") ?? "");
		await expect(names.filter((name) => name.includes(", Ready, then withdrawn, "))).toHaveLength(
			3,
		);
		await expect(names.filter((name) => name.includes(", Ready for conversation, "))).toHaveLength(
			1,
		);
		await expect(
			names.filter((name) => name.includes(", Delivered in conversation, withdrawn, ")),
		).toHaveLength(1);
	},
};

/** The head stands and the rows wait in their shape, so nothing jumps when they arrive. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", TABLE)).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(
			within(canvas.getByRole("table", TABLE)).getByText("No feedback yet"),
		).toBeVisible();
	},
};

export const FilteredToNothing: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: clearFilters } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await expect(clearFilters).toHaveBeenCalled();
	},
};

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 500, detail: "The feedback index is unavailable." },
			onRetry: retry,
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not load feedback");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalled();
	},
};

/** The delivery icons, the location marks and the day headings keep their contrast on dark. */
export const Dark: Story = {
	globals: { theme: "dark" },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		canvas.getByRole("table", TABLE);
	},
};

/** Tracking one piece of feedback: its row is tinted and its Track pressed, read from the address. */
export const Tracked: Story = {
	parameters: {
		router: {
			initialUrl: `/?feedbackId=${firstFeedback.id}&reviewId=${firstFeedback.reviewId}&selected=true`,
		},
	},
	play: async ({ canvas }) => {
		const [tracked] = canvas.getAllByRole("button", { name: /^Track feedback /u });
		await expect(tracked).toHaveAttribute("aria-pressed", "true");
		await expect(tracked?.closest("tr")).toHaveAttribute("data-variant", "highlighted");
	},
};
