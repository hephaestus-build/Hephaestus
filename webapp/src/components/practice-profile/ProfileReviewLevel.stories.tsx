import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { groups } from "@/stories/practice-profile-story-mock-data";
import { openProfileReviewDetail } from "@/stories/profile-reviews-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	practiceLevel,
	REVIEW_SELECTION_CLEARED,
	reviewLevel,
	REVIEWS_LEVEL,
	REVIEWS_OF_YOUR_WORK,
} from "./practice-profile-search";
import { type ProfileReviewDetailState, ProfileReviewLevel } from "./ProfileReviewLevel";

const detail = openProfileReviewDetail;
const { review } = detail;

const loaded = { status: "ready", detail } satisfies ProfileReviewDetailState;
const REQUESTED_REVIEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";

const [, focused] = detail.results;
if (!focused) {
	throw new Error("The review carries a second result");
}

const readyWith = (fields: Partial<typeof detail>): ProfileReviewDetailState => ({
	status: "ready",
	detail: { ...detail, ...fields },
});

const practiceTable = () => screen.getByRole("table", { name: "Every practice in this review" });

/** Every state belongs to the review at depth one, after that drawer has entered. */
async function settledReviewPanel() {
	const panel = await waitFor(() => {
		const level = document.querySelector<HTMLElement>(
			'[data-slot="drawer-popup"][data-detail-depth="1"]',
		);
		if (level === null) {
			throw new Error("The review drawer has not opened.");
		}
		return level;
	});
	await expectSettledVisible(panel);
	return panel;
}

