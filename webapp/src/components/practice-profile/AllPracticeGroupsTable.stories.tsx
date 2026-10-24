import type { Meta, StoryObj } from "@storybook/react-vite";

import { expect, fn, screen, userEvent, within } from "storybook/test";
import type { GroupRef } from "@/api/types.gen";

import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { expectFoldOnTableColumns } from "@/stories/fold-columns";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	practicesByGroup,
	SHARED_TRANSITION_OVERVIEW,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { withProvider } from "@/stories/decorators";
import { AllPracticeGroupsTable } from "./AllPracticeGroupsTable";
import { composeOverview } from "./compose-overview";

const meta = {
	decorators: [withProvider("GITHUB")],
	component: AllPracticeGroupsTable,
	tags: ["autodocs"],
	args: {
		groups,
		standings: groupStandings,
		practicesByGroup,
		sentences: composeOverview(OVERVIEW_FIXTURE).groupSentences,
		sort: DEFAULT_PRACTICE_GROUP_SORT,
		onSortChange: fn(),
		onOpenGroup: fn(),
		onOpenPractice: fn(),
		state: { status: "ready" },
	},
	// The caller orders the rows under the sort it holds.
	render: (args) => (
		<AllPracticeGroupsTable
			{...args}
			groups={sortPracticeGroups(args.groups, args.standings, args.sort)}
		/>
	),
} satisfies Meta<typeof AllPracticeGroupsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The one fold under the table: groups in which no practice has a standing yet. */
const FOLD = "Practice groups with no standing yet";

