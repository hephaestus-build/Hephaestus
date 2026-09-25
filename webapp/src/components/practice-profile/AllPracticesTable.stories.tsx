import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	practicesByGroup,
	SHARED_TRANSITION_OVERVIEW,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { AllPracticesTable } from "./AllPracticesTable";
import { composeOverview } from "./compose-overview";

const meta = {
	component: AllPracticesTable,
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
		isLoading: false,
	},
	// The route orders the rows under the sort it holds; the harness does the same so a press on the
	// header reorders the rows here too.
	render: (args) => (
		<Stateful initial={args.sort}>
			{(sort, setSort) => (
				<AllPracticesTable
					{...args}
					groups={sortPracticeGroups(args.groups, args.standings, sort)}
					sort={sort}
					onSortChange={(next) => {
						args.onSortChange(next);
						setSort(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof AllPracticesTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		// What needs attention comes first, and the standing words are the registry's.
		const rows = canvas.getAllByRole("button", { name: /^Open group /u });
		await expect(rows.map((row) => row.getAttribute("aria-label"))).toStrictEqual([
			"Open group Packaging work for review",
			"Open group Communicating in the open",
			"Open group Acting on review feedback",
			"Open group Reviewing a teammate's work constructively",
			"Open group Testing your changes",
		]);
		await expect(canvas.getAllByRole("button", { name: "Needs attention" })).toHaveLength(2);
		await expect(canvas.getByText("Not observed yet")).toBeVisible();
		// A trend reads as words, and a group with none says so in the same place.
		await expect(canvas.getByRole("button", { name: "More difficulties recently" })).toBeVisible();
		await expect(canvas.getAllByText("Not enough to compare yet")).toHaveLength(3);
		const breakdowns = canvas.getAllByRole("list", { name: "Practices by standing" }).map((list) =>
			within(list)
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		);
		await expect(breakdowns[0]).toStrictEqual(["2Needs attention", "1Mixed", "2Going well"]);
		await expect(breakdowns[4]).toStrictEqual(["2Not observed"]);
		// A group with no event shows its name and nothing beneath it.
		await expect(canvas.queryByText("Nothing new since the latest run.")).toBeNull();
		await expect(canvas.getByText("Acting on review feedback").closest("td")?.textContent).toBe(
			"Acting on review feedback",
		);
		// The group is named as the feedback cards name it: its icon and name in its colour, no pill.
		const name = canvas.getByText("Acting on review feedback");
		await expect(name.closest('[data-slot="badge"]')).toBeNull();
		await expect(name.parentElement).toHaveClass("bg-transparent");

		// A practice name in a sentence opens the practice, not the group.
		await userEvent.click(canvas.getByRole("button", { name: "Scope the change to one concern" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
		await expect(args.onOpenGroup).not.toHaveBeenCalled();

		await userEvent.click(canvas.getByRole("button", { name: "Open group Testing your changes" }));
		await expect(args.onOpenGroup).toHaveBeenCalledWith(groups[4]);
	},
};

/**
 * A group's events are one bullet each, every one listed, with the practice as the pill the
 * feedback cards name it with; the marker is the muted grey.
 */
export const ManyEvents: Story = {
	play: async ({ args, canvas }) => {
		const cell = canvas.getByText("Packaging work for review").closest("td");
		if (!cell) {
			throw new Error("The group's name sits in its cell");
		}
		await expect(within(cell).getByRole("list")).toHaveClass("marker:text-muted-foreground");
		const items = within(cell).getAllByRole("listitem");
		await expect(items).toHaveLength(6);
		// Feedback the work fell back on comes first, then new feedback, then the resolutions.
		await expect(items[0]).toHaveTextContent(
			"Say which acceptance criteria are done is back to 0 of 3 clean.",
		);
		await expect(items[1]).toHaveTextContent("Scope the change to one concern has new feedback.");
		// Each reference carries its own "(opens in a new tab)", so the sentence is matched to its
		// first.
		await expect(items[2]).toHaveTextContent(
			/^Describe what changed and why resolved by the work after #22/u,
		);
		const pill = within(cell).getByRole("button", { name: "Scope the change to one concern" });
		await expect(pill).toHaveAttribute("data-slot", "badge");
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
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
			"Describe what changed and why moved to Going well, and the group with it.",
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
			"The group moved to Mixed feedback.",
		);
	},
};

/**
 * The Standing header is the table's one sort: a press reverses it and the header says which way.
 */
export const Sorting: Story = {
	play: async ({ args, canvas }) => {
		const standing = canvas.getByRole("columnheader", { name: "Standing" });
		await expect(standing).toHaveAttribute("aria-sort", "ascending");

		await userEvent.click(within(standing).getByRole("button", { name: "Standing" }));
		await expect(args.onSortChange).toHaveBeenLastCalledWith("desc");
		await expect(standing).toHaveAttribute("aria-sort", "descending");
		const rows = canvas.getAllByRole("button", { name: /^Open group /u });
		await expect(rows[0]).toHaveAccessibleName("Open group Testing your changes");

		await userEvent.click(within(standing).getByRole("button", { name: "Standing" }));
		await expect(standing).toHaveAttribute("aria-sort", "ascending");
	},
};

export const SortedDescending: Story = {
	args: { sort: "desc" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Standing" })).toHaveAttribute(
			"aria-sort",
			"descending",
		);
		const rows = canvas.getAllByRole("button", { name: /^Open group /u });
		await expect(rows[0]).toHaveAccessibleName("Open group Testing your changes");
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
		await expect(canvas.getByText("No practices set up yet.")).toBeVisible();
		await expect(
			canvas.getByText(
				"Practice groups appear here once an admin sets up the practices this workspace reviews.",
			),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("button", { name: /^Open group /u })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		groups: [],
		error: new Error("Practice standings are unavailable right now"),
		onRetry: fn(),
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Could not load your practices")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
