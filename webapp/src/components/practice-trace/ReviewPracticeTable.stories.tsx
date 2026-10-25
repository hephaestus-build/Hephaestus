import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { InlineLink } from "@/components/common/InlineLink";
import { ROW_LINK_CLASS, RowLinkArrow } from "@/components/practice-vocabulary/PracticeTable";
import { groups } from "@/stories/practice-profile-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { notMetOwnResult as notMet, ownItemTrace, ownResults, reusedOwnResult } from "./fixtures";
import { ReviewPracticeTable, ReviewPracticeTableSkeleton } from "./ReviewPracticeTable";

const GROUP_OF_PRACTICE: Record<string, string> = {
	"thin-controllers": "review-ready-work",
	"descriptive-pull-requests": "review-ready-work",
	"product-language": "communication",
};

const groupOf = (practiceSlug: string) =>
	groups.find((group) => group.slug === GROUP_OF_PRACTICE[practiceSlug]);

const meta = {
	component: ReviewPracticeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		results: ownResults,
		reviewId: ownItemTrace.review.id,
		groupOf,
		filters: {},
		onFiltersChange: fn(),
		onOpenPractice: fn(),
		observationLink: (result) => (
			<InlineLink
				href={`#observation-${result.id}`}
				className={ROW_LINK_CLASS}
				aria-label={`Open observation for ${result.practiceName}`}
			>
				Open observation
				<RowLinkArrow />
			</InlineLink>
		),
		earlierReviewLink: () => <InlineLink href="#earlier">Open the earlier review</InlineLink>,
		emptyMessage: "This review recorded no observation about you.",
	},
	argTypes: {
		results: { control: false },
		groupOf: { control: false },
		earlierReviewLink: { control: false },
	},
} satisfies Meta<typeof ReviewPracticeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * With a link to each observation in its practice, the Observation column holds only the link:
 * the practice level shows the words, problems and next steps.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: `Open observation for ${notMet.practiceName}` }),
		).toBeVisible();
		await expect(
			canvas.queryByText("Two controllers query the database directly"),
		).not.toBeInTheDocument();
		await expect(canvas.queryByText("2 problems")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Major")).not.toBeInTheDocument();
		// The practices stand under a gray heading per group, groups by name: no row repeats its group.
		const table = canvas.getByRole("table", { name: "Every practice in this review" });
		const headings = [...table.querySelectorAll("th[scope=rowgroup]")].map(
			(heading) => heading.textContent,
		);
		await expect(headings).toStrictEqual([
			"Communicating in the open",
			"Packaging work for review",
		]);
		await expect(canvas.queryByText("in")).not.toBeInTheDocument();
		const thinRow = canvas.getByRole("button", { name: notMet.practiceName }).closest("tbody");
		await expect(thinRow?.querySelector("th")).toHaveTextContent("Packaging work for review");
		// The group's name at the size of a feedback card's group name.
		const groupName = canvas.getByText("Packaging work for review");
		await expect(getComputedStyle(groupName).fontSize).toBe("14px");
	},
};

/** Without such a link, the column names the observation and counts its problems. */
export const WithoutObservationLinks: Story = {
	args: { observationLink: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Two controllers query the database directly")).toBeVisible();
		await expect(canvas.getByText("2 problems")).toBeVisible();
	},
};

