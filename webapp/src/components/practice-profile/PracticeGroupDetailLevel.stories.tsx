import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { GroupRef } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { text, work } from "@/components/practice-vocabulary/feedback-text";
import { withPageBehind, withProvider } from "@/stories/decorators";
import { expectFoldOnTableColumns } from "@/stories/fold-columns";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { detailPractices, focusedChanges } from "@/stories/practice-detail-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
	pullRequest as storyPullRequest,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { SAMPLE_PATTERN_NONE_CLEAN } from "@/stories/practice-profile-samples";
import { composeGroupOverview } from "./compose-overview";
import { PracticeGroupDetailLevel } from "./PracticeGroupDetailLevel";

/** What the route composes for the group's level: a sentence per practice in it. */
const groupOverview = (groupSlug: string) => composeGroupOverview(OVERVIEW_FIXTURE, groupSlug);

/**
 * The level has no page of its own, so every story mounts a real drawer over a real page.
 */
const meta = {
	component: PracticeGroupDetailLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind, withProvider("GITHUB")],
	args: {
		path: { behind: [{ label: "Practice profile", depth: 0 }], onClose: fn() },
		group: packagingGroup,
		standing: packagingStanding,
		practices: detailPractices,
		...groupOverview(packagingGroup.slug),
		onOpenPractice: fn(),
		isLoading: false,
		onRetry: fn(),
	},
	argTypes: {
		// The close is the drawer stack's, which the render holds; only the crumbs come from here.
		path: { control: false },
	},
	// Stateful, so Escape, an outside press and the header control really close the panel instead
	// of firing an inert spy.
	render: (args) => (
		<Stateful initial={[{ kind: "practice-group", id: packagingGroup.slug }]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
					{(_entry, level) => (
						<PracticeGroupDetailLevel
							{...args}
							nested={level.nested}
							path={{
								behind: args.path.behind,
								onClose: (depth) => setStack(stack.slice(0, depth)),
							}}
						/>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeGroupDetailLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A group the overview does not mention: no practice carries a sentence. */
const otherGroup: GroupRef = {
	...packagingGroup,
	slug: "testing-discipline",
	name: "Testing your changes",
	icon: "TestTube",
	color: "emerald",
};

/** The table's own rows: its first body, without the header or a fold under it. */
const practiceRows = () =>
	within(
		screen.getByRole("table", { name: "Practices in this group" }).querySelector("tbody") ??
			document.body,
	).getAllByRole("row");

export const Default: Story = {
	play: async ({ args }) => {
		const table = await screen.findByRole("table", { name: "Practices in this group" });
		await expectSettledVisible(table);
		const header = screen.getByRole("heading", { name: packagingGroup.name });
		await expect(header).toBeVisible();
		// The heading names the group as the All practice groups table does, its icon and name in the
		// group's color, only larger: no tinted square.
		const mark = header.parentElement?.querySelector("svg");
		await expect(mark).not.toBeNull();
		const name = within(header).getByText(packagingGroup.name);
		await expect(getComputedStyle(name).color).toBe(getComputedStyle(mark ?? header).color);
		await expect(getComputedStyle(name).color).not.toBe(getComputedStyle(document.body).color);
		// The header's three-line limit can cut the name short, so the full name is its title.
		await expect(name).toHaveAttribute("title", packagingGroup.name);
		await expect(getComputedStyle(mark?.parentElement ?? header).backgroundColor).toBe(
			"rgba(0, 0, 0, 0)",
		);
		await expect(screen.getByText("Group")).toBeVisible();
		// The practice count and its ring sit beside the title, over the line that closes the
		// header, and the tabs start under it.
		const summary = screen.getByText("Five practices in this group");
		await expect(summary).toBeVisible();
		// The standing names the work it is read from in its sentence only, not beside its badge.
		await expect(screen.queryByText(/^from \d+ pieces? of work$/u)).toBeNull();
		const [line] = within(screen.getByRole("dialog")).getAllByRole("separator");
		if (!line) {
			throw new Error("Expected the line under the header.");
		}
		await expect(summary.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			line.getBoundingClientRect().top,
		);
		// No summary card: no Hephaestus mark, no held rows, no next step, no reviewed work.
		await expect(screen.queryByRole("img", { name: "Ask Hephaestus" })).toBeNull();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByText("Next step")).toBeNull();
		// One column: no tabs over the practices.
		await expect(screen.queryByRole("tab")).toBeNull();
		// A practice the overview mentions carries its sentence under its pill, its clean work
		// linked.
		await expect(screen.getByText(/^Feedback resolved by the work after/u)).toBeVisible();
		await expect(screen.queryByText("Suggested next step")).not.toBeInTheDocument();
		await userEvent.click(
			screen.getByRole("button", { name: `Open practice ${focusedChanges.name}` }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith(focusedChanges.slug);
	},
};

/**
 * Where the reader stands in the group is the header's badge, trend and ring; the level adds no
 * "Where you stand" section over the practices.
 */
export const Standing: Story = {
	play: async () => {
		await expectSettledVisible(
			await screen.findByRole("table", { name: "Practices in this group" }),
		);
		await expect(screen.queryByRole("region", { name: "Where you stand" })).toBeNull();
	},
};

/**
 * The Standing header sorts the practices: needs attention first, pressed again the other way
 * round.
 */
export const Sorting: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByText("Practices in this group"));
		await expect(practiceRows()).toHaveLength(5);
		const standing = screen.getByRole("button", { name: "Standing" });
		await expect(standing.closest("th")).toHaveAttribute("aria-sort", "ascending");
		// Needs attention, then mixed feedback, then going well.
		await expect(practiceRows()[0]).toHaveTextContent("Needs attention");
		await expect(practiceRows()[0]).toHaveTextContent("Keep the diff reviewable in one sitting");
		await expect(practiceRows()[1]).toHaveTextContent(focusedChanges.name);
		await expect(practiceRows()[4]).toHaveTextContent("Going well");
		await expect(practiceRows()[4]).toHaveTextContent(
			"Write commit subjects a reviewer can follow",
		);
		await userEvent.click(standing);
		await expect(standing.closest("th")).toHaveAttribute("aria-sort", "descending");
		await expect(practiceRows()[0]).toHaveTextContent("Going well");
		await expect(practiceRows()[0]).toHaveTextContent("Mark the change ready and link its issue");
		await expect(practiceRows()[4]).toHaveTextContent("Needs attention");
	},
};

/** A group the overview does not mention: its practices are listed without a sentence. */
export const OtherGroup: Story = {
	args: {
		group: otherGroup,
		standing: { ...packagingStanding, groupSlug: otherGroup.slug, standing: "STRENGTH" },
		...groupOverview(otherGroup.slug),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Describe what changed and why"));
		await expect(screen.getByRole("heading", { name: otherGroup.name })).toBeVisible();
		await expect(screen.queryByText(/^Feedback resolved by the work after/u)).toBeNull();
	},
};

/**
 * A group with no practices, or with every one switched off: the profile lists no such group, so
 * only a link lands here. The level names the group and says it has no practices — no card, no
 * tabs, no standing over nothing.
 */
export const NoPractices: Story = {
	args: {
		group: otherGroup,
		standing: {
			...packagingStanding,
			groupSlug: otherGroup.slug,
			standing: "NOT_OBSERVED",
			direction: null,
			trendSupport: null,
		},
		practices: [],
		...groupOverview(otherGroup.slug),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("No reviewed practices"));
		await expect(screen.getByRole("heading", { name: otherGroup.name })).toBeVisible();
		await expect(screen.queryByRole("tab")).toBeNull();
		await expect(screen.queryByRole("table")).toBeNull();
		await expect(screen.queryByText(/^Not reviewed/u)).toBeNull();
		await expect(screen.queryByText("We could not find this practice group")).toBeNull();
	},
};

/**
 * The group's own description, as the workspace wrote it, under the header. It is the level's lead,
 * set as a practice's why it matters is: body size, in the ink of the heading.
 */
export const WithItsDescription: Story = {
	args: {
		standing: {
			...packagingStanding,
			groupDescription: "Work a reviewer can start on without asking first.",
		},
	},
	play: async () => {
		const description = await screen.findByText(
			"Work a reviewer can start on without asking first.",
		);
		await expectSettledVisible(description);
		// The description is the foreground; the heading over it wears the group's color.
		await expect(getComputedStyle(description).color).toBe(
			getComputedStyle(screen.getByText("Practices in this group")).color,
		);
		// `text-sm`, the body size of the practice surfaces.
		const rootSize = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
		await expect(Number.parseFloat(getComputedStyle(description).fontSize)).toBe(0.875 * rootSize);
		await expect(screen.getByText("Practices in this group")).toBeVisible();
	},
};

/**
 * With a practice's level open over this one, its row keeps the accent bar on its leading edge,
 * as the open group's row does in the "All practice groups" table; nothing else about the row changes.
 */
export const OpenPracticeRow: Story = {
	args: { openPracticeSlug: focusedChanges.slug },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Practices in this group"));
		const openRows = practiceRows().filter((row) => row.dataset.state === "open");
		const [openRow] = openRows;
		if (!openRow) {
			throw new Error("Exactly one practice row is open");
		}
		await expect(openRows).toHaveLength(1);
		await expect(openRow).toHaveTextContent(focusedChanges.name);
	},
};

