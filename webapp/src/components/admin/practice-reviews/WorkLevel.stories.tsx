import type { ReviewRef } from "@/api/types.gen";

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { eventLabel } from "@/components/practice-trace/fixtures";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";

import { levelsOpenedBy } from "@/test/detail-stack";
import {
	itemEventsOf,
	mergedPullRequestFacts,
	openIssueFacts,
	pullRequestReviewItem,
	reviewDetailCompleted,
	reviewItem,
	trackerIssue,
} from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { SHEET_SECTIONS } from "./RecordSheet";
import { itemLevel, reviewLevel } from "./review-levels";

import { WorkLevel } from "./WorkLevel";

const retryItem = fn();
const REQUESTED_REVIEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";
const reviewItemEvents = itemEventsOf(reviewDetailCompleted);

const requestedReview: ReviewRef = {
	id: reviewDetailCompleted.id,
	item: reviewItem,
	itemVersion: reviewDetailCompleted.itemVersion,
	kind: "ITEM",
	origin: "REQUEST",
	status: "RUNNING",
	createdAt: reviewDetailCompleted.createdAt,
	completedAt: null,
};

const withheldEvents = reviewItemEvents.slice(0, 1).map((event) => ({
	...event,
	state: "WITHHELD" as const,
	reason: "GATE_WITHHELD" as const,
	reviewId: null,
}));

const meta = {
	component: WorkLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		item: { status: "ready", item: reviewItem },
		events: { status: "ready", events: reviewItemEvents },
		facts: { status: "ready", facts: mergedPullRequestFacts.facts },
		eventLabel,
		onRequestReview: fn(),
	},
	argTypes: { path: { control: false }, eventLabel: { control: false } },
	render: (args) => (
		<InLevelStack entry={itemLevel(reviewItem.id)} path={args.path}>
			{(level) => <WorkLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("work");
		return () => forgetLevelFolds("work");
	},
} satisfies Meta<typeof WorkLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithEvents: Story = {
	play: async ({ args, userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(
			panel.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toStrictEqual([SHEET_SECTIONS.what, SHEET_SECTIONS.state, SHEET_SECTIONS.technical]);
		// One line between each two sections, one section size, and every section folds.
		await expectSectionLines(panelElement, 2);
		await expectSectionHeadings(panelElement);
		await expectSectionsFold(panelElement, userEvent);
		const history = within(panel.getByRole("list", { name: "History" }));
		await expect(history.getAllByRole("listitem")).toHaveLength(reviewItemEvents.length);
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			`${reviewItemEvents.length} events, 1 started a review`,
		);
		history.getByText(/The assigned AI model was unavailable\./u);
		await expect(history.getByRole("link", { name: "Open AI models" })).toHaveAttribute(
			"href",
			expect.stringContaining("/admin/models"),
		);
		panel.getByText("feature/retry-service → main");
		await expect(panel.getByRole("link", { name: /^Failing\b/u })).toHaveAttribute(
			"href",
			expect.stringContaining("/checks"),
		);
		await userEvent.click(panel.getByRole("button", { name: "Request review" }));
		await expect(args.onRequestReview).toHaveBeenCalledOnce();
	},
};

/** One section folds by its heading button and opens again; header and title stay. */
export const FoldsASection: Story = {
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const button = panel.getByRole("button", { name: SHEET_SECTIONS.what });
		const content = document.getElementById(String(button.getAttribute("aria-controls")));
		await expect(button).toHaveAttribute("aria-expanded", "true");
		await expect(content).not.toHaveAttribute("hidden");
		await userEvent.click(button);
		await expect(button).toHaveAttribute("aria-expanded", "false");
		await expect(content).toHaveAttribute("hidden");
		await userEvent.click(button);
		await expect(button).toHaveAttribute("aria-expanded", "true");
		await expect(content).not.toHaveAttribute("hidden");
	},
};

/** A saved fold leaves a failed facts read and Retry visible. */
export const FoldedFactsFail: Story = {
	play: async ({ args, mount, userEvent }) => {
		await mount();
		let panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: SHEET_SECTIONS.what }));
		await expect(panel.getByRole("button", { name: SHEET_SECTIONS.what })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		const retry = fn();
		await mount(
			<InLevelStack entry={itemLevel(reviewItem.id)} path={args.path}>
				{(level) => (
					<WorkLevel
						{...args}
						{...level}
						facts={{ status: "error", error: new Error("Network down"), onRetry: retry }}
					/>
				)}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		await expect(
			panel.getByText("We could not load what the provider reports about this work"),
		).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
		await mount(
			<InLevelStack entry={itemLevel(reviewItem.id)} path={args.path}>
				{(level) => <WorkLevel {...args} {...level} />}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		const restored = panel.getByRole("button", { name: SHEET_SECTIONS.what });
		await expect(restored).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(restored);
		await expect(restored).toHaveAttribute("aria-expanded", "true");
	},
};

/** A saved fold leaves a failed events read and Retry visible. */
export const FoldedEventsFail: Story = {
	play: async ({ args, mount, userEvent }) => {
		await mount();
		let panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: SHEET_SECTIONS.state }));
		await expect(panel.getByRole("button", { name: SHEET_SECTIONS.state })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		const retry = fn();
		await mount(
			<InLevelStack entry={itemLevel(reviewItem.id)} path={args.path}>
				{(level) => (
					<WorkLevel
						{...args}
						{...level}
						events={{ status: "error", error: new Error("Network down"), onRetry: retry }}
					/>
				)}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load the events on this work")).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
		await mount(
			<InLevelStack entry={itemLevel(reviewItem.id)} path={args.path}>
				{(level) => <WorkLevel {...args} {...level} />}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		const restored = panel.getByRole("button", { name: SHEET_SECTIONS.state });
		await expect(restored).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(restored);
		await expect(restored).toHaveAttribute("aria-expanded", "true");
	},
};

/** Upgraded history: each event started a review it no longer links to. The header counts what the badges say. */
export const StartedEventsWithoutReviewLink: Story = {
	args: {
		events: {
			status: "ready",
			events: reviewItemEvents
				.slice(0, 2)
				.map((event) => ({ ...event, state: "STARTED" as const, reviewId: null })),
		},
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			"2 events, 2 started a review",
		);
	},
};

