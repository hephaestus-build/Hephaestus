import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { DeliveryBreakdownCard } from "./DeliveryBreakdownCard";
import {
	deliveredCounts,
	practiceReviewOverview,
	readyReviewOverview,
	reviewOverviewScope,
	withheldByReasonCounts,
} from "./fixtures";
import type { ReviewListTarget } from "./review-outcomes";
import type { ReviewCountState } from "./review-states";

const FAILED_DELIVERIES: ReviewListTarget = {
	list: "feedback",
	search: {
		from: reviewOverviewScope.from,
		to: reviewOverviewScope.to,
		state: ["FAILED"],
	},
};

function counted(total: number, stale = false): ReviewCountState {
	return { status: "ready", total, stale };
}

/** A split's rows as "label: count", in their order. */
function rowsOf(canvas: StoryContext["canvas"], label: string) {
	return [...canvas.getByRole("table", { name: label }).querySelectorAll("tr")].map(
		(row) =>
			`${row.querySelector("th")?.textContent ?? ""}: ${row.querySelector("td")?.textContent ?? ""}`,
	);
}

function rateOf(canvas: StoryContext["canvas"], term: RegExp) {
	return canvas.getByText(term, { selector: "[data-rate]" }).parentElement;
}

/** Where a count's link opens, as the search params the Feedback table reads. */
function linkOf(canvas: StoryContext["canvas"], name: string) {
	const url = new URL(canvas.getByRole<HTMLAnchorElement>("link", { name }).href);
	// A list is a repeated key: one value reads as itself, several as the list.
	const search = Object.fromEntries(
		[...new Set(url.searchParams.keys())].map((key) => {
			const values = url.searchParams.getAll(key);
			return [key, values.length > 1 ? values : values[0]];
		}),
	);
	return { path: url.pathname, search };
}

const meta = {
	component: DeliveryBreakdownCard,
	decorators: [
		(Story) => (
			<div className="flex max-w-xl">
				<Story />
			</div>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		scope: reviewOverviewScope,
		range: { label: "Last 30 days", during: "in the last 30 days" },
		overview: readyReviewOverview(),
		delivered: { status: "ready", counts: deliveredCounts, stale: false },
		withheld: { status: "ready", counts: withheldByReasonCounts, stale: false },
		failedDeliveries: { state: counted(1), list: FAILED_DELIVERIES, of: 15 },
	},
	argTypes: {
		overview: { control: false },
		delivered: { control: false },
		withheld: { control: false },
		failedDeliveries: { control: false },
	},
} satisfies Meta<typeof DeliveryBreakdownCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		await expect(rowsOf(canvas, "Delivered and partly delivered")).toEqual([
			"On GitHub: 8",
			"In conversation in Slack: 2",
			"On the practice page: 4",
			"Partly delivered: 1",
		]);
		await expect(rowsOf(canvas, "Withheld by reason")).toEqual([
			"The work moved on: 2",
			"Policy or settings: 1",
			"Rejected: 1",
		]);
		await expect(linkOf(canvas, "8, On GitHub")).toEqual({
			path: "/w/demo/admin/practices/reviews/feedback",
			search: {
				from: reviewOverviewScope.from,
				to: reviewOverviewScope.to,
				state: "DELIVERED",
				location: "GITHUB",
			},
		});
		await expect(linkOf(canvas, "1, Partly delivered").search).toEqual({
			from: reviewOverviewScope.from,
			to: reviewOverviewScope.to,
			partlyDelivered: "true",
		});
		await expect(linkOf(canvas, "2, The work moved on").search).toEqual({
			from: reviewOverviewScope.from,
			to: reviewOverviewScope.to,
			state: "WITHHELD",
			withheldFamily: "WORK_MOVED_ON",
		});
		await expect(linkOf(canvas, "1, Rejected").search.state).toBe("REJECTED");
		// Each split heads its table with its total, which the Feedback tile beside it counts too.
		await expect(
			canvas.getByText("Delivered and partly delivered").nextElementSibling,
		).toHaveTextContent("15");
		await expect(canvas.getByText("Withheld by reason").nextElementSibling).toHaveTextContent("4");
		// The whole row is the link: its words stretch over the row.
		await expect(canvas.getByRole("link", { name: "8, On GitHub" }).closest("tr")).toHaveClass(
			"relative",
		);
		await expect(rateOf(canvas, /^Feedback failed to deliver/u)).toHaveAttribute(
			"data-tone",
			"destructive",
		);
		await expect(linkOf(canvas, "1 of 15 sent").search.state).toBe("FAILED");
		await userEvent.click(
			canvas.getByRole("button", { name: "About delivered and withheld feedback" }),
		);
		await expect(
			await screen.findByRole("dialog", { name: "About delivered and withheld feedback" }),
		).toHaveTextContent(
			"Feedback failed to deliver is the share of the feedback sent that failed. Above 5% it is red.",
		);
	},
};

