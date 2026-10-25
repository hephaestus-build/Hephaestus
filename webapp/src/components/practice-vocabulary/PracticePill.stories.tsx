import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { expectTouchTarget } from "@/test/controls";

import { getGroupVisual, groupMark } from "./group-visuals";
import { PracticePill, PracticeTextPill, PracticeTitlePill } from "./PracticePill";

/** A group as the pill shows it: its icon in its color. */
const CYAN = groupMark(getGroupVisual("MessageSquareReply", "cyan"));

const meta = {
	component: PracticePill,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		name: "Explain significant decisions",
		onOpen: fn(),
	},
} satisfies Meta<typeof PracticePill>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Where the surface does not know the group: the gray pill holds the name alone, with no stand-in
 * mark. Somewhere to open the practice: the name is the control, and a keyboard reaches it.
 */
export const Default: Story = {
	play: async ({ args, canvas, canvasElement }) => {
		await expect(canvasElement.querySelector("[data-practice-pill] svg")).toBeNull();
		const pill = canvas.getByRole("button", { name: "Explain significant decisions" });
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpen).toHaveBeenCalledOnce();
	},
};

/** Nowhere to open it: the name is a word, so there is no control for a keyboard to land on. */
export const Plain: Story = {
	args: { onOpen: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.getByText("Explain significant decisions")).toBeVisible();
	},
};

/**
 * A practice named at the length the catalog allows: the label keeps to its column and the name
 * truncates rather than pushing the row it sits in wider.
 */
export const LongestName: Story = {
	args: {
		name: "Explain significant decisions where the reasoning cannot be recovered from the diff alone",
	},
	decorators: [
		(Story) => (
			<div className="w-60 border p-2">
				<Story />
			</div>
		),
	],
	play: async ({ canvas }) => {
		const name = canvas.getByText(/Explain significant decisions where/u);
		await expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
	},
};

/**
 * Where the group is known: the group's icon in its color inside the gray pill, before the name.
 * The icon is a mark; the name alone opens the practice.
 */
export const InItsGroup: Story = {
	args: { group: CYAN },
	argTypes: { group: { control: false } },
	play: async ({ args, canvas, canvasElement }) => {
		const pill = canvasElement.querySelector("[data-practice-pill]");
		await expect(getComputedStyle(pill ?? canvasElement).backgroundColor).not.toBe(
			"rgba(0, 0, 0, 0)",
		);
		await expect(pill?.querySelector("svg")).not.toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Explain significant decisions" }));
		await expect(args.onOpen).toHaveBeenCalledOnce();
		await expect(canvas.getAllByRole("button")).toHaveLength(1);
	},
};

/** The longest name beside its group's icon: the icon keeps its size and the name truncates. */
export const LongestNameInItsGroup: Story = {
	...LongestName,
	args: { ...LongestName.args, group: CYAN },
	argTypes: { group: { control: false } },
	play: async ({ canvas, canvasElement }) => {
		const name = canvas.getByText(/Explain significant decisions where/u);
		await expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
		// The icon and the name together stay inside the column, not only the text.
		const pill = canvasElement.querySelector("[data-practice-pill]");
		const column = pill?.parentElement;
		await expect(pill?.getBoundingClientRect().right ?? Infinity).toBeLessThanOrEqual(
			column?.getBoundingClientRect().right ?? 0,
		);
	},
};

/**
 * In a line of running text and the weekly digest, `PracticeTextPill`: the plain gray pill with no
 * group's icon, the button itself when there is somewhere to open it.
 */
export const InRunningText: Story = {
	render: (args) => (
		<p className="max-w-sm text-sm">
			Your standing on <PracticeTextPill name={args.name} onOpen={args.onOpen} /> is now Going well.
		</p>
	),
	play: async ({ args, canvas }) => {
		const pill = canvas.getByRole("button", { name: args.name });
		await expect(pill.querySelector("svg")).toBeNull();
		await userEvent.click(pill);
		await expect(args.onOpen).toHaveBeenCalledOnce();
	},
};

/**
 * A practice's level is headed by `PracticeTitlePill`, the gray pill at the heading's size with the
 * group's icon. At the longest name the catalog allows and 320 px, the name wraps inside the pill
 * and the heading stays inside the page.
 */
export const AsHeading: Story = {
	args: {
		name: "Explain significant decisions where the reasoning cannot be recovered from the diff alone",
		group: CYAN,
	},
	argTypes: { group: { control: false } },
	globals: { viewport: { value: "reflow" } },
	render: (args) => (
		<h2 className="line-clamp-3 text-2xl font-semibold tracking-tight break-words">
			<PracticeTitlePill name={args.name} group={args.group} />
		</h2>
	),
	play: async ({ args, canvas }) => {
		const heading = canvas.getByRole("heading", { name: /Explain significant decisions where/u });
		await expect(heading.querySelector("svg")).not.toBeNull();
		// The three-line limit can cut the name short, so the full name is its title.
		await expect(heading.querySelector(`[title="${args.name}"]`)).not.toBeNull();
		await expectNoPageOverflow();
		// More than one line, each inside the heading's width.
		await expect(heading.getBoundingClientRect().height).toBeGreaterThan(
			Number.parseFloat(getComputedStyle(heading).lineHeight) * 1.5,
		);
	},
};
