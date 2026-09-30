import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { TraceKindFilter } from "./TraceKindFilter";

/**
 * Which kind of work the review-activity list shows. "All work" is a choice of its own rather than
 * an empty value, and it is the only spelling of "no filter" that ever reaches the URL.
 */
const meta = {
	component: TraceKindFilter,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		seen: [],
		value: undefined,
		onChange: fn(),
	},
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<TraceKindFilter
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof TraceKindFilter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AllWork: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Show" })).toHaveTextContent("All work");
	},
};

/** Every kind is offered by the name a reader uses, never by its `domain.kind` id. */
export const EveryKindIsNamed: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Show" }));
		// The listbox is portalled, so it is on `screen` rather than in the canvas.
		await expectSettledVisible(
			await screen.findByRole("option", { name: "Pull or merge requests" }),
		);
		screen.getByRole("option", { name: "Issues" });
		screen.getByRole("option", { name: "Conversations" });
		screen.getByRole("option", { name: "Documents" });
		await expect(screen.queryByRole("option", { name: "scm.pull_request" })).toBeNull();
	},
};

export const FilteredToIssues: Story = {
	args: { value: "scm.issue" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Show" })).toHaveTextContent("Issues");
	},
};

/**
 * A kind this build has never heard of arrives from the page it filters, and is shown as "Other
 * work" rather than dropped — a filter that cannot be seen cannot be cleared — and never by its id.
 */
export const AKindThisBuildDoesNotKnow: Story = {
	args: { seen: ["wiki.page"], value: "wiki.page" },
	play: async ({ canvas }) => {
		const select = canvas.getByRole("combobox", { name: "Show" });
		await expect(select).toHaveTextContent("Other work");
		await expect(select).not.toHaveTextContent("wiki.page");
	},
};

/** Choosing "All work" reports `undefined`: the sentinel is this component's business alone. */
export const ClearingTheFilter: Story = {
	args: { value: "scm.issue" },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Show" }));
		await userEvent.click(await screen.findByRole("option", { name: "All work" }));

		await expect(args.onChange).toHaveBeenCalledWith(undefined);
		await expect(canvas.getByRole("combobox", { name: "Show" })).toHaveTextContent("All work");
	},
};