/** Always stacked on the reviews list, so every story mounts it as a drawer's second level. */
const meta = {
	component: ProfileReviewLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: {
			behind: [
				{ label: "Practice profile", depth: 0 },
				{ label: REVIEWS_OF_YOUR_WORK, depth: 1 },
			],
			onClose: fn(),
		},
		state: loaded,
		positionOnWork: 2,
		groupOf: () => groups[0],
		onOpenPractice: fn(),
		observationLink: (result) => (
			<DetailStackLink
				aria-label={`Open observation for ${result.practiceName}`}
				entry={practiceLevel(result.practiceSlug)}
				levelSearch={{ practiceTab: "results", observation: result.id }}
			>
				Open observation
			</DetailStackLink>
		),
		tab: "practices",
		onTabChange: fn(),
		filters: {},
		onFiltersChange: fn(),
		onReviewNow: fn(),
	},
	argTypes: { path: { control: false }, requestOutcome: { control: false } },
	render: (args) => (
		<DetailDrawerStack stack={[REVIEWS_LEVEL, reviewLevel(review.id)]} onClose={fn()}>
			{(entry, level) =>
				entry.kind === "review" ? (
					<ProfileReviewLevel {...args} nested={level.nested} />
				) : (
					// A covered level is not inert, so it is a dialog with a name like any other.
					<>
						<DetailDrawerHeader nested={level.nested}>
							<DrawerTitle>{REVIEWS_OF_YOUR_WORK}</DrawerTitle>
						</DetailDrawerHeader>
						<DrawerBody />
					</>
				)
			}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProfileReviewLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledReviewPanel();
		await expect(screen.getByRole("link", { name: /^Open the original/u })).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "Request review" }));
		await expect(args.onReviewNow).toHaveBeenCalledWith(review.item.id);
		await expect(screen.getByText("Requested")).toBeVisible();
		await expect(screen.getByText("Review 2")).toBeVisible();
		await expect(
			screen.getByRole("tab", { name: `Every practice ${detail.results.length}` }),
		).toBeVisible();
		await expect(
			screen.getByRole("tab", { name: `What we noticed ${detail.trace.events.length}` }),
		).toBeVisible();

		await expect(screen.getByRole("list", { name: "Your feedback" })).toBeVisible();

		const table = within(practiceTable());
		for (const result of detail.results) {
			await expect(table.getByRole("button", { name: result.practiceName })).toBeVisible();
		}
		await userEvent.click(table.getByRole("button", { name: "Thin controllers" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("thin-controllers");
		await expectNoPanelOverflow(panel);
	},
};

export const ExactObservationLinks: Story = {
	play: async () => {
		await settledReviewPanel();
		const table = within(practiceTable());
		const link = table.getByRole("link", { name: `Open observation for ${focused.practiceName}` });
		await expect(link).toHaveAttribute("href", expect.stringContaining(focused.id));
		await expect(table.queryByRole("button", { name: /^Show evidence/u })).toBeNull();
	},
};

export const WhatWeNoticed: Story = {
	args: {
		tab: "noticed",
		state: readyWith({
			trace: {
				...detail.trace,
				events: [
					{
						name: "scm.pull_request.ready",
						displayName: "Marked ready for review",
						occurredAt: daysBefore(2),
					},
				],
			},
		}),
	},
	play: async () => {
		await settledReviewPanel();
		await expect(screen.getByRole("heading", { name: "What we noticed" })).toBeVisible();
		await expect(screen.getByText("Marked ready for review")).toBeVisible();
	},
};

export const AskingForAReview: Story = {
	args: { requesting: review.item.id },
	play: async () => {
		await settledReviewPanel();
		await expect(screen.getByRole("button", { name: "Requesting review…" })).toBeDisabled();
	},
};

/**
 * An ask from this review was accepted. The way to the new review stands in this one's place over
 * the list, and leaves this review's practice filter behind.
 */
export const WithRequestedReview: Story = {
	parameters: {
		router: {
			initialUrl: `/?detail=${encodeURIComponent(
				JSON.stringify([detailStackKey(REVIEWS_LEVEL), detailStackKey(reviewLevel(review.id))]),
			)}&reviewPractice=thin-controllers`,
		},
	},
	args: {
		requestOutcome: (
			<ReviewRequestedNotice
				review={
					<DetailStackLink
						entry={reviewLevel(REQUESTED_REVIEW_ID)}
						single
						levelSearch={REVIEW_SELECTION_CLEARED}
					/>
				}
			/>
		),
	},
	play: async () => {
		const panel = within(await settledReviewPanel());
		const link = within(panel.getByRole("note")).getByRole("link", {
			name: "View requested review",
		});
		await expect(levelsOpenedBy(link)).toStrictEqual([
			detailStackKey(REVIEWS_LEVEL),
			`review:${REQUESTED_REVIEW_ID}`,
		]);
		await expect(link.getAttribute("href")).not.toMatch(/admin|reviewPractice/u);
	},
};

export const WithoutAsking: Story = {
	args: { onReviewNow: undefined },
	play: async () => {
		await settledReviewPanel();
		await expect(screen.queryByRole("button", { name: /Request review/u })).toBeNull();
	},
};

export const NothingRecorded: Story = {
	args: { state: readyWith({ results: [], feedback: [] }) },
	play: async () => {
		await settledReviewPanel();
		await expect(
			screen.getByText(
				"This review recorded no observation about you, so there is nothing to list here.",
			),
		).toBeVisible();
		await expect(screen.queryByRole("list", { name: "Your feedback" })).toBeNull();
	},
};

export const RunningWithNothingRecorded: Story = {
	args: {
		state: readyWith({
			review: { ...review, status: "RUNNING", completedAt: null },
			results: [],
			feedback: [],
		}),
	},
	play: async () => {
		const panel = within(await settledReviewPanel());
		await expect(panel.getByText("Created")).toBeVisible();
		await expect(panel.queryByText("Reviewed")).toBeNull();
		await expect(screen.getByText("Running")).toBeVisible();
		await expect(
			screen.getByText("Observations appear here as the review reaches each practice."),
		).toBeVisible();
		await expect(screen.queryByText(/recorded no observation/u)).toBeNull();
	},
};

/**
 * A partial review may have run to the end with a practice failed or withheld, and this view has no
 * stop reason to say which, so its status claims neither. The results it recorded still list.
 */
export const PartialReview: Story = {
	args: { state: readyWith({ review: { ...review, status: "PARTIAL" } }) },
	play: async () => {
		const panel = within(await settledReviewPanel());
		await expect(panel.getByRole("button", { name: "Partial" })).toHaveAccessibleDescription(
			"The review’s observations cover only the work it reached.",
		);
		await expect(panel.queryByText(/did not finish|stopped before/iu)).toBeNull();
		await expect(
			within(practiceTable()).getByRole("button", { name: focused.practiceName }),
		).toBeVisible();
	},
};

export const UntitledItem: Story = {
	args: { state: readyWith({ review: { ...review, item: { ...review.item, title: null } } }) },
	play: async () => {
		await settledReviewPanel();
		await expect(screen.getByRole("heading", { name: review.item.label })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		await settledReviewPanel();
		await expect(practiceTable()).toHaveAttribute("aria-busy", "true");
	},
};

/** A review that is not the reader's, or is gone, answers 404, which offers no retry. */
export const NotFound: Story = {
	args: { state: { status: "error", error: { status: 404 }, onRetry: fn() } },
	play: async () => {
		await settledReviewPanel();
		await expect(screen.getByText("We could not find this review")).toBeVisible();
		await expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledReviewPanel());
	},
};
