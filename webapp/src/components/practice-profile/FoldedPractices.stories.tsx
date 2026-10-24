import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { PracticeStanding } from "@/api/types.gen";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import {
	PracticeTable,
	PracticeTableRow,
	StandingCell,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";

import { expectFoldOnTableColumns } from "@/stories/fold-columns";

import { SAMPLE_NEVER_APPLIED, SAMPLE_NOT_OBSERVED } from "@/stories/practice-profile-samples";
import { practiceStandingOf } from "@/stories/practice-profile-story-mock-data";
import { FoldedPractices, type FoldedPracticesProps, FoldedStandingCell } from "./FoldedPractices";
import { isNeverApplied, noStandingYetReason } from "./practice-folds";

const onOpen = fn();

const standingsOf = (
	items: readonly { slug: string; name: string }[],
	standing: PracticeStanding["standing"],
): PracticeStanding[] => items.map((item) => practiceStandingOf({ ...item, standing }));

const SHOWN_ROWS: PracticeStanding[] = [
	practiceStandingOf({
		slug: "describe-change",
		name: "Describe what changed and why",
		standing: "DEVELOPING",
		direction: "IMPROVING",
	}),
	practiceStandingOf({
		slug: "small-diff",
		name: "Keep the diff reviewable in one sitting",
		standing: "STRENGTH",
	}),
];
/**
 * Practices with no standing yet for every reason: three never applied, two nobody reviewed, and
 * one reviewed but not judged.
 */
const NO_STANDING_YET_ROWS = [
	...standingsOf(SAMPLE_NEVER_APPLIED, "NO_OPPORTUNITY").map((practice) => ({
		...practice,
		doesNotApplyOnly: true,
	})),
	...standingsOf(SAMPLE_NOT_OBSERVED, "NOT_OBSERVED"),
	practiceStandingOf({
		slug: "edge-cases",
		name: "Name the edge cases you checked",
		standing: "NO_OPPORTUNITY",
		doesNotApplyOnly: false,
	}),
];

function PracticeRow({
	practice,
	folded = false,
}: {
	practice: PracticeStanding;
	folded?: boolean;
}) {
	return (
		<PracticeTableRow
			link={{
				text: "Open practice",
				name: practice.name,
				onOpen: () => {
					onOpen(practice.slug);
				},
			}}
		>
			{folded ? (
				<FoldedStandingCell
					reason={noStandingYetReason(practice.standing, "practice", isNeverApplied(practice))}
				/>
			) : (
				<StandingCell
					standing={practice.standing}
					direction={practice.direction ?? undefined}
					scope="practice"
				/>
			)}
			<SubjectCell badge={<PracticePill name={practice.name} />} />
		</PracticeTableRow>
	);
}

type FoldStoryArgs = Omit<FoldedPracticesProps, "columns" | "children"> & {
	practices: readonly PracticeStanding[];
};

/**
 * The fold as a group's level draws it: the practice table, and under its rows, in the same table,
 * the fold's toggle row and its rows in their plain state.
 */
function FoldStory({ practices, ...fold }: FoldStoryArgs) {
	return (
		<PracticeTable
			aria-label="Practices in this group"
			sort="desc"
			onSortChange={fn()}
			subjectHead="Practice"
			rows={SHOWN_ROWS}
			rowKey={(practice) => practice.slug}
			renderRow={(practice) => <PracticeRow practice={practice} />}
			empty={{ icon: null, title: "", description: "" }}
			sections={(columns) => (
				<FoldedPractices {...fold} columns={columns}>
					{practices.map((practice) => (
						<PracticeRow key={practice.slug} practice={practice} folded />
					))}
				</FoldedPractices>
			)}
		/>
	);
}

/**
 * Practices or groups with no standing yet, whether they never applied or nobody reviewed them,
 * folded into one row at the end of their table instead of reading as a gap. The row counts nothing. Opened, its rows follow it in the
 * same table, on the table's own columns, each in its plain state, and each opens as any row does.
 */
const meta = {
	component: FoldStory,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		count: NO_STANDING_YET_ROWS.length,
		noun: "practice",
		practices: NO_STANDING_YET_ROWS,
	},
	argTypes: { practices: { control: false } },
} satisfies Meta<typeof FoldStory>;

export default meta;
type Story = StoryObj<typeof meta>;

const FOLD = "Practices with no standing yet";

export const Folded: Story = {
	play: async ({ canvas, userEvent }) => {
		const trigger = canvas.getByRole("button", { name: FOLD });
		await expect(trigger).toHaveAttribute("aria-expanded", "false");
		// The row names the fold and counts nothing.
		await expect(trigger.closest("tr")?.textContent).not.toMatch(/\d/u);
		// A plain disclosure: at least 32 px high, and no border of its own.
		await expect(trigger.getBoundingClientRect().height).toBeGreaterThanOrEqual(32);
		await expect(getComputedStyle(trigger).borderTopWidth).toBe("0px");
		await userEvent.click(trigger);
		await expect(
			canvas.getByText(/has given these practices a standing in your work\. This is not a gap\./u),
		).toBeVisible();
		// One table: the fold adds no second one and no second header.
		await expect(canvas.getAllByRole("table")).toHaveLength(1);
		await expect(canvas.getAllByRole("columnheader")).toHaveLength(3);
		const rows = canvas.getByRole("rowgroup", { name: FOLD });
		await userEvent.click(
			within(rows).getByRole("button", { name: "Open practice Document the public API" }),
		);
		await expect(onOpen).toHaveBeenCalledWith("write-api-docs");
	},
};

export const Open: Story = {
	args: { defaultOpen: true },
	play: async ({ canvas }) => {
		const rows = canvas.getByRole("rowgroup", { name: FOLD });
		// Each row in its plain state, the badge alone: no trend note under it. Whatever the reason,
		// the badge says what the fold says; only its sentence differs.
		// Three never applied, two not reviewed, one not judged: six rows, one badge each.
		await expect(within(rows).getAllByText("No standing yet")).toHaveLength(6);
		await expect(within(rows).getByText("Name the edge cases you checked")).toBeVisible();
		await expect(within(rows).queryByText("Does not apply to your work yet")).toBeNull();
		await expect(within(rows).queryByText("Not reviewed")).toBeNull();
		await expect(within(rows).queryByText("Nothing to report yet")).toBeNull();
		await expect(within(rows).queryByText(/compare/u)).toBeNull();
	},
};

/** Opened, every folded row's cells start where the cells of the rows above start. */
export const OpenOnTableColumns: Story = {
	args: { defaultOpen: true },
	globals: { viewport: { value: "desktop" } },
	play: async ({ canvas }) => {
		const shown = canvas.getAllByRole("row").find((row) => row.textContent.includes("Describe"));
		await expectFoldOnTableColumns(shown ?? null, canvas.getByRole("rowgroup", { name: FOLD }));
	},
};

export const Dark: Story = { args: { defaultOpen: true }, globals: { theme: "dark" } };

export const Phone: Story = {
	args: { defaultOpen: true },
	globals: { viewport: { value: "phone390" } },
};
