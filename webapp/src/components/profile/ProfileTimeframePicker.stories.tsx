import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, waitFor } from "storybook/test";

import { STORY_NOW } from "@/components/common/story-clock";
import { DEFAULT_SCHEDULE, formatDateRangeForApi, getDateRangeForPreset } from "@/lib/timeframe";
import { Stateful } from "@/stories/stateful";

import { ProfileTimeframePicker } from "./ProfileTimeframePicker";

const defaultRange = getDateRangeForPreset(new Date(STORY_NOW), "this-week", DEFAULT_SCHEDULE);
const { after: defaultAfter, before: defaultBefore } = formatDateRangeForApi(defaultRange);

const meta = {
	component: ProfileTimeframePicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={{ afterDate: args.afterDate, beforeDate: args.beforeDate }}>
			{(dates, setDates) => (
				<ProfileTimeframePicker
					{...args}
					{...dates}
					onTimeframeChange={(afterDate, beforeDate) => {
						setDates({ afterDate, beforeDate });
						args.onTimeframeChange?.(afterDate, beforeDate);
					}}
				/>
			)}
		</Stateful>
	),
	args: {
		afterDate: defaultAfter,
		beforeDate: defaultBefore,
		onTimeframeChange: fn(),
		enableAllActivity: true,
		schedule: DEFAULT_SCHEDULE,
	},
} satisfies Meta<typeof ProfileTimeframePicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Timeframe" }));
		await userEvent.click(await screen.findByRole("option", { name: "All time" }));
		await waitFor(() =>
			expect(canvas.getByRole("combobox", { name: "Timeframe" })).toHaveTextContent("All time"),
		);
	},
};

export const AllTime: Story = {
	args: {
		afterDate: "1970-01-01T00:00:00.000Z",
		beforeDate: undefined,
	},
};

export const LastWeek: Story = {
	args: (() => {
		const range = getDateRangeForPreset(new Date(STORY_NOW), "last-week", DEFAULT_SCHEDULE);
		const { after, before } = formatDateRangeForApi(range);
		return { afterDate: after, beforeDate: before };
	})(),
};

export const ThisMonth: Story = {
	args: (() => {
		const range = getDateRangeForPreset(new Date(STORY_NOW), "this-month", DEFAULT_SCHEDULE);
		const { after, before } = formatDateRangeForApi(range);
		return { afterDate: after, beforeDate: before };
	})(),
};

export const LastMonth: Story = {
	args: (() => {
		const range = getDateRangeForPreset(new Date(STORY_NOW), "last-month", DEFAULT_SCHEDULE);
		const { after, before } = formatDateRangeForApi(range);
		return { afterDate: after, beforeDate: before };
	})(),
};

export const CustomRange: Story = {
	args: {
		afterDate: "2024-11-01T00:00:00.000Z",
		beforeDate: "2024-11-15T00:00:00.000Z",
	},
};

export const TuesdaySchedule: Story = {
	args: {
		schedule: { day: 2, hour: 9, minute: 0 },
	},
};

export const WednesdaySchedule: Story = {
	args: {
		schedule: { day: 3, hour: 10, minute: 0 },
	},
};

export const FridaySchedule: Story = {
	args: {
		schedule: { day: 5, hour: 16, minute: 30 },
	},
};

export const WithoutAllTime: Story = {
	args: {
		enableAllActivity: false,
	},
};

export const AllPresets: Story = {
	render: () => {
		const presets = [
			{
				label: "This Week",
				...formatDateRangeForApi(getDateRangeForPreset(new Date(STORY_NOW), "this-week")),
			},
			{
				label: "Last Week",
				...formatDateRangeForApi(getDateRangeForPreset(new Date(STORY_NOW), "last-week")),
			},
			{
				label: "This Month",
				...formatDateRangeForApi(getDateRangeForPreset(new Date(STORY_NOW), "this-month")),
			},
			{
				label: "Last Month",
				...formatDateRangeForApi(getDateRangeForPreset(new Date(STORY_NOW), "last-month")),
			},
			{
				label: "All Time",
				after: "1970-01-01T00:00:00.000Z",
				before: undefined,
			},
		];

		return (
			<div className="flex flex-col gap-4">
				{presets.map(({ label, after, before }) => (
					<div key={label} className="flex items-center gap-4">
						<span className="w-24 text-sm text-muted-foreground">{label}:</span>
						<ProfileTimeframePicker
							afterDate={after}
							beforeDate={before}
							enableAllActivity={true}
							schedule={DEFAULT_SCHEDULE}
						/>
					</div>
				))}
			</div>
		);
	},
};
