import type { Meta, StoryObj } from "@storybook/react";

import { daysBefore, minutesBefore, STORY_NOW } from "@/components/common/story-clock";

import { NavMentorThreads } from "./NavMentorThreads";
import { withSidebarFrame } from "./sidebar-story-frame";

/**
 * Navigation component showing chat thread history in mentor mode.
 * Displays a flat list of previous conversations; the component buckets
 * threads locally by createdAt (Today, Yesterday, Last 7 days, ...).
 */
const meta = {
	component: NavMentorThreads,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "aet",
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavMentorThreads>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default view showing empty state.
 */
export const Default: Story = {
	args: {
		threads: [],
	},
};

/**
 * Shows the complete thread list with various time groups.
 */
export const WithMockData: Story = {
	args: {
		threads: [
			{ id: "1", title: "React Hooks Best Practices", createdAt: new Date(STORY_NOW) },
			{ id: "2", title: "TypeScript Generic Types", createdAt: new Date(STORY_NOW) },
			{ id: "3", title: "Database Design Help", createdAt: new Date(STORY_NOW) },
			{
				id: "4",
				title: "API Architecture Review",
				createdAt: minutesBefore(24 * 60 + 1),
			},
			{
				id: "5",
				title: "Frontend Performance Tips",
				createdAt: minutesBefore(24 * 60 + 1),
			},
			{
				id: "6",
				title: "Performance Optimization",
				createdAt: daysBefore(3),
			},
		],
	},
	parameters: {
		docs: {
			description: {
				story: "Displays mock chat threads grouped by time periods with various thread titles.",
			},
		},
	},
};

/**
 * Loading state while fetching threads.
 */
export const Loading: Story = {
	args: {
		threads: [],
		isLoading: true,
	},
};

/**
 * Error state when thread loading fails.
 */
export const ErrorState: Story = {
	args: {
		threads: [],
		error: "Failed to load threads",
	},
};
