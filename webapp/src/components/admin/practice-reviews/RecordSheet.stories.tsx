import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { minutesBefore } from "@/stories/story-clock";

import { SheetHistory, SheetStatusLine, SheetTechnical } from "./RecordSheet";

/**
 * The parts every record sheet of the Practice reviews is built from. The history lists a record's
 * events oldest first, each with its absolute time, and puts an event without a time after the
 * timed ones.
 */
const meta = {
	component: SheetHistory,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		events: [
			{ key: "delivered", at: minutesBefore(5), title: "Delivered as a summary comment" },
			{ key: "created", at: minutesBefore(60), title: "Review created" },
			{ key: "unknown", at: undefined, title: "Marked incorrect", by: "Ada Lovelace" },
		],
		now: "The feedback stands as delivered.",
	},
	argTypes: { events: { control: false } },
} satisfies Meta<typeof SheetHistory>;

export default meta;
type Story = StoryObj<typeof meta>;

export const History: Story = {
	play: async ({ canvas }) => {
		const events = within(canvas.getByRole("list", { name: "History" })).getAllByRole("listitem");
		// Oldest first, and the event without a time last.
		const titles = ["Review created", "Delivered as a summary comment", "Marked incorrect"];
		await expect(events).toHaveLength(titles.length);
		for (const [index, title] of titles.entries()) {
			await expect(events[index]).toHaveTextContent(title);
		}
		canvas.getByText("by Ada Lovelace");
		canvas.getByText("The feedback stands as delivered.");
	},
};

/** The status line says the record's status once, then one flag, one figure and one time. */
export const StatusLine: Story = {
	render: () => (
		<SheetStatusLine
			status="Delivered"
			flag="Marked incorrect"
			figure="1 of 2 delivered"
			at={{ label: "Delivered", date: minutesBefore(5) }}
		/>
	),
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector("[data-sheet-status]")).toHaveTextContent(
			/Delivered.*Marked incorrect.*1 of 2 delivered.*Delivered/u,
		);
	},
};

/** A fact that carries text to copy gets a button named for it; one without gets none. */
export const Technical: Story = {
	render: () => (
		<SheetTechnical
			foldKey="record-sheet-story"
			facts={[
				{ label: "Review ID", value: <code>8f2c0d1e</code>, copy: "8f2c0d1e" },
				{ label: "Runner", value: "agent-3" },
			]}
		/>
	),
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Copy review id" });
		await expect(canvas.getAllByRole("button", { name: /^Copy/u })).toHaveLength(1);
	},
};
