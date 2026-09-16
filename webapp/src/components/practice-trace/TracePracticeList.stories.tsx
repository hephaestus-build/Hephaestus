import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { practiceTraceEntries, tracedSignals } from "./story-mock-data";
import { TracePracticeList } from "./TracePracticeList";

/**
 * Every practice this workspace runs against a kind of work, including the ones that stayed quiet.
 *
 * Measured and delivered are two axes, never collapsed into one badge: a practice can be reviewed
 * and still, by design, say nothing — and the reader has to see both halves to know which one to go
 * and change.
 */
const meta = {
	title: "Practice trace/Practice outcomes",
	component: TracePracticeList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		practices: practiceTraceEntries,
		signals: tracedSignals,
		artifactKind: "scm.pull_request",
	},
} satisfies Meta<typeof TracePracticeList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One row per outcome the server can send, plus the measured-but-deliberately-silent case. */
export const EveryOutcome: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not reached")).toBeVisible();
		await expect(canvas.getByText("The review ended before reaching this practice.")).toBeVisible();
		await expect(canvas.getByText("2 measurements, none sent")).toBeVisible();
		await expect(
			canvas.getByText("This feedback is waiting for a person to approve it."),
		).toBeVisible();
		await expect(canvas.getAllByText("Reviewed")).toHaveLength(2);
		await expect(canvas.getByText("Review before sending")).toBeVisible();
	},
};

/** The same signal name recurs on every revision, so a row has to point at the occurrence. */
export const SameSignalTwice: Story = {
	play: async ({ canvas }) => {
		const jumpFrom = (practiceName: string) => {
			const row = canvas.getByText(practiceName).closest('[role="listitem"]');
			if (!(row instanceof HTMLElement)) {
				throw new Error(`No row for ${practiceName}`);
			}
			return within(row).getByRole("link", { name: "Jump to: New commits pushed" });
		};

		await expect(jumpFrom("Small, reviewable changes")).toHaveAttribute(
			"href",
			"#occurrence-sig-sync-9ab3c410",
		);
		await expect(jumpFrom("Drafts are not left open")).toHaveAttribute(
			"href",
			"#occurrence-sig-sync-b71d0a52",
		);
	},
};

/** What a version skew between the recorder and this endpoint looks like from the page. */
export const OccurrenceMissingFromTheTimeline: Story = {
	args: {
		practices: practiceTraceEntries.map((entry) =>
			entry.practiceSlug === "small-changes"
				? { ...entry, occasionedById: "sig-from-a-newer-server" }
				: entry,
		),
	},
	play: async ({ canvas }) => {
		const row = canvas.getByText("Small, reviewable changes").closest('[role="listitem"]');
		if (!(row instanceof HTMLElement)) {
			throw new Error("No row for the skipped practice");
		}

		// Scoped to "Rests on": the same signal name also appears in the row's "Starts a review on"
		// list, and a row-wide query cannot say which of the two is the fallback under test.
		const restsOn = within(row).getByText("Rests on").closest("div");
		if (!(restsOn instanceof HTMLElement)) {
			throw new Error("No 'Rests on' entry on the row");
		}
		await expect(within(restsOn).getByText("scm.pull_request.synchronized")).toBeVisible();
		await expect(within(row).queryByRole("link", { name: /^Jump to:/u })).toBeNull();

		// The rows whose occurrence does resolve are untouched, so this is a fallback and not a mode.
		const resolved = canvas.getByText("Drafts are not left open").closest('[role="listitem"]');
		if (!(resolved instanceof HTMLElement)) {
			throw new Error("No row for the lapsed practice");
		}
		await expect(
			within(resolved).getByRole("link", { name: "Jump to: New commits pushed" }),
		).toBeVisible();
	},
};

/** No practice covers this kind of work, said in the reader's words rather than as `scm.issue`. */
export const NoPracticeCoversThisKind: Story = {
	args: { practices: [], artifactKind: "scm.issue" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice covers this kind of work")).toBeVisible();
		await expect(canvas.getByText(/runs no practice against issue/u)).toBeVisible();
	},
};
