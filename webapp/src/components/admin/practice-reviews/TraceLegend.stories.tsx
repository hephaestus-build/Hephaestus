import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { TraceLegend, type TraceLegendProps } from "./TraceLegend";

/**
 * The trace's legend: a "Legend" button in the trace's toolbar, and over the trace a sample of each
 * mark the rail draws, drawn by the same part, beside a short name and one line, grouped by what it
 * marks. It opens over the rail, so the rail does not move.
 */
function LegendInToolbar(props: TraceLegendProps) {
	const [open, setOpen] = useState(props.open);
	return (
		<TraceLegend
			{...props}
			open={open}
			onOpenChange={(next) => {
				setOpen(next);
				props.onOpenChange(next);
			}}
		/>
	);
}

const meta = {
	component: TraceLegend,
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="flex w-[40rem] max-w-full justify-end p-4">
				<Story />
			</div>
		),
	],
	args: {
		open: false,
		onOpenChange: fn(),
	},
	render: (args) => <LegendInToolbar {...args} />,
} satisfies Meta<typeof TraceLegend>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Closed: only the button, in the toolbar where the reader finds it. */
export const Closed: Story = {
	play: async ({ canvas, args, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Legend" });
		await expect(button).toHaveAttribute("aria-expanded", "false");
		await expect(screen.queryByRole("region", { name: "Legend" })).toBeNull();
		await userEvent.click(button);
		await expect(args.onOpenChange).toHaveBeenCalledWith(true);
		// The popover fades in, so it is asserted once it has settled.
		await expectSettledVisible(await screen.findByRole("region", { name: "Legend" }));
	},
};

export const Open: Story = {
	args: { open: true },
	play: async () => {
		const region = await screen.findByRole("region", { name: "Legend" });
		const legend = within(region);
		// It opens at its top, so the first group is in view and no term's tooltip shows.
		await expectSettledVisible(legend.getByRole("heading", { name: "How to read the trace" }));
		// Base UI moves the initial focus after the popup mounts, not in the same frame.
		await waitFor(async () => {
			await expect(legend.getByRole("heading", { name: "How to read the trace" })).toHaveFocus();
		});
		for (const group of [
			"Stages",
			"Steps",
			"Where a path ends",
			"Observations and feedback",
			"Connections",
			"Rail",
			"Icons",
		]) {
			await expect(legend.getByRole("region", { name: group })).toBeVisible();
		}
		// Every mark a stage's header can carry, not only two of them.
		for (const mark of ["Stopped here", "Lost here", "Time went here", "Waiting here"]) {
			await expect(legend.getByText(mark)).toBeVisible();
		}
		await expect(legend.getByText("You started here")).toBeVisible();
		await expect(legend.getByText("Merged early")).toBeVisible();
		for (const end of ["Delivered", "Did not arrive", "Open", "No feedback"]) {
			await expect(
				within(legend.getByRole("region", { name: "Where a path ends" })).getByText(end),
			).toBeVisible();
		}
		// Where a piece went, in the tags its row carries.
		await expect(legend.getByText("On the work")).toBeVisible();
		await expect(legend.queryByText("On GitHub")).toBeNull();
		await expect(legend.getByText("Withheld")).toBeVisible();
		// What an admin may know of an answer: that it was given, never how the developer rated it.
		await expect(legend.queryByText("Helpful")).toBeNull();
		await expect(legend.queryByText(/verdict/iu)).toBeNull();
		const cannot = legend.getByRole("heading", { name: "What the trace cannot show" });
		await expect(cannot.nextElementSibling?.querySelectorAll("li")).toHaveLength(3);
	},
};

/** The phone: one column, inside the screen, scrolling on its own. */
export const OpenOnAPhone: Story = {
	args: { open: true },
	globals: { viewport: { value: "phone390" } },
	play: async () => {
		const region = await screen.findByRole("region", { name: "Legend" });
		await expectSettledVisible(region);
		await expect(region.getBoundingClientRect().right).toBeLessThanOrEqual(window.innerWidth);
	},
};