export const RequestBlockedWhileRunning: Story = {
	args: { requestBlocked: "A review of this work is already queued or running." },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const button = panel.getByRole("button", { name: "Request review" });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(button);
		await expect(args.onRequestReview).not.toHaveBeenCalled();
	},
};

export const IssueFacts: Story = {
	args: {
		item: { status: "ready", item: trackerIssue },
		events: { status: "ready", events: [] },
		facts: { status: "ready", facts: openIssueFacts.facts },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("grace");
		await expect(panel.queryByText("Branch")).toBeNull();
		await expect(panel.queryByText("Checks")).toBeNull();
	},
};

export const PartOfAPullRequest: Story = {
	args: {
		item: { status: "ready", item: pullRequestReviewItem },
		events: { status: "ready", events: [] },
		facts: { status: "ready", facts: [] },
		onRequestReview: undefined,
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			levelsOpenedBy(panel.getByRole("link", { name: "Open the work it belongs to" })),
		).toEqual(["item:42"]);
		await expect(panel.queryByRole("button", { name: "Request review" })).toBeNull();
	},
};

export const NoEvents: Story = {
	args: { events: { status: "ready", events: [] } },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText("No event was recorded on this work.");
		await expect(panelElement.querySelector("[data-sheet-status]")).toBeNull();
	},
};

export const ProviderFactsFailed: Story = {
	args: { facts: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText(/what the provider reports about this work/u);
		panel.getByRole("list", { name: "History" });
	},
};

export const Loading: Story = {
	args: {
		item: { status: "loading" },
		reviews: [requestedReview],
		events: { status: "loading" },
		facts: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("heading", { name: SHEET_SECTIONS.what })).toBeNull();
		await expect(panel.queryByRole("link", { name: "Open review" })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		item: { status: "error", error: new Error("Network down"), onRetry: retryItem },
		reviews: [requestedReview],
	},
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("link", { name: "Open review" })).toBeNull();
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		await expect(retryItem).toHaveBeenCalled();
	},
};

export const RequestedReviewRunning: Story = {
	args: {
		events: { status: "ready", events: withheldEvents },
		reviews: [requestedReview],
		requestBlocked: "A review of this work is already queued or running.",
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent("Running");
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("Running");
		history.getByText(/Withheld/u);
		await expect(levelsOpenedBy(history.getByRole("link", { name: "Requested review" }))).toEqual([
			`review:${requestedReview.id}`,
		]);
		await expect(levelsOpenedBy(panel.getByRole("link", { name: "Open review" }))).toEqual([
			`review:${requestedReview.id}`,
		]);
		await expect(panel.getByRole("button", { name: "Request review" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

/**
 * The ask was accepted before any read of the work lists the new review: the sheet still shows the
 * reviews it read, and the way to the new one stands above them.
 */
export const WithRequestedReview: Story = {
	args: {
		reviews: [
			{ ...requestedReview, status: "COMPLETE", completedAt: reviewDetailCompleted.completedAt },
		],
		requestOutcome: (
			<ReviewRequestedNotice
				review={<DetailStackLink entry={reviewLevel(REQUESTED_REVIEW_ID)} single />}
			/>
		),
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		const note = within(panel.getByRole("note"));
		// In this sheet's place, so Back returns to the work.
		await expect(
			levelsOpenedBy(note.getByRole("link", { name: "View requested review" })),
		).toStrictEqual([`review:${REQUESTED_REVIEW_ID}`]);
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent("Complete");
	},
};

export const RequestedReviewComplete: Story = {
	args: {
		events: { status: "ready", events: withheldEvents },
		reviews: [
			{ ...requestedReview, status: "COMPLETE", completedAt: reviewDetailCompleted.completedAt },
		],
	},
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(panelElement.querySelector("[data-sheet-status]")).toHaveTextContent("Complete");
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("Complete");
		history.getByText(/Withheld/u);
		await expect(levelsOpenedBy(history.getByRole("link", { name: "Requested review" }))).toEqual([
			`review:${requestedReview.id}`,
		]);
		await expect(panel.getByRole("button", { name: "Request review" })).not.toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};
