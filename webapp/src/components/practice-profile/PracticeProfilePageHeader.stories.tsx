import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticeProfilePageHeader } from "./PracticeProfilePageHeader";

const meta = {
	component: PracticeProfilePageHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		latestRun: {
			jobId: "run-2026-09-09",
			at: new Date("2026-09-09T14:10:00"),
			reviewedWork: {
				id: "C01/p1",
				label: "#releases",
				url: "https://example.slack.com/archives/C01/p1",
				kind: ARTIFACT_KIND.conversationThread,
			},
		},
		counts: { STRENGTH: 7, MIXED: 3, DEVELOPING: 2, NO_OPPORTUNITY: 1, NOT_OBSERVED: 3 },
		practiceCount: 16,
		groupCount: 5,
		isLoading: false,
	},
} satisfies Meta<typeof PracticeProfilePageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.getByText(/resolves once your work comes back clean/u)).toBeVisible();
		await expect(canvas.getByText("9 September, 2:10 pm")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /^#releases/u })).toBeVisible();
		await expect(canvas.getByText("16 practices in 5 groups")).toBeVisible();

		// Every standing with a count is listed, in the registry's order: what needs attention first,
		// the same order the ring draws its arcs and the tables sort by.
		const items = canvas.getAllByRole("listitem");
		await expect(items.map((item) => item.textContent)).toEqual([
			"2Needs attention",
			"3Mixed",
			"7Going well",
			"1Nothing to report",
			"3Not observed",
		]);

		// One destination, and the card is all of it: the words are the keyboard path and their
		// pseudo-element covers the card for the pointer. The level it opens is an address.
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-groups%3Aall"),
		);
	},
};

/** A standing at zero drops out of the legend rather than reading "0 Needs attention". */
export const SomeStandingsAbsent: Story = {
	args: {
		counts: { STRENGTH: 9, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 7 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.queryByText("Needs attention")).toBeNull();
	},
};

/**
 * Nothing reviewed yet: every practice stands at "Not observed", so the ring is one grey arc and
 * the legend one line, and the card still names its destination.
 */
export const NothingObservedYet: Story = {
	args: {
		latestRun: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 16 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"16Not observed",
		]);
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * A work label longer than the chip is cut at the chip's edge rather than widening the page; the
 * link still carries all of it.
 */
export const LongWorkLabel: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: {
		latestRun: {
			jobId: "run-2026-09-09",
			at: new Date("2026-09-09T14:10:00"),
			reviewedWork: {
				id: "queue-retry-policy",
				label: "Queue retry policy for the notification pipeline",
				url: "https://outline.example.com/doc/queue-retry-policy",
				kind: ARTIFACT_KIND.document,
			},
		},
	},
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", {
			name: /^Queue retry policy for the notification pipeline/u,
		});
		const cut = link.parentElement;
		if (!cut) {
			throw new Error("Expected the line the label is cut in.");
		}
		await expect(cut.scrollWidth).toBeGreaterThan(cut.clientWidth);
		await expectNoPageOverflow();
	},
};

/** A workspace with no practices: there is no chip, and the card has nothing to count. */
export const Empty: Story = {
	args: {
		latestRun: undefined,
		counts: { STRENGTH: 0, MIXED: 0, DEVELOPING: 0, NO_OPPORTUNITY: 0, NOT_OBSERVED: 0 },
		practiceCount: 0,
		groupCount: 0,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Latest run")).toBeNull();
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * The title, the introduction and the destination stand while the counts load; only the chip and
 * the counts wait, and no count is shown as zero in the meantime.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice profile");
		await expect(canvas.getByText(/resolves once your work comes back clean/u)).toBeVisible();
		await expect(canvas.queryByText("Latest run")).toBeNull();
		await expect(canvas.queryByText("16 practices in 5 groups")).toBeNull();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

/**
 * The five standings at 320 px: the legend wraps and the destination takes the row under it,
 * without widening the page.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