export const Default: Story = {
	play: async ({ args, canvas }) => {
		// Under the table, one quiet line says that the group colors carry no rating.
		await expect(
			canvas.getByText(
				"The colors of the group names only tell the groups apart. They are not a rating.",
			),
		).toBeVisible();
		// What needs attention comes first, and the standing words are the registry's.
		const rows = canvas.getAllByRole("button", { name: /^Open group /u });
		await expect(rows.map((row) => row.getAttribute("aria-label"))).toStrictEqual([
			"Open group Packaging work for review",
			"Open group Communicating in the open",
			"Open group Acting on review feedback",
			"Open group Reviewing a teammate's work constructively",
		]);
		await expect(canvas.getAllByRole("button", { name: "Needs attention" })).toHaveLength(2);
		// The group in which no practice has a standing yet waits in the fold under the table.
		await expect(canvas.getByRole("button", { name: FOLD })).toBeVisible();
		// A trend reads as words, and a group with none says so in the same place.
		await expect(canvas.getByRole("button", { name: "More difficulties recently" })).toBeVisible();
		await expect(canvas.getAllByText("Not enough to compare yet")).toHaveLength(2);
		const breakdowns = canvas.getAllByRole("list", { name: "Practices by standing" }).map((list) =>
			within(list)
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		);
		await expect(breakdowns[0]).toStrictEqual(["2Needs attention", "1Mixed", "2Going well"]);
		// A group with no event shows its name and nothing beneath it.
		await expect(canvas.queryByText("Nothing new since the latest review.")).toBeNull();
		await expect(canvas.getByText("Acting on review feedback").closest("td")?.textContent).toBe(
			"Acting on review feedback",
		);

		// A practice name in a sentence opens the practice, not the group.
		await userEvent.click(canvas.getByRole("button", { name: "Describe what changed and why" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("describe-what-and-why");
		await expect(args.onOpenGroup).not.toHaveBeenCalled();

		await userEvent.click(canvas.getByRole("button", { name: FOLD }));
		await userEvent.click(canvas.getByRole("button", { name: "Open group Testing your changes" }));
		await expect(args.onOpenGroup).toHaveBeenCalledWith(groups[4]);
	},
};

/**
 * The "Practice group" heading carries an (i) that says what a practice group is. A keyboard reaches
 * it, and its focus opens the explanation.
 */
export const PracticeGroupInfo: Story = {
	play: async ({ canvas }) => {
		const heading = canvas.getByRole("columnheader", { name: /^Practice group/u });
		const button = within(heading).getByRole("button", { name: "About practice groups" });
		// The heading's words stand in the foreground, as the Standing head's do.
		const words = within(heading).getByText("Practice group");
		await expect(getComputedStyle(words).color).toBe(
			getComputedStyle(canvas.getByRole("button", { name: /Standing/u })).color,
		);
		// From the Standing sort, the next Tab reaches the (i): the keyboard's path through the header.
		canvas.getByRole("button", { name: /Standing/u }).focus();
		await userEvent.tab();
		await expect(button).toHaveFocus();
		const panel = await screen.findByRole("dialog", { name: "About practice groups" });
		await expect(panel).toHaveTextContent("A practice group is a named set of related practices.");
	},
};

/**
 * A group's events are one bullet each, every one listed, with the practice as the pill the
 * feedback cards name it with.
 */
export const ManyEvents: Story = {
	play: async ({ args, canvas }) => {
		const cell = canvas.getByText("Packaging work for review").closest("td");
		if (!cell) {
			throw new Error("The group's name sits in its cell");
		}
		const items = within(cell).getAllByRole("listitem");
		// A practice in the group's summary wears the row's label: the gray pill with the group's icon.
		const labels = cell.querySelectorAll("li [data-practice-pill]");
		await expect(labels.length).toBeGreaterThan(0);
		for (const label of labels) {
			await expect(label.querySelector("svg")).not.toBeNull();
		}
		// Pills on later lines keep a gap to the pill on the line above; the summary has more than one.
		const boxes = [...labels].map((label) => label.getBoundingClientRect());
		const gaps = boxes.slice(1).flatMap((next, index) => {
			const above = boxes[index];
			return above !== undefined && next.top > above.top + 1 ? [next.top - above.bottom] : [];
		});
		await expect(gaps.length).toBeGreaterThan(0);
		for (const gap of gaps) {
			await expect(gap).toBeGreaterThanOrEqual(3);
		}
		// New feedback is the card's to say, so the cell has no bullet for it.
		await expect(items).toHaveLength(5);
		await expect(cell).not.toHaveTextContent(/new feedback/u);
		// Feedback the work fell back on comes first, then the resolutions.
		await expect(items[0]).toHaveTextContent(
			"Say which acceptance criteria are done is back to none of 3 clean in a row.",
		);
		// Each reference carries its own "(opens in a new tab)", so the sentence is matched to its
		// first.
		await expect(items[1]).toHaveTextContent(
			/^Describe what changed and why resolved by the work after #22/u,
		);
		await userEvent.click(
			within(cell).getByRole("button", { name: "Describe what changed and why" }),
		);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("describe-what-and-why");
		await expect(args.onOpenGroup).not.toHaveBeenCalled();
	},
};

/**
 * Practices that made the same move are one bullet, their pills listed and what happened said
 * once: three trends that turned the same way read as one piece of news, not three. The group's
 * own move rides on the bullet of the practice that made it, and a move no practice made says
 * "the group" rather than repeating the name the row already carries.
 */
export const OneBulletPerMove: Story = {
	args: { sentences: composeOverview(SHARED_TRANSITION_OVERVIEW).groupSentences },
	play: async ({ args, canvas }) => {
		const cell = canvas.getByText("Packaging work for review").closest("td");
		if (!cell) {
			throw new Error("The group's name sits in its cell");
		}
		const items = within(cell).getAllByRole("listitem");
		await expect(items).toHaveLength(3);
		// The group moved where this practice moved, so it is the tail of that practice's bullet.
		await expect(items[0]).toHaveTextContent(
			"Describe what changed and why is now at Going well for you, and the group with it.",
		);
		await expect(items[1]).toHaveTextContent(
			"Mark the change ready and link its issue was seen for the first time.",
		);
		// Three trends that turned the same way are one bullet, and the verb agrees with them.
		await expect(items[2]).toHaveTextContent(
			"Scope the change to one concern, Write commit subjects a reviewer can follow and Keep the diff reviewable in one sitting now show More positive recently.",
		);
		// Every practice in the joined bullet is its own pill, and each opens its own practice.
		await userEvent.click(
			within(cell).getByRole("button", { name: "Keep the diff reviewable in one sitting" }),
		);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("reviewable-diff-size");

		// A move none of the group's practices made is the group's own bullet, and it says "the
		// group": the row beside it already names it.
		const alone = canvas.getByText("Communicating in the open").closest("td");
		if (!alone) {
			throw new Error("The group's name sits in its cell");
		}
		await expect(within(alone).getByRole("listitem")).toHaveTextContent(
			"The group is now at Mixed feedback for you.",
		);
	},
};

/** The open group's row carries a bar on its leading edge and nothing else changes. */
export const OpenRow: Story = {
	args: { openGroupSlug: "communication" },
	play: async ({ canvas }) => {
		const openRows = canvas.getAllByRole("row").filter((row) => row.dataset.state === "open");
		await expect(openRows).toHaveLength(1);
		await expect(openRows[0]).toHaveTextContent("Communicating in the open");
	},
};

/** No row is a link: the trailing column stays empty and the group names are plain text. */
export const ReadOnly: Story = {
	args: { onOpenGroup: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Open group /u })).toBeNull();
		await expect(canvas.getByText("Packaging work for review")).toBeVisible();
	},
};

