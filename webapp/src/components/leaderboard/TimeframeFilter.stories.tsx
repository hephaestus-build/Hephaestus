import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, waitFor } from "storybook/test";

import { STORY_NOW } from "@/components/common/story-clock";
import { DEFAULT_SCHEDULE, formatDateRangeForApi, getDateRangeForPreset } from "@/lib/timeframe";

import { Stateful } from "@/stories/stateful";

import { TimeframeFilter } from "./TimeframeFilter";

const defaultRange = getDateRangeForPreset(new Date(STORY_NOW), "this-week", DEFAULT_SCHEDULE);
const { after: defaultAfter, before: defaultBefore } = formatDateRangeForApi(defaultRange);

const meta = {
	component: TimeframeFilter,
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={{ after: args.afterDate, before: args.beforeDate }}>
			{(dates, setDates) => (
				<TimeframeFilter
					{...args}
					afterDate={dates.after}
					beforeDate={dates.before}
					onTimeframeChange={(after, before, timeframe) => {
						setDates({ after, before });
						args.onTimeframeChange?.(after, before, timeframe);
					}}
				/>
			)}
		</Stateful>
	),
	parameters: {
		layout: "centered",
	},

	args: {
		onTimeframeChange: fn(),
	},
} satisfies Meta<typeof TimeframeFilter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Timeframe" }));
		await userEvent.click(await screen.findByRole("option", { name: "Last month" }));
		await waitFor(() =>
			expect(canvas.getByRole("combobox", { name: "Timeframe" })).toHaveTextContent("Last month"),
		);
	},
};

export const WithSchedule: Story = {
	args: {
		leaderboardSchedule: {
			day: 1, // Monday
			hour: 9,
			minute: 0,
		},
	},
};

export const FridaySchedule: Story = {
	args: {
		leaderboardSchedule: {
			day: 5, // Friday
			hour: 16,
			minute: 30,
		},
	},
};

export const TuesdaySchedule: Story = {
	args: {
		leaderboardSchedule: {
			day: 2, // Tuesday
			hour: 9,
			minute: 0,
		},
	},
};

export const ThisWeekSelected: Story = {
	args: {
		afterDate: defaultAfter,
		beforeDate: defaultBefore,
		leaderboardSchedule: {
			day: 1,
			hour: 9,
			minute: 0,
		},
	},
};

export const LastWeekSelected: Story = {
	args: (() => {
		const range = getDateRangeForPreset(new Date(STORY_NOW), "last-week", DEFAULT_SCHEDULE);
		const { after, before } = formatDateRangeForApi(range);
		return {
			afterDate: after,
			beforeDate: before,
			leaderboardSchedule: {
				day: 1,
				hour: 9,
				minute: 0,
			},
		};
	})(),
};

export const WithAllActivityOption: Story = {
	args: {
		enableAllActivityOption: true,
		leaderboardSchedule: {
			day: 1,
			hour: 9,
			minute: 0,
		},
	},
};

export const OpenEndedMode: Story = {
	args: {
		openEndedPresets: true,
		leaderboardSchedule: {
			day: 1,
			hour: 9,
			minute: 0,
		},
	},
};
