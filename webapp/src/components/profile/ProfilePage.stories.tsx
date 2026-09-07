import type { Meta, StoryObj } from "@storybook/react";
import { endOfISOWeek, formatISO, startOfISOWeek } from "date-fns";
import { fn } from "storybook/test";

import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import { STORY_NOW } from "@/components/common/story-clock";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/test/reflow";

import { PracticeGroupStandingCard } from "./PracticeGroupStandingCard";
import { ProfilePage } from "./ProfilePage";

const now = new Date(STORY_NOW);
const defaultAfter = formatISO(startOfISOWeek(now));
const defaultBefore = formatISO(endOfISOWeek(now));

const meta = {
	component: ProfilePage,
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
} satisfies Meta<typeof ProfilePage>;

export default meta;
type Story = StoryObj<typeof meta>;

const baseMonitorArgs = {
	activityMonitorFilters: { repositoryIds: [], limit: 5 },
	onActivityMonitorFiltersChange: fn(),
};

const practiceGroup: PracticeGroup = {
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

const practiceGroupStanding: PracticeGroupStanding = {
	groupSlug: practiceGroup.slug,
	groupName: practiceGroup.name,
	standing: "MIXED",
	guidance: "Keep changes focused on one concern.",
	observations: [],
	sources: [{ workKind: "scm.pull_request", count: 4 }],
};

const groupPractices: PracticeStanding[] = [
	{
		slug: "small-changes",
		name: "Keep changes focused",
		standing: "STRENGTH",
		strengths: [],
		toWorkOn: [],
	},
	{
		slug: "explain-why",
		name: "Explain why",
		standing: "DEVELOPING",
		strengths: [],
		toWorkOn: [],
	},
];
const practiceGroupStandings = (
	<PracticeGroupStandingCard
		groups={[practiceGroup]}
		standings={{ [practiceGroup.slug]: practiceGroupStanding }}
		practicesByGroup={{ [practiceGroup.slug]: groupPractices }}
		isLoading={false}
		onOpenDetails={fn()}
	/>
);

export const Default: Story = {
	args: {
		...baseMonitorArgs,
		practiceGroupStandings,
		isLoading: false,
		username: "johndoe",
		currUserIsDashboardUser: true,
		workspaceSlug: "aet",
		after: defaultAfter,
		before: defaultBefore,
		onTimeframeChange: fn(),
		profileData: {
			userInfo: {
				id: 1,
				login: "johndoe",
				name: "John Doe",
				avatarUrl: "https://github.com/github.png",
				htmlUrl: "https://github.com/johndoe",
				leaguePoints: 150,
			},
			xpRecord: {
				currentLevel: 5,
				currentLevelXP: 450,
				xpNeeded: 1000,
				totalXP: 5450,
			},
			firstContribution: new Date("2022-05-15T00:00:00Z"),
			contributedRepositories: [
				{
					id: 1,
					name: "Hephaestus",
					nameWithOwner: "ls1intum/Hephaestus",
					description: "A GitHub contribution tracking tool",
					htmlUrl: "https://github.com/ls1intum/Hephaestus",
					hiddenFromContributions: false,
				},
				{
					id: 2,
					name: "Artemis",
					nameWithOwner: "ls1intum/Artemis",
					description: "Interactive learning platform",
					htmlUrl: "https://github.com/ls1intum/Artemis",
					hiddenFromContributions: false,
				},
			],
		},
	},
};

export const Loading: Story = {
	args: {
		...baseMonitorArgs,
		isLoading: true,
		username: "johndoe",
		profileData: undefined,
		currUserIsDashboardUser: true,
		workspaceSlug: "aet",
		after: defaultAfter,
		before: defaultBefore,
		onTimeframeChange: fn(),
	},
};

export const ErrorState: Story = {
	args: {
		...baseMonitorArgs,
		isLoading: false,
		error: new Error("Request failed with status 503"),
		username: "johndoe",
		profileData: undefined,
		currUserIsDashboardUser: true,
		workspaceSlug: "aet",
		after: defaultAfter,
		before: defaultBefore,
		onTimeframeChange: fn(),
	},
};

export const Empty: Story = {
	args: {
		...baseMonitorArgs,
		isLoading: false,
		username: "emptydoe",
		currUserIsDashboardUser: true,
		workspaceSlug: "aet",
		after: defaultAfter,
		before: defaultBefore,
		onTimeframeChange: fn(),
		profileData: {
			userInfo: {
				id: 3,
				login: "emptydoe",
				name: "Empty Doe",
				avatarUrl: "https://github.com/octocat.png",
				htmlUrl: "https://github.com/emptydoe",
				leaguePoints: 0,
			},
			xpRecord: {
				currentLevel: 1,
				currentLevelXP: 0,
				xpNeeded: 150,
				totalXP: 0,
			},
			firstContribution: new Date("2023-10-15T00:00:00Z"),
			contributedRepositories: [],
		},
	},
};

export const MobileReflow: Story = {
	...Default,
	args: {
		...Default.args,
		username: "avery-long-provider-username-that-must-remain-readable",
		profileData: {
			...Default.args.profileData,
			userInfo: {
				...Default.args.profileData?.userInfo,
				id: 99,
				login: "avery-long-provider-username-that-must-remain-readable",
				name: "A deliberately long contributor name that wraps cleanly",
				avatarUrl: "https://github.com/github.png",
				htmlUrl: "https://github.com/avery-long-provider-username-that-must-remain-readable",
				leaguePoints: 150,
			},
			xpRecord: Default.args.profileData?.xpRecord ?? {
				currentLevel: 5,
				currentLevelXP: 450,
				xpNeeded: 1000,
				totalXP: 5450,
			},
			firstContribution: Default.args.profileData?.firstContribution,
			contributedRepositories: Default.args.profileData?.contributedRepositories ?? [],
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};

export const ActivityUnavailable: Story = {
	args: {
		...Default.args,
		activityMonitorError: { status: 503 },
		onRetryActivityMonitor: fn(),
	},
};

export const LoadingActivity: Story = {
	args: {
		...Default.args,
		isActivityLoading: true,
	},
};
