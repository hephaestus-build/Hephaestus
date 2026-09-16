import type { Meta, StoryObj } from "@storybook/react";
import { expect, screen, userEvent } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { RelativeTime } from "./RelativeTime";
import { minutesBefore } from "./story-clock";

const meta = {
	component: RelativeTime,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { value: minutesBefore(4) },
} satisfies Meta<typeof RelativeTime>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const HoverRevealsAbsoluteTime: Story = {
	args: { value: new Date("2026-07-14T09:30:12Z") },
	play: async ({ canvas }) => {
		const trigger = canvas.getByText(/ago$/u);
		await userEvent.hover(trigger);
		await expectSettledVisible(await screen.findByText(/14 Jul 2026, /u));
	},
};

export const Fresh: Story = { args: { value: minutesBefore(4), tone: "fresh" } };

export const Stale: Story = {
	args: { value: minutesBefore(180), tone: "stale" },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: /stale/iu });
	},
};

export const VeryStale: Story = {
	args: { value: minutesBefore(60 * 24 * 9), tone: "veryStale" },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: /very stale/iu });
	},
};

export const UnknownCadence: Story = { args: { value: minutesBefore(600), tone: "unknown" } };

export const Never: Story = {
	args: { value: undefined },
	play: async ({ canvas }) => {
		canvas.getByText("–");
	},
};

export const CustomFallback: Story = {
	args: { value: null, fallback: "not tracked" },
};

/**
 * The generated client does not revive dates: the wire type says `Date`, runtime hands you an ISO
 * string. Trusting the type renders "Invalid Date" in production and passes in Storybook.
 */
export const WireString: Story = {
	args: { value: "2026-07-14T09:30:12Z" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/invalid date/iu)).not.toBeInTheDocument();
	},
};

export const InvalidValue: Story = { args: { value: "not-a-date" } };

export const WithoutTooltip: Story = {
	args: { value: minutesBefore(45), tooltip: false, tone: "stale" },
};

export const AllTones: Story = {
	// Five fixed tones side by side, so there is no single `value` for the control to act on.
	parameters: { controls: { disable: true } },
	render: () => (
		<dl className="grid grid-cols-[8rem_1fr] gap-x-6 gap-y-2 text-sm">
			<dt className="text-muted-foreground">fresh</dt>
			<dd>
				<RelativeTime value={minutesBefore(3)} tone="fresh" />
			</dd>
			<dt className="text-muted-foreground">stale</dt>
			<dd>
				<RelativeTime value={minutesBefore(200)} tone="stale" />
			</dd>
			<dt className="text-muted-foreground">veryStale</dt>
			<dd>
				<RelativeTime value={minutesBefore(60 * 24 * 12)} tone="veryStale" />
			</dd>
			<dt className="text-muted-foreground">unknown</dt>
			<dd>
				<RelativeTime value={minutesBefore(90)} tone="unknown" />
			</dd>
			<dt className="text-muted-foreground">never</dt>
			<dd>
				<RelativeTime value={undefined} tone="never" fallback="Never synced" />
			</dd>
		</dl>
	),
};