/** No groups: the empty block every practice surface shows, in the table's own frame. */
export const Empty: Story = {
	args: { groups: [], standings: {}, practicesByGroup: {} },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
		// No group, no colors to explain.
		await expect(canvas.queryByText(/colors of the group names/u)).toBeNull();
		await expect(
			canvas.getByText(
				"Practice groups appear here once a workspace admin sets up the practices this workspace reviews.",
			),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("button", { name: /^Open group /u })).toBeNull();
		await expect(canvas.queryByText(/colors of the group names/u)).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		groups: [],
		state: {
			status: "error",
			error: new Error("Practice standings are unavailable right now"),
			onRetry: fn(),
		},
	},
	play: async ({ args: { state }, canvas }) => {
		await expect(canvas.getByText("We could not load your practice groups")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

/**
 * On a phone the rows stack instead of scrolling sideways: each group's name comes first and is
 * seen whole, and nothing in the table reaches past its frame.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expectNoPageOverflow();
		const table = canvas.getByRole("table");
		const container = table.parentElement;
		if (container === null) {
			throw new Error("The table has no container");
		}
		await expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
		const [firstRow] = within(table).getAllByRole("row").slice(1);
		if (firstRow === undefined) {
			throw new Error("No group rows");
		}
		const subject = firstRow.querySelector("[data-subject]");
		const standing = firstRow.querySelector("td");
		await expect(subject).not.toBeNull();
		// The name heads the row: above the standing, not beside it off-screen.
		await expect(subject?.getBoundingClientRect().top).toBeLessThan(
			standing?.getBoundingClientRect().top ?? 0,
		);
	},
};

/** The last group with every practice never applied. */
const lastGroup = groups.at(-1);
const neverAppliedByGroup = Object.fromEntries(
	Object.entries(practicesByGroup).map(([slug, practices]) => [
		slug,
		slug === lastGroup?.slug
			? practices.map((practice) => ({
					...practice,
					standing: "NO_OPPORTUNITY" as const,
					doesNotApplyOnly: true,
				}))
			: practices,
	]),
);

/**
 * A group whose every practice never applied leaves the table for the same fold as the groups no
 * review has reviewed: to a developer, both have no standing yet.
 */
export const NeverApplied: Story = {
	args: { practicesByGroup: neverAppliedByGroup },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: FOLD }));
		const folded = canvas.getByRole("rowgroup", { name: FOLD });
		await expect(
			within(folded).getByRole("button", { name: `Open group ${lastGroup?.name ?? ""}` }),
		).toBeVisible();
		await expect(
			within(folded).getAllByRole("button", { name: "No standing yet" }),
		).not.toHaveLength(0);
		await expect(canvas.queryByText("Does not apply to your work yet")).toBeNull();
	},
};

export const NeverAppliedDark: Story = {
	args: { practicesByGroup: neverAppliedByGroup },
	globals: { theme: "dark" },
};