/** 1 of 20 is 5%: at the mark, not past it, so grey. */
export const RateWithinThreshold: Story = {
	args: { failedDeliveries: { state: counted(1), list: FAILED_DELIVERIES, of: 20 } },
	play: async ({ canvas }) => {
		await expect(rateOf(canvas, /^Feedback failed to deliver/u)).toHaveAttribute(
			"data-tone",
			"secondary",
		);
		canvas.getByText("5%");
	},
};

/** Withheld feedback whose reason is not recorded is a row, but no filter selects it alone. */
export const ReasonNotRecorded: Story = {
	args: {
		withheld: {
			status: "ready",
			counts: [
				...withheldByReasonCounts,
				{ reason: "NOT_KNOWN_TO_THIS_BUILD", description: "A newer reason", count: 1 },
			],
			stale: false,
		},
	},
	play: async ({ canvas }) => {
		await expect(rowsOf(canvas, "Withheld by reason")).toContain("Reason not recorded: 1");
		await expect(canvas.queryByRole("link", { name: /Reason not recorded/u })).toBeNull();
	},
};

export const NothingYet: Story = {
	args: {
		overview: readyReviewOverview({
			...practiceReviewOverview,
			feedback: { ...practiceReviewOverview.feedback, withheld: 0, rejected: 0 },
		}),
		delivered: { status: "ready", counts: { byLocation: [], partlyDelivered: 0 }, stale: false },
		withheld: { status: "ready", counts: [], stale: false },
		failedDeliveries: { state: counted(0), list: FAILED_DELIVERIES, of: 0 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Nothing delivered in the last 30 days");
		canvas.getByText("Nothing withheld in the last 30 days");
		// No count opens an empty list; only the title opens the Feedback tab, as on every card.
		await expect(canvas.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"Delivered and withheld",
		]);
	},
};

export const Loading: Story = {
	args: {
		delivered: { status: "loading" },
		withheld: { status: "loading" },
		failedDeliveries: { state: { status: "loading" }, list: FAILED_DELIVERIES },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Loading delivered feedback by where it appeared…");
		canvas.getByText("Loading withheld feedback by reason…");
		canvas.getByText("Loading feedback that failed to deliver…");
	},
};

/** One split failed: it says so with its retry, and the other still answers. */
export const OneSplitFailed: Story = {
	args: { withheld: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText("We could not load withheld feedback by reason");
		await expect(rowsOf(canvas, "Delivered and partly delivered")).toHaveLength(4);
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

/** The previous range's counts stand in while the new ones load: drained and marked busy. */
export const Stale: Story = {
	args: {
		delivered: { status: "ready", counts: deliveredCounts, stale: true },
		failedDeliveries: { state: counted(1, true), list: FAILED_DELIVERIES, of: 15 },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("table", { name: "Delivered and partly delivered" }),
		).toHaveAttribute("aria-busy", "true");
		await expect(rateOf(canvas, /^Feedback failed to deliver/u)).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "8, On GitHub" })).toBeVisible();
	},
};
