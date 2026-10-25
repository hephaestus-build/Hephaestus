import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";

import { ITEM_KIND } from "@/lib/item-kinds";
import { withProvider } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";

import { statusValues } from "@/components/common/status-def";
import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";
import { StandingBadge } from "./StandingBadge";

/**
 * A standing is the registry's badge made focusable, so the sentence behind it is reachable by
 * keyboard as well as pointer. The badge itself is `StatusBadge`, as every enum's is.
 */
const meta = {
	component: StandingBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { standing: "STRENGTH", scope: "group" },
	decorators: [withProvider("GITHUB")],
} satisfies Meta<typeof StandingBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The sentence is the reason the badge is a button; it portals, so it is read off the document.
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Going well" })).toHaveFocus();
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Recent reviews here were almost entirely positive.");
	},
};

/**
 * The same standing on one practice: the sentence is about that practice, not about a group of
 * them.
 */
export const PracticeNotReviewed: Story = {
	args: { standing: "NOT_OBSERVED", scope: "practice" },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button", { name: "No standing yet" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"No review has looked at this practice in your work yet.",
		);
	},
};

const fourPieces = {
	bundleSize: 4,
	credibilityThreshold: 0.9,
	currentOpportunities: 4,
	opportunities: 8,
	opportunitiesUntilComparable: 0,
	previousOpportunities: 4,
	standingWork: 4,
	reviewedWork: 11,
	reviewedWorkKind: ITEM_KIND.pullRequest,
	ropeHalfWidth: 0.15,
	calendarSpanDays: null,
	comparablePractices: null,
	eligiblePractices: null,
	firstOpportunityAt: null,
	lastOpportunityAt: null,
};

/**
 * A settled standing names the work it is read from in its sentence, never beside the badge: the
 * newest reviewed pull requests it reads, and all the pull requests reviewed for the practice.
 */
export const WithItsWork: Story = {
	args: { scope: "practice", support: fourPieces },
	play: async ({ canvas }) => {
		// No count beside the badge: it lives in the sentence, which a screen reader hears too.
		const SR_ONLY = { ignore: "script, style, .sr-only" };
		await expect(canvas.queryByText(/pieces? of work|pull requests/u, SR_ONLY)).toBeNull();
		await expect(canvas.getByRole("button", { name: "Going well" })).toHaveAccessibleDescription(
			/11 pull requests were reviewed for this practice/u,
		);
		await userEvent.hover(canvas.getByRole("button", { name: "Going well" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"Recent reviews here were almost entirely positive. Read from your last 4 reviewed pull requests. In the last 90 days, 11 pull requests were reviewed for this practice.",
		);
	},
};

/** A group counts its practices' work once each, in the same two sentences. */
export const GroupWork: Story = {
	args: { support: { ...fourPieces, standingWork: 6, reviewedWork: 15 } },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button", { name: "Going well" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"Read from the last reviewed pull requests of each practice here, 6 in all. In the last 90 days, 15 pull requests were reviewed for this group.",
		);
	},
};

/** Fewer than three pieces of work: the sentence behind the badge calls the standing an early read. */
export const EarlyRead: Story = {
	args: { scope: "practice", support: { ...fourPieces, standingWork: 1, reviewedWork: 1 } },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByText(/pieces? of work|pull request/u, { ignore: "script, style, .sr-only" }),
		).toBeNull();
		await userEvent.hover(canvas.getByRole("button", { name: "Going well" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"An early read from the only pull request reviewed for this practice in the last 90 days.",
		);
		await expect(tooltip).not.toHaveTextContent("almost entirely positive");
	},
};

/** On GitLab the same sentence counts merge requests. */
export const OnGitLab: Story = {
	args: { scope: "practice", support: fourPieces },
	decorators: [withProvider("GITLAB")],
	play: async ({ canvas }) => {
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Going well" })).toHaveFocus();
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Read from your last 4 reviewed merge requests.");
	},
};

/**
 * A tap opens the sentence and a second tap closes it: the sentence is a popover, as every (i)
 * button's is, since a tooltip never opens on touch.
 */
export const TapToRead: Story = {
	args: { scope: "practice", support: fourPieces },
	play: async ({ canvas }) => {
		const badge = canvas.getByRole("button", { name: "Going well" });
		await userEvent.pointer({ keys: "[TouchA]", target: badge });
		const popup = await settledPopup();
		await expect(popup).toHaveTextContent("11 pull requests were reviewed for this practice.");
		await expect(badge).toHaveAttribute("aria-expanded", "true");
		await userEvent.pointer({ keys: "[TouchA]", target: badge });
		await waitFor(async () => {
			await expect(document.querySelector("[data-slot='popover-content']")).toBeNull();
		});
	},
};

/** Focus from the keyboard opens the sentence, and Escape closes it with the focus left on the badge. */
export const EscapeCloses: Story = {
	args: { scope: "practice", support: fourPieces },
	play: async ({ canvas }) => {
		await userEvent.tab();
		const badge = canvas.getByRole("button", { name: "Going well" });
		await expect(badge).toHaveFocus();
		await expect(await settledPopup()).toHaveTextContent("Read from your last 4 reviewed");
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(document.querySelector("[data-slot='popover-content']")).toBeNull();
		});
		await expect(badge).toHaveFocus();
	},
};

/** A standing no review has settled names no work, whatever support comes with it. */
export const NotSettled: Story = {
	args: { standing: "NOT_OBSERVED", support: fourPieces },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/pieces? of work/u)).toBeNull();
	},
};

/**
 * Every standing at once, which is where two entries sharing an icon would show. The two unsettled
 * standings share one name, No standing yet, and keep their own icons.
 */
export const EveryStanding: Story = {
	render: (args) => (
		<div className="flex flex-wrap gap-2">
			{statusValues(PRACTICE_GROUP_STANDING_DEFS).map((standing) => (
				<StandingBadge key={standing} {...args} standing={standing} />
			))}
		</div>
	),
	play: async ({ canvas }) => {
		const names = canvas.getAllByRole("button").map((badge) => badge.textContent);
		await expect(names).toStrictEqual(
			statusValues(PRACTICE_GROUP_STANDING_DEFS).map(
				(standing) => PRACTICE_GROUP_STANDING_DEFS[standing].label,
			),
		);
		const icons = canvas
			.getAllByRole("button")
			.map((badge) => badge.querySelector("svg")?.outerHTML);
		await expect(new Set(icons).size).toBe(icons.length);
	},
};
