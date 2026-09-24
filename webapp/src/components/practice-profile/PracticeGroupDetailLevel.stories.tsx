import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { PracticeGroup } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { detailPractices } from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { composeNextStep, composeOverview, groupOverviewOf } from "./compose-overview";
import { PracticeGroupDetailLevel } from "./PracticeGroupDetailLevel";

/** What the route composes for the group's level: its slice of the overview and its next step. */
const groupOverview = (groupSlug: string) => ({
	...groupOverviewOf(composeOverview(OVERVIEW_FIXTURE), groupSlug),
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
		group: packagingGroup,
		standing: packagingStanding,
		practices: detailPractices,
		...groupOverview(packagingGroup.slug),
		onOpenPractice: fn(),
		isLoading: false,
		onRetry: fn(),
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
								behind: [{ label: "Practice profile", depth: 0 }],
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

/** The group the overview knows: held rows, a next step and a sentence under one practice. */
export const Default: Story = {
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("What is holding up well"));
		const header = screen.getByRole("heading", { name: packagingGroup.name });
		await expect(header).toBeVisible();
		// The practice count and its ring sit beside the title, in the header.
		const summary = screen.getByText("three practices in this group");
		await expect(summary).toBeVisible();
		await expect(summary.closest("[data-slot='drawer-header']")).not.toBeNull();
		await expect(screen.getByText("Next step")).toBeVisible();
		const nextStep = screen.getByText(/That is the open next step on/u);
		await expect(nextStep).toBeVisible();
		// The practice the next step names, and each practice in the table, is the grey pill.
		await expect(
			within(nextStep).getByRole("button", { name: "Scope the change to one concern" }),
		).toHaveAttribute("data-slot", "badge");
		await expect(
			screen.getByText("Keep changes focused").closest('[data-slot="badge"]'),
		).not.toBeNull();
		// Heph's card is the group's summary over the tabs; the practices open, counted.
		await expect(screen.getByRole("tab", { name: "Practices 3" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.getByRole("tab", { name: "About this group" })).toBeVisible();
		await expect(screen.queryByText(/one concern per change/u)).not.toBeInTheDocument();
		// One practice carries a sentence under its pill, its clean work linked; the others show only
		// the pill.
		await expect(screen.getByText(/^Feedback resolved by the work after/u)).toBeVisible();
		await expect(screen.queryByText("Suggested next step")).not.toBeInTheDocument();
		await userEvent.click(screen.getByRole("button", { name: "Open Keep changes focused" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("small-changes");
	},
};

/**
 * The other tab: where the reader stands in the group, in the registry's words and on the
 * practices counted — the same counts the ring draws — then the catalog's words on the group.
 * The table leaves with the tab.
 */
export const AboutTab: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("tab", { name: "About this group" }));
		await userEvent.click(screen.getByRole("tab", { name: "About this group" }));
		const stand = screen.getByRole("region", { name: "Where you stand" });
		await expect(
			within(stand).getByText(
				"Recent reviews here were mostly problems. Of three practices, one shows mixed feedback, one is going well and one is not observed yet.",
			),
		).toBeVisible();
		await expect(
			within(stand).getByText(
				"Recent reviewed work carried more problems than the stretch before it. Across eight pieces of reviewed work in this group. Two of three practices here had enough evidence to compare. Evidence spans 12 days.",
			),
		).toBeVisible();
		// The badge and the chip are printed beside their sentences, so neither is a tooltip's trigger.
		await expect(within(stand).queryByRole("button")).toBeNull();
		await expect(screen.getByRole("heading", { name: "About this group" })).toBeVisible();
		await expect(screen.getByText(/one concern per change/u)).toBeVisible();
		await waitFor(async () =>
			expect(
				screen.queryByRole("table", { name: "Practices in this group" }),
			).not.toBeInTheDocument(),
		);
		// Heph's card stays over the tabs whichever is shown.
		await expect(screen.getByText("What is holding up well")).toBeVisible();
	},
};

/**
 * The Standing header sorts the practices: needs attention first, pressed again the other way
 * round.
 */
export const Sorting: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByText("Practices in this group"));
		const names = () =>
			practiceRows().map((row) => within(row).getByRole("button", { name: /^Open /u }).textContent);
		await expect(practiceRows()).toHaveLength(3);
		const standing = screen.getByRole("button", { name: "Standing" });
		await expect(standing.closest("th")).toHaveAttribute("aria-sort", "ascending");
		// Mixed feedback, then going well, then not observed.
		await expect(practiceRows()[0]).toHaveTextContent("Keep changes focused");
		await expect(practiceRows()[2]).toHaveTextContent("Link the issue the change resolves");
		await userEvent.click(standing);
		await expect(standing.closest("th")).toHaveAttribute("aria-sort", "descending");
		await expect(practiceRows()[0]).toHaveTextContent("Link the issue the change resolves");
		await expect(practiceRows()[2]).toHaveTextContent("Keep changes focused");
		await expect(names()).toHaveLength(3);
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
		await expectSettledVisible(await screen.findByRole("tab", { name: "Practices 3" }));
		await expect(screen.getByText("Describe what changed and why")).toBeVisible();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByText("Next step")).toBeNull();
		await expect(screen.getByText("pull requests")).toBeVisible();
	},
};