/** The table draws its own shape, so nothing jumps when the level lands. */
export const Loading: Story = {
	args: { isLoading: true, standing: undefined, practices: undefined },
	play: async () => {
		const table = await screen.findByRole("table", { name: "Practices in this group" });
		await expectSettledVisible(table);
		await expect(table).toHaveAttribute("aria-busy", "true");
		// Nothing is claimed about a standing that has not loaded.
		await expect(screen.queryByRole("button", { name: "No standing yet" })).toBeNull();
		await expect(screen.queryByRole("img", { name: "Ask Hephaestus" })).toBeNull();
		await expect(screen.queryByRole("tab")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { error: new Error("Unavailable") },
	play: async ({ args }) => {
		await expectSettledVisible(
			await screen.findByText("We could not load your standing for Packaging work for review"),
		);
		await userEvent.click(screen.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

/** A slug the profile does not list, such as a stale address. */
export const Missing: Story = {
	args: { group: undefined, standing: undefined, practices: undefined },
	play: async () => {
		await expectSettledVisible(await screen.findByText("We could not find this practice group"));
	},
};

/** At 320px the standing summary wraps under the title and nothing leaves the panel. */
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expectNoPanelOverflow(panel);
		const summary = screen.getByText("Five practices in this group");
		const title = screen.getByRole("heading", { name: packagingGroup.name });
		await expect(summary.getBoundingClientRect().top).toBeGreaterThanOrEqual(
			title.getBoundingClientRect().bottom,
		);
	},
};

/** Below `sm` the standing summary spans the header under the title rather than hugging its legend. */
export const MobileSummarySpansHeader: Story = {
	parameters: { viewport: { defaultViewport: "mobile" }, chromatic: { viewports: [375] } },
	play: async () => {
		await settledDrawerPanel();
		// At 320px the legend alone fills the column, so a box that hugs it only shows from here up.
		// The summary's text block sits in the box, and the heading's row in the title column.
		const box = screen.getByText("Five practices in this group").parentElement?.parentElement;
		const titleColumn = screen.getByRole("heading", { name: packagingGroup.name }).parentElement
			?.parentElement;
		if (!box || !titleColumn) {
			throw new Error("The summary box or the title column is missing.");
		}
		await expect(
			Math.abs(box.getBoundingClientRect().width - titleColumn.getBoundingClientRect().width),
		).toBeLessThanOrEqual(1);
	},
};

// --- The pattern label and the folds ------------------------------------------------------------

const [patterned] = detailPractices;
const neverAppliedPractices = detailPractices.map((practice, index) =>
	index === detailPractices.length - 1
		? { ...practice, standing: "NO_OPPORTUNITY" as const, doesNotApplyOnly: true }
		: practice,
);
const pullRequest = (number: number) => work(storyPullRequest(number));
/** A row's sentence that names the work already, as a fall back does. */
const patternedSentences = patterned && {
	...groupOverview(packagingGroup.slug).practiceSentences,
	[patterned.slug]: [
		[
			text("Back to none of 3 clean in a row after "),
			pullRequest(12),
			text(" and "),
			pullRequest(14),
		],
	],
};
const patternArgs = {
	practices: neverAppliedPractices,
	practiceSentences: patternedSentences,
	patterns: patterned ? { [patterned.slug]: SAMPLE_PATTERN_NONE_CLEAN } : {},
};

/**
 * The pattern: "Has open feedback", a plain label beside the practice's name, on the line of its standing. The sentence under it names the work, so the label
 * does not count it again. It is no control; the row's own link opens the practice. The practice
 * that never applied folds under the table, with no standing yet.
 */
export const Pattern: Story = {
	args: patternArgs,
	play: async () => {
		const label = await screen.findByText("Has open feedback");
		await expectSettledVisible(label);
		await expect(label.closest("button")).toBeNull();
		await expect(screen.queryByRole("button", { name: /Has open feedback/u })).toBeNull();
		await expectSettledVisible(screen.getByRole("button", { name: FOLD }));
	},
};

/** Without a sentence naming the work, the label reads the same. */
export const PatternWithoutSentence: Story = {
	args: { ...patternArgs, practiceSentences: {} },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Has open feedback"));
	},
};

export const PatternDark: Story = {
	args: Pattern.args,
	globals: { theme: "dark" },
};

export const PatternPhone: Story = {
	args: Pattern.args,
	globals: { viewport: { value: "mobile" } },
	play: async () => {
		const table = await screen.findByRole("table", { name: "Practices in this group" });
		await expectSettledVisible(table);
		// The progress words wrap inside the label rather than run past the table's edge.
		const frame = table.closest("[class*='overflow-hidden']") ?? table;
		for (const words of within(table).getAllByText(/in a row/u)) {
			await expect(words.getBoundingClientRect().right).toBeLessThanOrEqual(
				frame.getBoundingClientRect().right,
			);
			const label = words.parentElement;
			await expect(label?.scrollWidth ?? 0).toBeLessThanOrEqual((label?.clientWidth ?? 0) + 1);
		}
	},
};

/** The one fold under the table: practices with no standing yet, whatever the reason. */
const FOLD = "Practices with no standing yet";

/** The group's practices with one never applied and one no review has reviewed yet. */
const foldedPractices = detailPractices.map((practice, index) => {
	if (index === detailPractices.length - 1) {
		return { ...practice, standing: "NO_OPPORTUNITY" as const, doesNotApplyOnly: true };
	}
	if (index === detailPractices.length - 2) {
		return {
			...practice,
			standing: "NOT_OBSERVED" as const,
			direction: null,
			trendSupport: null,
		};
	}
	return practice;
});
const notReviewedName = detailPractices.at(-2)?.name ?? "";

/**
 * A practice no review has reviewed yet and one that never applied leave the table for the same
 * folded line: to a developer, both have no standing yet. Each still opens from there.
 */
export const NoStandingYetPractices: Story = {
	args: { practices: foldedPractices },
	play: async ({ args }) => {
		const trigger = await screen.findByRole("button", { name: FOLD });
		await expectSettledVisible(trigger);
		await expect(practiceRows()).toHaveLength(3);
		await expect(screen.queryByText("Does not apply to your work yet")).toBeNull();
		await userEvent.click(trigger);
		// Opened, the fold's rows join the same table, each with the one badge alone.
		await expect(screen.getAllByRole("table")).toHaveLength(1);
		const folded = screen.getByRole("rowgroup", { name: FOLD });
		await expect(within(folded).getAllByText("No standing yet")).toHaveLength(2);
		await expect(within(folded).queryByText("Not reviewed")).toBeNull();
		await expect(within(folded).queryByText(/compare/u)).toBeNull();
		await expectFoldOnTableColumns(practiceRows()[0] ?? null, folded);
		await userEvent.click(
			within(folded).getByRole("button", { name: `Open practice ${notReviewedName}` }),
		);
		await expect(args.onOpenPractice).toHaveBeenCalledWith(detailPractices.at(-2)?.slug);
	},
};

/**
 * No practice of the group has a standing yet. The group has practices, so the table does not
 * claim it has none: the fold stands open and its own sentence says why, and the header reads as
 * the rows do.
 */
export const OnlyNoStandingYetPractices: Story = {
	args: {
		standing: {
			...packagingStanding,
			standing: "NOT_OBSERVED",
			direction: null,
			trendSupport: null,
		},
		practices: detailPractices.map((practice) => ({
			...practice,
			standing: "NOT_OBSERVED" as const,
			direction: null,
			trendSupport: null,
		})),
	},
	play: async () => {
		const trigger = await screen.findByRole("button", { name: FOLD });
		await expectSettledVisible(trigger);
		await expect(trigger).toHaveAttribute("aria-expanded", "true");
		await expect(screen.queryByText("No practices yet")).toBeNull();
		const folded = screen.getByRole("rowgroup", { name: FOLD });
		// The group's five practices, one badge each.
		await expect(within(folded).getAllByText("No standing yet")).toHaveLength(5);
		// The header's badge says what the rows say: five rows and the header. Nothing says "Not reviewed".
		await expect(screen.getAllByRole("button", { name: "No standing yet" })).toHaveLength(6);
		await expect(screen.queryByText("Not reviewed")).toBeNull();
		// The header's trend chip says there is nothing to compare yet; the folded rows stay badge-only.
		await expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
	},
};

/** Opened, every folded row sits on the columns of the rows above. */
export const NoStandingYetPracticesOpen: Story = {
	args: { practices: foldedPractices },
	globals: { viewport: { value: "desktop" } },
	play: async () => {
		const trigger = await screen.findByRole("button", { name: FOLD });
		await expectSettledVisible(trigger);
		await userEvent.click(trigger);
		await expectFoldOnTableColumns(
			practiceRows()[0] ?? null,
			screen.getByRole("rowgroup", { name: FOLD }),
		);
	},
};

export const NoStandingYetPracticesDark: Story = {
	...NoStandingYetPracticesOpen,
	globals: { theme: "dark" },
};

export const NoStandingYetPracticesPhone: Story = {
	...NoStandingYetPracticesOpen,
	globals: { viewport: { value: "phone390" } },
};
