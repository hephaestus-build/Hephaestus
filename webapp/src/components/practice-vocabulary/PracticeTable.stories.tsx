import type { Meta, StoryObj } from "@storybook/react-vite";
import { ClipboardCheckIcon } from "lucide-react";
import { expect, fn, userEvent, within } from "storybook/test";

import type { PracticeStanding } from "@/api/types.gen";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell } from "@/components/ui/table";
import { practicesByGroup } from "@/stories/practice-profile-story-mock-data";
import { Stateful } from "@/stories/stateful";

import {
	DEFAULT_PRACTICE_GROUP_SORT,
	type SortDirection,
	sortByStanding,
} from "./practice-group-list-order";
import { PracticePill } from "./PracticePill";
import { PracticeTable, PracticeTableRow, StandingCell, SubjectCell } from "./PracticeTable";

const rows = practicesByGroup["review-ready-work"] ?? [];

/** What a row opens; the route pushes the practice's level. */
const onOpen = fn<(slug: string) => void>();

const loadingRow = (
	<>
		<TableCell>
			<Skeleton className="h-5 w-32 rounded-full" />
		</TableCell>
		<TableCell>
			<Skeleton className="h-5 w-56 rounded-full" />
		</TableCell>
		<TableCell />
	</>
);

/**
 * The frame every practice table on the profile shares: the sortable Standing column, the subject
 * and its sentence, the row's own "Open …" link. The caller renders its rows from the cells
 * exported beside it and orders them under the sort the header shows.
 */
const meta = {
	component: PracticeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		"aria-label": "Practices",
		sort: DEFAULT_PRACTICE_GROUP_SORT,
		onSortChange: fn(),
		subjectHead: "Practice",
		rows,
		rowKey: (practice: PracticeStanding) => practice.slug,
		renderRow: (practice: PracticeStanding) => (
			<PracticeTableRow
				link={{
					text: "Open practice",
					name: practice.name,
					onOpen: () => onOpen(practice.slug),
				}}
			>
				<StandingCell standing={practice.standing} scope="practice" />
				<SubjectCell badge={<PracticePill name={practice.name} />} />
			</PracticeTableRow>
		),
		empty: {
			icon: <ClipboardCheckIcon />,
			title: "No practices here yet.",
			description: "Practices appear here once an admin adds them to this group.",
		},
		loadingRow,
	},
	render: (args) => (
		<Stateful initial={args.sort}>
			{(sort: SortDirection, setSort) => (
				<PracticeTable
					{...args}
					rows={sortByStanding(
						args.rows,
						sort,
						(practice) => practice.standing,
						(left, right) => left.name.localeCompare(right.name),
					)}
					sort={sort}
					onSortChange={(next) => {
						args.onSortChange(next);
						setSort(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeTable<PracticeStanding>>;

export default meta;
type Story = StoryObj<typeof meta>;

/** What needs attention first; a press on Standing turns the order around. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const links = () => canvas.getAllByRole("button", { name: /^Open practice /u });
		// Each name starts with the link's visible words, so a reader who speaks them reaches it
		// (WCAG 2.2 SC 2.5.3); the practice after them tells the rows apart.
		for (const link of links()) {
			await expect(link).toHaveTextContent(/^Open practice$/u);
		}
		const names = () => links().map((link) => link.getAttribute("aria-label"));
		await expect(names()).toStrictEqual([
			"Open practice Keep the diff reviewable in one sitting",
			"Open practice Scope the change to one concern",
			"Open practice Describe what changed and why",
			"Open practice Mark the change ready and link its issue",
			"Open practice Write commit subjects a reviewer can follow",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "Standing" }));
		await expect(args.onSortChange).toHaveBeenCalledWith("desc");
		await expect(names()[0]).toBe("Open practice Mark the change ready and link its issue");
	},
};

/**
 * The standing badge and the trend chip are buttons only so a keyboard reaches their sentences;
 * they answer nothing of their own, so a pointer's press on either is the row's. A keyboard's
 * Enter on one is not: that reader came for the sentence, and the row's own link is theirs.
 */
export const OpensFromTheStandingCell: Story = {
	play: async ({ canvas }) => {
		onOpen.mockClear();
		const row = canvas
			.getByRole("button", { name: "Open practice Describe what changed and why" })
			.closest("tr");
		if (!row) {
			throw new Error("Expected the row around its link.");
		}
		const badge = within(row).getByRole("button", { name: "Mixed feedback" });
		await userEvent.click(badge);
		await expect(onOpen).toHaveBeenLastCalledWith("describe-what-and-why");
		await userEvent.click(within(row).getByRole("button", { name: "Not enough to compare yet" }));
		await expect(onOpen).toHaveBeenCalledTimes(2);

		onOpen.mockClear();
		badge.focus();
		await userEvent.keyboard("{Enter}");
		await expect(onOpen).not.toHaveBeenCalled();
		// The row's own link is the keyboard path, and Enter on it still opens the row.
		within(row)
			.getByRole("button", { name: "Open practice Describe what changed and why" })
			.focus();
		await userEvent.keyboard("{Enter}");
		await expect(onOpen).toHaveBeenCalledWith("describe-what-and-why");
	},
};

/** The first load: a few skeleton rows in the caller's own shape, and the table says it is busy. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Practices" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByRole("button", { name: /^Open /u })).toBeNull();
	},
};

/**
 * No rows: the empty block every practice surface shows, under the same header, so the columns
 * keep their place.
 */
export const Empty: Story = {
	args: { rows: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Standing" })).toBeVisible();
		// One cell across the three columns a row has without `heads`.
		await expect(canvas.getByRole("cell")).toHaveAttribute("colspan", "3");
	},
};

/** The empty block spans the columns the extra heads add as well, however many each covers. */
export const EmptyUnderHeads: Story = {
	args: { rows: [], heads: [{ label: "Trend", span: 2 }] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Trend" })).toHaveAttribute(
			"colspan",
			"2",
		);
		await expect(canvas.getByRole("cell")).toHaveAttribute("colspan", "5");
	},
};
