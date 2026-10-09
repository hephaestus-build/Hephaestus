import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { PracticeGroup } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { detailPractices, focusedChanges } from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { precedes } from "@/test/dom";

import { composeGroupOverview, composeNextStep } from "./compose-overview";
import { PracticeGroupDetailLevel } from "./PracticeGroupDetailLevel";

/** What the route composes for the group's level: its slice of the overview and its next step. */
const groupOverview = (groupSlug: string) => ({
	...composeGroupOverview(OVERVIEW_FIXTURE, groupSlug),
	nextStep: composeNextStep(ALL_FEEDBACK_CARDS, groupSlug),
});

/**
 * The level has no page of its own, so every story mounts a real drawer over a real page.
 */
const meta = {
	component: PracticeGroupDetailLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
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
				<DetailDrawerStack
					stack={stack}
					size="detailWide"
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
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

/** A group the overview does not mention: nothing held, no next step, only the reviewed work. */
const otherGroup: PracticeGroup = {
	...packagingGroup,
	slug: "testing-discipline",
	name: "Testing your changes",
	icon: "TestTube",
	color: "emerald",
	description: "Prove a change works before a reviewer has to take your word for it.",
};

const practiceRows = () =>
	within(screen.getByRole("table", { name: "Practices in this group" }))
		.getAllByRole("row")
		.slice(1);

/**
 * The group the overview knows, in one column: the catalog's words on the group lead, then Heph's
 * card with held rows and a next step, where the reader stands, and the practices with a sentence
 * under each.
 */
export const Default: Story = {
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("What is holding up well"));
		const lead = screen.getByText(/one concern per change/u);
		await expect(lead).toBeVisible();
		await expect(precedes(lead, screen.getByText("What is holding up well"))).toBe(true);
		await expect(
			precedes(
				screen.getByRole("region", { name: "Where you stand" }),
				screen.getByRole("table", { name: "Practices in this group" }),
			),
		).toBe(true);
		const header = screen.getByRole("heading", { name: packagingGroup.name });
		await expect(header).toBeVisible();
		await expect(screen.getByText("Group")).toBeVisible();
		// The practice count and its ring sit beside the title, over the line that closes the
		// header; Heph's card starts under it, so the two do not touch.
		const summary = screen.getByText("five practices in this group");
		await expect(summary).toBeVisible();
		// The standing names the work it is read from beside its badge.
		await expect(screen.getAllByText("from 4 pieces of work")[0]).toBeVisible();
		const [line] = within(screen.getByRole("dialog")).getAllByRole("separator");
		if (!line) {
			throw new Error("Expected the line under the header.");
		}
		await expect(summary.getBoundingClientRect().bottom).toBeLessThanOrEqual(
			line.getBoundingClientRect().top,
		);
		await expect(
			screen.getByRole("img", { name: "Heph, AI mentor" }).getBoundingClientRect().top,
		).toBeGreaterThan(line.getBoundingClientRect().bottom);
		await expect(screen.getByText("Next step")).toBeVisible();
		const nextStep = screen.getByText(/That is the open next step on/u);
		await expect(nextStep).toBeVisible();
		// The practice the next step names opens the practice.
		await userEvent.click(
			within(nextStep).getByRole("button", { name: "Scope the change to one concern" }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("scope-one-reviewable-change");
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
 * Where the reader stands in the group, in the registry's words and on the practices counted —
 * the same counts the ring draws — over the practices themselves.
 */
export const Standing: Story = {
	play: async () => {
		const stand = await screen.findByRole("region", { name: "Where you stand" });
		await expectSettledVisible(stand);
		await expect(
			within(stand).getByText(
				"Recent reviews here mostly found problems. Read from four pieces of work. Of five practices, two need attention, one shows mixed feedback and two are going well.",
			),
		).toBeVisible();
		await expect(within(stand).getByText("Needs attention")).toBeVisible();
		await expect(within(stand).getByText("More difficulties recently")).toBeVisible();
		await expect(
			within(stand).getByText(
				"Recent reviewed work carried more problems than the stretch before it. Across eight pieces of reviewed work in this group. Two of three practices here had enough evidence to compare. Evidence spans 12 days.",
			),
		).toBeVisible();
		// The badge and the chip are printed beside their sentences, so neither is a tooltip's trigger.
		await expect(within(stand).queryByRole("button")).toBeNull();
		await expect(screen.getByRole("table", { name: "Practices in this group" })).toBeVisible();
	},
};

/** A group nothing is written about yet opens on Heph's card, with no line saying so. */
export const NoDescription: Story = {
	args: { group: { ...packagingGroup, description: undefined } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("What is holding up well"));
		await expect(screen.queryByText(/one concern per change/u)).not.toBeInTheDocument();
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

/**
 * A group the overview does not mention: nothing held there, no open card to take a next step
 * from, so Heph's card keeps only its footer — a block with nothing to say is left out.
 */
export const OtherGroup: Story = {
	args: {
		group: otherGroup,
		standing: { ...packagingStanding, groupSlug: otherGroup.slug, standing: "STRENGTH" },
		...groupOverview(otherGroup.slug),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Describe what changed and why"));
		await expect(
			screen.getByText("Prove a change works before a reviewer has to take your word for it."),
		).toBeVisible();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByText("Next step")).toBeNull();
		await expect(screen.getByText("pull requests")).toBeVisible();
	},
};

/**
 * A group with nothing reviewed yet: the table says so, the card keeps only its footer, and
 * where the reader stands claims no basis and no direction over no verdict.
 */
export const NoPractices: Story = {
	args: {
		group: otherGroup,
		standing: {
			...packagingStanding,
			groupSlug: otherGroup.slug,
			standing: "NOT_OBSERVED",
			direction: undefined,
			trendSupport: undefined,
		},
		practices: [],
		...groupOverview(otherGroup.slug),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("No practices yet"));
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByText("Next step")).toBeNull();
		await expect(screen.getByText("pull requests")).toBeVisible();
		const stand = screen.getByRole("region", { name: "Where you stand" });
		await expect(
			within(stand).getByText("No practice in this group has been observed in your work yet."),
		).toBeVisible();
		await expect(within(stand).queryByText(/^Of /u)).toBeNull();
		// The header's chip is the only one: no direction is claimed over no verdict.
		await expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
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

/** Heph's card and the table each draw their own shape, so nothing jumps when the level lands. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async () => {
		const table = await screen.findByRole("table", { name: "Practices in this group" });
		await expectSettledVisible(table);
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(screen.getByRole("img", { name: "Heph, AI mentor" })).toBeVisible();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
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
		const summary = screen.getByText("five practices in this group");
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
		const box = screen.getByText("five practices in this group").parentElement?.parentElement;
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