/**
 * A group with nothing reviewed yet: the table says so, the card keeps only its footer, and
 * the About tab claims no basis and no direction over no verdict.
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
		await expectSettledVisible(await screen.findByText("No practices here yet."));
		await expect(screen.getByRole("tab", { name: "Practices 0" })).toBeVisible();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByText("Next step")).toBeNull();
		await expect(screen.getByText("pull requests")).toBeVisible();
		await userEvent.click(screen.getByRole("tab", { name: "About this group" }));
		const stand = screen.getByRole("region", { name: "Where you stand" });
		await expect(
			within(stand).getByText("No practice in this group has a current verdict for you."),
		).toBeVisible();
		await expect(within(stand).queryByText(/^Of /u)).toBeNull();
		// The header's chip is the only one: no direction is claimed over no verdict.
		await expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
	},
};

/**
 * With a practice's level open over this one, its row keeps the accent bar on its leading edge,
 * as the open group's row does in the "All practices" table; nothing else about the row changes.
 */
export const OpenPracticeRow: Story = {
	args: { openPracticeSlug: "small-changes" },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Practices in this group"));
		const openRows = practiceRows().filter((row) => row.dataset.state === "open");
		await expect(openRows).toHaveLength(1);
		await expect(openRows[0]).toHaveTextContent("Keep changes focused");
		await expect(openRows[0]).toHaveClass("data-[state=open]:[&>td:first-child]:before:bg-mentor");
	},
};

/**
 * Heph's card, the rail and the table each draw their own shape, so nothing jumps when the level
 * lands.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async () => {
		const table = await screen.findByRole("table", { name: "Practices in this group" });
		await expectSettledVisible(table);
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(screen.getByText("Heph")).toBeVisible();
		await expect(screen.queryByText("What is holding up well")).toBeNull();
		await expect(screen.queryByRole("tab")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { error: new Error("Unavailable") },
	play: async () => {
		await expectSettledVisible(
			await screen.findByText("Could not load your standing for Packaging work for review"),
		);
	},
};

export const Missing: Story = {
	args: { group: undefined, standing: undefined, practices: undefined },
};

/**
 * At 320px the standing summary wraps under the title, its full width, and the tabs keep to the
 * rail.
 */
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expectNoPanelOverflow(panel);
		const summary = screen.getByText("three practices in this group");
		const title = screen.getByRole("heading", { name: packagingGroup.name });
		await expect(summary.getBoundingClientRect().top).toBeGreaterThanOrEqual(
			title.getBoundingClientRect().bottom,
		);
	},
};
