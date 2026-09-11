import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import type {
	PracticeGroup,
	PracticeGroupReviewObservation,
	PracticeGroupReviewRun,
	PracticeGroupStanding,
} from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { expectNoPageOverflow } from "@/test/reflow";
import { PracticeGroupDetailPage, type ReviewRunFeedState } from "./PracticeGroupDetailPage";

const group: PracticeGroup = {
	id: 1,
	slug: "review-ready-work",
	name: "Packaging work for review",
	description: "Make changes easy to review before asking for feedback.",
	displayOrder: 0,
	visibleInPracticeDashboards: true,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
	icon: "Package",
	color: "blue",
	createdAt: new Date("2026-01-01T00:00:00Z"),
};
const standing: PracticeGroupStanding = {
	groupSlug: group.slug,
	groupName: group.name,
	standing: "MIXED",
	guidance: "Keep changes focused on one concern.",
	observations: [],
	sources: [],
};

const meta = {
	title: "Profile/PracticeGroupDetailPage",
	component: PracticeGroupDetailPage,
	tags: ["autodocs"],
	args: {
		group,
		standing,
		practices: [
			{
				slug: "small-changes",
				name: "Keep changes focused",
				whyItMatters: "Focused changes are faster to understand.",
				standing: "MIXED",
			},
		],
		isLoading: false,
		onBack: fn(),
		onSelectPractice: fn(),
	},
} satisfies Meta<typeof PracticeGroupDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

const observation: PracticeGroupReviewObservation = {
	observationId: "00000000-0000-0000-0000-000000000102",
	feedbackId: "00000000-0000-0000-0000-000000000103",
	practiceSlug: "small-changes",
	practiceName: "Keep changes focused",
	title: "The refactor and the fix arrived together",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MAJOR",
};

const run: PracticeGroupReviewRun = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		id: 902,
		type: "scm.pull_request",
		provider: "GITHUB",
		number: 902,
		title: "Split the practice catalog loader per workspace",
		repositoryName: "ls1intum/Hephaestus",
		url: "https://github.com/ls1intum/Hephaestus/pull/902",
	},
	observations: [observation],
};

const readyFeed = {
	status: "ready",
	runs: [run],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewRunFeedState;

export const Default: Story = {};
export const Loading: Story = { args: { isLoading: true } };
export const Missing: Story = { args: { group: undefined } };
export const Failure: Story = { args: { error: new Error("Unavailable") } };
export const WithReviewRuns: Story = {
	args: {
		feed: readyFeed,
		onToggleObservation: fn(),
		onRespond: fn(),
	},
};
export const MoreToLoad: Story = {
	args: {
		feed: { ...readyFeed, hasMore: true },
	},
};

export const FeedLoading: Story = {
	args: { feed: { status: "loading" }, skeletonRows: 4 },
};
export const FeedFailed: Story = {
	args: {
		feed: { status: "error", error: new Error("Gateway timeout"), onRetry: fn() },
	},
};
export const NarrowedToEmpty: Story = {
	args: { selectedPracticeSlug: "small-changes" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Show every review in this group" }),
		).toBeVisible();
	},
};
export const MobileReflow: Story = {
	args: { feed: readyFeed },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