/** The Outcome head is plain words: the rows keep the review's order and the filter narrows them. */
export const FilterByOutcome: Story = {
	args: { filters: { outcome: ["NOT_MET"] } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(
			canvas.queryByRole("button", { name: /Show problems first/u }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("columnheader", { name: "Outcome" })).not.toHaveAttribute(
			"aria-sort",
		);
		await expect(canvas.getByText(notMet.practiceName)).toBeVisible();
		await expect(canvas.queryByText("Product language")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Descriptive pull requests")).not.toBeInTheDocument();
		// The admin Observations tab's Outcome facet, with its Reset.
		await userEvent.click(canvas.getByRole("button", { name: /^Reset/u }));
		await expect(args.onFiltersChange).toHaveBeenCalledWith({});
	},
};

/** A press anywhere on a row follows its Open observation link; a press on the pill is the pill's. */
export const RowOpensItsObservation: Story = {
	play: async ({ canvas, userEvent, args }) => {
		const link = canvas.getByRole("link", { name: `Open observation for ${notMet.practiceName}` });
		const row = link.closest("tr");
		if (row === null) {
			throw new Error("The link sits in a row.");
		}
		let followed = 0;
		link.addEventListener("click", (event) => {
			event.preventDefault();
			followed += 1;
		});
		const outcomeCell = row.querySelector("td");
		if (outcomeCell === null) {
			throw new Error("The row has cells.");
		}
		await userEvent.click(outcomeCell);
		await expect(followed).toBe(1);
		await userEvent.click(within(row).getByRole("button", { name: notMet.practiceName }));
		await expect(followed).toBe(1);
		await expect(args.onOpenPractice).toHaveBeenCalledWith(notMet.practiceSlug);
	},
};

/**
 * Back from an observation, its row is the one marked with the accent bar on its leading edge, and
 * says it is the current one.
 */
export const MarksTheObservationOpenedLast: Story = {
	play: async ({ canvas, userEvent }) => {
		const link = canvas.getByRole("link", { name: `Open observation for ${notMet.practiceName}` });
		link.addEventListener("click", (event) => event.preventDefault());
		const row = link.closest("tr");
		if (row === null) {
			throw new Error("The link sits in a row.");
		}
		await expect(row).not.toHaveAttribute("aria-current");
		await userEvent.click(link);
		await expect(row).toHaveAttribute("aria-current", "true");
		const bar = getComputedStyle(row.querySelector("td") ?? row, "::before");
		await expect(bar.backgroundColor).not.toBe("rgba(0, 0, 0, 0)");
		// Another observation opened from its row takes the mark over.
		const other = canvas
			.getByRole("link", { name: "Open observation for Descriptive pull requests" })
			.closest("tr");
		other
			?.querySelector("[data-observation-link] a")
			?.addEventListener("click", (event) => event.preventDefault());
		const otherCell = other?.querySelector("td");
		if (otherCell === null || otherCell === undefined) {
			throw new Error("Expected the other row.");
		}
		await userEvent.click(otherCell);
		await expect(other).toHaveAttribute("aria-current", "true");
		await expect(row).not.toHaveAttribute("aria-current");
	},
};

/** After a reload with the observation open over the review, its row is marked from the address. */
export const MarksTheObservationFromTheAddress: Story = {
	args: { openedObservationId: notMet.id },
	play: async ({ canvas }) => {
		const row = canvas
			.getByRole("link", { name: `Open observation for ${notMet.practiceName}` })
			.closest("tr");
		await expect(row).toHaveAttribute("aria-current", "true");
	},
};

export const ReusedFromAnEarlierReview: Story = {
	args: { results: [reusedOwnResult] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open the earlier review" })).toBeVisible();
	},
};

export const MarkedIncorrect: Story = {
	args: { results: [{ ...notMet, markedIncorrectAt: daysBefore(1) }] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Marked incorrect")).toBeVisible();
	},
};

export const ExactObservationLink: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: `Open observation for ${notMet.practiceName}` }),
		).toHaveAttribute("href", `#observation-${notMet.id}`);
		await expect(canvas.queryByRole("button", { name: /^Show evidence/u })).toBeNull();
	},
};

export const Filtered: Story = {
	args: { filters: { practice: "thin" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Thin controllers")).toBeVisible();
		await expect(canvas.queryByText("Product language")).not.toBeInTheDocument();
	},
};

export const NarrowViewport: Story = {
	globals: { viewport: { value: "reflow" } },
	parameters: { chromatic: { viewports: [320] } },
};

export const Empty: Story = {
	args: { results: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This review recorded no observation about you.")).toBeVisible();
	},
};

export const Loading: Story = {
	render: () => <ReviewPracticeTableSkeleton rows={3} />,
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("table", { name: "Every practice in this review" }),
		).toHaveAttribute("aria-busy", "true");
	},
};
