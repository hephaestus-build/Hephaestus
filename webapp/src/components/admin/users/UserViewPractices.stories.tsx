import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type {
	PracticeGroup,
	PracticeGroupReviewRun,
	PracticeGroupTrend,
	PracticeTrend,
	UserPracticeSummary,
} from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import type { ReviewRunFeedState } from "@/components/profile/PracticeGroupDetailPage";

import {
	UserViewPractices,
	type UserViewGroupSelection,
	type UserViewPracticesView,
} from "./UserViewPractices";

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

const summary: UserPracticeSummary = {
	groups: [group],
	groupStandings: [
		{
			groupSlug: group.slug,
			groupName: group.name,
			standing: "MIXED",
			guidance: "Keep changes focused on one concern.",
			observations: [],
			sources: [],
		},
	],
	practices: [
		{
			slug: "small-changes",
			name: "Keep changes focused",
			groupSlug: group.slug,
			whyItMatters: "Focused changes are faster to understand.",
		},
	],
	standings: [
		{
			slug: "small-changes",
			name: "Keep changes focused",
			groupSlug: group.slug,
			standing: "MIXED",
			strengths: [],
			toWorkOn: [
				{
					observationId: "00000000-0000-0000-0000-000000000102",
					kind: "OMISSION_GAP",
					origin: "LIVE",
					reviewedWorkId: 902,
					workKind: "scm.pull_request",
					title: "The refactor and the fix arrived together",
					deliveredFeedback: "Land the refactor first, then the fix on top of it.",
				},
			],
		},
	],
};

const support: PracticeTrend["support"] = {
	bundleSize: 5,
	credibilityThreshold: 0.8,
	currentOpportunities: 5,
	previousOpportunities: 5,
	opportunitiesUntilComparable: 0,
	ropeHalfWidth: 0.1,
};
const trend = {
	group: { slug: group.slug, scope: "GROUP", direction: "IMPROVING", support, opportunities: [] },
	practices: [
		{
			slug: "small-changes",
			scope: "PRACTICE",
			direction: "IMPROVING",
			support,
			opportunities: [],
		},
	],
} satisfies PracticeGroupTrend;

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
	observations: [
		{
			observationId: "00000000-0000-0000-0000-000000000102",
			feedbackId: "00000000-0000-0000-0000-000000000103",
			practiceSlug: "small-changes",
			practiceName: "Keep changes focused",
			title: "The refactor and the fix arrived together",
			assessmentStatus: "ASSESSED",
			presence: "PRESENT",
			assessment: "BAD",
			severity: "MAJOR",
		},
	],
};

const retry = fn();

const readyFeed = {
	status: "ready",
	runs: [run],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewRunFeedState;

const readyGroup = (selection: Partial<UserViewGroupSelection> = {}): UserViewPracticesView => ({
	kind: "group",
	selection: { groupSlug: group.slug, ...selection },
	group: { status: "ready", trend, feed: readyFeed },
});

const meta = {
	component: UserViewPractices,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		practices: { status: "ready", summary },
		view: { kind: "overview" },
		skeletonRows: 3,
		onOpenGroup: fn(),
		onSelectPractice: fn(),
		onToggleObservation: fn(),
		onBack: fn(),
	},
} satisfies Meta<typeof UserViewPractices>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "See details about Packaging work for review" }),
		);
		await expect(args.onOpenGroup).toHaveBeenCalledWith(group.slug);
	},
};

export const NoGroups: Story = {
	args: {
		practices: {
			status: "ready",
			summary: { groups: [], groupStandings: [], practices: [], standings: [] },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice groups are configured yet.")).toBeVisible();
	},
};

export const Loading: Story = { args: { practices: { status: "loading" } } };

export const LoadFailed: Story = {
	args: {
		practices: {
			status: "error",
			error: { status: 503, detail: "User view audit is unavailable" },
			onRetry: retry,
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
	},
};

export const GroupLoading: Story = {
	args: {
		view: { kind: "group", selection: { groupSlug: group.slug }, group: { status: "loading" } },
	},
};

export const GroupReady: Story = {
	args: { view: readyGroup() },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("heading", { name: group.name })).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Show review runs for Keep changes focused" }),
		);
		await expect(args.onSelectPractice).toHaveBeenCalledWith("small-changes");
	},
};

export const PracticeSelected: Story = {
	args: { view: readyGroup({ practiceSlug: "small-changes" }) },
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Clear review-run filter for Keep changes focused" }),
		);
		await expect(args.onSelectPractice).toHaveBeenCalledWith(undefined);
	},
};

export const UnknownGroup: Story = {
	args: { view: readyGroup({ groupSlug: "retired-group" }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/does not exist or is not active/)).toBeVisible();
	},
};

export const FeedFailed: Story = {
	args: {
		view: {
			kind: "group",
			selection: { groupSlug: group.slug },
			group: {
				status: "ready",
				trend,
				feed: { status: "error", error: new Error("Gateway timeout"), onRetry: retry },
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: group.name })).toBeVisible();
		await expect(canvas.getByText("Could not load review runs")).toBeVisible();
	},
};

export const ObservationFailed: Story = {
	args: {
		view: {
			kind: "group",
			selection: { groupSlug: group.slug, observationId: "00000000-0000-0000-0000-000000000102" },
			group: {
				status: "ready",
				trend,
				feed: readyFeed,
				observationDetail: { isLoading: false, error: { status: 404, detail: "Not found" } },
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Could not load this observation")).toBeVisible();
	},
};

export const GroupRefused: Story = {
	args: {
		view: {
			kind: "group",
			selection: { groupSlug: group.slug },
			group: {
				status: "error",
				error: {
					status: 403,
					title: "Confirm access",
					detail: "This action requires a recent sign-in.",
					code: "step_up_required",
					maxAgeSeconds: 300,
				},
				onRetry: retry,
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Confirm your sign-in to keep viewing")).toBeVisible();
		await expect(canvas.queryByRole("heading", { name: group.name })).toBeNull();
	},
};