/**
 * A group no review has reviewed a practice of yet leaves the table for the fold of groups with no
 * standing yet. The group still opens from the fold.
 */
export const NoStandingYetGroups: Story = {
	play: async ({ args, canvas }) => {
		const trigger = canvas.getByRole("button", {
			name: FOLD,
		});
		await expect(trigger).toHaveAttribute("aria-expanded", "false");
		await expect(
			canvas.queryByRole("button", { name: "Open group Testing your changes" }),
		).not.toBeInTheDocument();
		await userEvent.click(trigger);
		// Opened, the fold's rows join the same table: one header, the same columns.
		await expect(canvas.getAllByRole("table")).toHaveLength(1);
		const folded = canvas.getByRole("rowgroup", {
			name: FOLD,
		});
		await expectFoldOnTableColumns(
			canvas.getByRole("table", { name: "All practice groups" }).querySelector("tbody tr"),
			folded,
		);
		// One quiet indication per row: the badge, without trend, ring or counts.
		await expect(within(folded).getByText("No standing yet")).toBeVisible();
		await expect(within(folded).queryByText(/compare/u)).toBeNull();
		await expect(within(folded).queryByRole("list", { name: "Practices by standing" })).toBeNull();
		await userEvent.click(
			within(folded).getByRole("button", { name: "Open group Testing your changes" }),
		);
		await expect(args.onOpenGroup).toHaveBeenCalledWith(groups[4]);
	},
};

export const NoStandingYetGroupsOpen: Story = {
	globals: { viewport: { value: "desktop" } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: FOLD }));
		await expectFoldOnTableColumns(
			canvas.getByRole("table", { name: "All practice groups" }).querySelector("tbody tr"),
			canvas.getByRole("rowgroup", { name: FOLD }),
		);
	},
};

export const NoStandingYetGroupsDark: Story = {
	...NoStandingYetGroupsOpen,
	globals: { theme: "dark" },
};

export const NoStandingYetGroupsPhone: Story = {
	...NoStandingYetGroupsOpen,
	globals: { viewport: { value: "phone390" } },
};

const mixedSilentGroup = groups[1];
const mixedSilentByGroup = Object.fromEntries(
	Object.entries(practicesByGroup).map(([slug, practices]) => [
		slug,
		slug === mixedSilentGroup?.slug
			? practices.map((practice, index) =>
					index === 0
						? { ...practice, standing: "NO_OPPORTUNITY" as const, doesNotApplyOnly: true }
						: { ...practice, standing: "NOT_OBSERVED" as const },
				)
			: practices,
	]),
);

/**
 * A group with one practice that never applied and the others not reviewed yet has no standing
 * either: its own level holds only the fold. It joins the fold of groups with no standing yet, so
 * the table and the group's level agree.
 */
export const MixedSilentGroupFolded: Story = {
	args: { practicesByGroup: mixedSilentByGroup },
	play: async ({ canvas }) => {
		const name = `Open group ${mixedSilentGroup?.name ?? ""}`;
		await expect(canvas.queryByRole("button", { name })).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: "Does not apply to your work yet" }),
		).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: FOLD }));
		const folded = canvas.getByRole("rowgroup", { name: FOLD });
		await expect(within(folded).getByRole("button", { name })).toBeVisible();
	},
};

const emptyGroup: GroupRef = {
	...packagingGroup,
	slug: "release-hygiene",
	name: "Keeping releases tidy",
};

/**
 * A group with no practice in it (none added, or all switched off) has nothing to act on, so
 * it joins the fold of groups with no standing yet instead of disappearing. It still opens.
 */
export const EmptyGroupFolded: Story = {
	args: {
		emptyGroups: [emptyGroup],
	},
	play: async ({ args, canvas }) => {
		const trigger = canvas.getByRole("button", {
			name: FOLD,
		});
		await userEvent.click(trigger);
		await userEvent.click(canvas.getByRole("button", { name: "Open group Keeping releases tidy" }));
		await expect(args.onOpenGroup).toHaveBeenCalledWith(
			expect.objectContaining({ slug: "release-hygiene" }),
		);
	},
};
