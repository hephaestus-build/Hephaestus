import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectTouchTarget } from "@/test/controls";

import { getGroupVisual } from "./group-visuals";

import { PracticeInGroup } from "./PracticeInGroup";
import { PracticePill } from "./PracticePill";

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
	args: { group: getGroupVisual("MessageSquareReply", "cyan") },
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

/**
 * `sm` in a line of running text and the weekly digest, the plain gray pill; `row` (the default) in
 * a table, a list or a card's head.
 */
export const Sizes: Story = {
	args: { onOpen: undefined },
	render: (args) => (
		<div className="flex flex-col items-start gap-3">
			<span className="text-xs">
				<PracticePill {...args} size="sm" />
			</span>
			<span className="text-sm">
				<PracticePill {...args} group={getGroupVisual("MessageSquareReply", "cyan")} />
			</span>
		</div>
	),
	play: async ({ canvas }) => {
		const [inText, inRow] = canvas.getAllByText("Explain significant decisions");
		await expect(inText?.closest("[data-slot=badge]")).not.toBeNull();
		await expect(inRow?.closest("[data-slot=badge]")).toBeNull();
	},
};

/** The longest name beside its group's icon: the icon keeps its size and the name truncates. */
export const LongestNameInItsGroup: Story = {
	...LongestName,
	args: { ...LongestName.args, group: getGroupVisual("MessageSquareReply", "cyan") },
	argTypes: { group: { control: false } },
	play: async ({ canvas, canvasElement }) => {
		const name = canvas.getByText(/Explain significant decisions where/u);
		await expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
		// The icon and the name together stay inside the column, not only the text.
		const column = canvasElement.querySelector<HTMLElement>(".w-60");
		const pill = canvas.getByRole("button", { name: /Explain significant decisions where/u });
		await expect(pill.getBoundingClientRect().right).toBeLessThanOrEqual(
			column?.getBoundingClientRect().right ?? 0,
		);
	},
};

/**
 * A practice beside its group, as a feedback card's head names it: the practice's words and the
 * group's are one size, so neither reads as the larger.
 */
export const BesideItsGroup: Story = {
	args: { onOpen: undefined },
	render: (args) => (
		<PracticeInGroup
			practiceName={args.name}
			practiceSquare={getGroupVisual("Package", "sky")}
			groupName="Packaging work for review"
			groupIcon={getGroupVisual("Package", "sky").Icon}
			groupPill={getGroupVisual("Package", "sky").pill}
		/>
	),
	play: async ({ canvas }) => {
		const practice = canvas.getByText("Explain significant decisions");
		const group = canvas.getByText("Packaging work for review");
		await expect(getComputedStyle(practice).fontSize).toBe(getComputedStyle(group).fontSize);
	},
};
