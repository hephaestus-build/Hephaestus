import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import {
	conversation,
	groups,
	packagingGroup,
	pullRequest,
} from "@/stories/practice-profile-story-mock-data";

import { group, practice, text, work } from "./feedback-text";
import { FeedbackText } from "./FeedbackText";

const PRACTICE_NAME = "Scope one reviewable change";
const PRACTICE_SLUG = "scope-one-reviewable-change";

/** Every segment kind in one sentence: words, a practice, a group, work with and without a page. */
const SENTENCE = [
	text("On "),
	work(pullRequest(22)),
	text(" you kept to "),
	practice(PRACTICE_SLUG, PRACTICE_NAME),
	text(", the habit "),
	group(packagingGroup.slug, packagingGroup.name),
	text(" is built on, and said so in "),
	work(conversation("#releases")),
	text("."),
];

const meta = {
	component: FeedbackText,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		segments: SENTENCE,
		groups,
		as: "p",
		className: "mx-auto max-w-xl text-sm",
		onOpenPractice: fn(),
		onOpenGroup: fn(),
	},
} satisfies Meta<typeof FeedbackText>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * With somewhere to open each name, the practice and the group are controls a keyboard reaches,
 * and only work that carries the provider's page is a link.
 */
export const Openable: Story = {
	play: async ({ args, canvas }) => {
		const workLink = canvas.getByRole("link", { name: "#22 (opens in a new tab)" });
		await expect(workLink).toHaveAttribute("href", expect.stringContaining("/pull/22"));
		await expect(workLink).toHaveAttribute("target", "_blank");
		// A conversation carries no page, so it stays a word rather than a link that goes nowhere.
		await expect(canvas.queryByRole("link", { name: "#releases" })).toBeNull();
		await expect(canvas.getByText("#releases")).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: PRACTICE_NAME }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith(PRACTICE_SLUG);

		const groupName = canvas.getByRole("button", { name: packagingGroup.name });
		// The workspace's own colour for the group, from the `groups` it was handed.
		await expect(groupName.closest("span")).toHaveClass("text-sky-700");
		await userEvent.click(groupName);
		await expect(args.onOpenGroup).toHaveBeenCalledWith(packagingGroup.slug);
	},
};

/** With nowhere to open them, the practice and the group are words; the work link stays a link. */
export const NothingToOpen: Story = {
	args: { onOpenPractice: undefined, onOpenGroup: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("button")).toStrictEqual([]);
		await expect(canvas.getByText(PRACTICE_NAME)).toBeVisible();
		await expect(canvas.getByText(packagingGroup.name)).toBeVisible();
		await expect(canvas.getAllByRole("link")).toHaveLength(1);
	},
};

/**
 * A group the workspace's list no longer carries still reads as the group it is: the catalog's
 * folder and grey rather than a name whose colour went missing.
 */
export const GroupNotInTheWorkspace: Story = {
	args: {
		segments: [
			text("This came out of "),
			group("retired-group", "Reviewing in the open"),
			text("."),
		],
	},
	play: async ({ args, canvas }) => {
		const groupName = canvas.getByRole("button", { name: "Reviewing in the open" });
		await expect(groupName.closest("span")).toHaveClass("text-slate-700");
		await userEvent.click(groupName);
		await expect(args.onOpenGroup).toHaveBeenCalledWith("retired-group");
	},
};
