import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ReviewObservation } from "@/api/types.gen";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	manyObservations,
	practiceCounts,
	reviewOverviewScope,
	workspacePractices,
} from "./fixtures";
import { PracticeLevel } from "./PracticeLevel";
import { practiceLevel } from "./review-levels";
import { REVIEW_PREVIEW_SIZE } from "./review-search";
import type { ReviewSectionState } from "./review-states";

const SLUG = "thin-controllers";

const THIN_CONTROLLERS = workspacePractices.find((practice) => practice.slug === SLUG);
const COUNTS = practiceCounts.find((counts) => counts.practiceSlug === SLUG);
if (!THIN_CONTROLLERS || !COUNTS) {
	throw new Error("The fixtures no longer cover thin-controllers");
}

/** The practice's most recent observations, as a preview of `total`. */
function ready(total: number): ReviewSectionState<ReviewObservation> {
	return {
		status: "ready",
		items: manyObservations(REVIEW_PREVIEW_SIZE).map((observation) => ({
			...observation,
			practiceSlug: SLUG,
			practiceName: THIN_CONTROLLERS?.name ?? SLUG,
		})),
		total,
	};
}

/**
 * One practice as the reviews saw it over the overview's range: the autonomy it runs with, how its
 * observations turned out, what became of the feedback citing it, and what it observed most
 * recently. Every count opens the rows it counts. The footer edits the practice's definition in
 * Practice setup, where "this practice is wrong" is fixed.
 */
const meta = {
	component: PracticeLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		practiceSlug: SLUG,
		practice: THIN_CONTROLLERS,
		rangeLabel: "Last 30 days",
		scope: reviewOverviewScope,
		counts: { status: "ready", counts: COUNTS, stale: false },
		observations: ready(9),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={practiceLevel(args.practiceSlug)} path={args.path} size="detailWide">
			{(level) => <PracticeLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof PracticeLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Thin controllers", level: 2 })).toBeVisible();
		panel.getByText(THIN_CONTROLLERS.whyItMatters ?? "");

		// The autonomy it runs with, and where that is changed.
		panel.getByText("Review before sending");
		await expect(
			panel.getByRole("link", { name: "Change its autonomy in Review settings" }),
		).toHaveAttribute("href", "/w/demo/admin/practices/review");
		const observed = within(panel.getByRole("list", { name: "Observations by outcome" }));
		await expect(observed.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"18 positive outcomes",
			"9 negative outcomes",
			"4 not applicable",
			"2 undetermined",
		]);
		// An observation count opens the list narrowed to this practice as well as the value.
		const negative = new URL(
			observed.getByRole<HTMLAnchorElement>("link", { name: "9 negative outcomes" }).href,
		);
		await expect(negative.searchParams.get("outcome")).toBe('["NEGATIVE"]');
		await expect(negative.searchParams.get("practiceSlug")).toBe(`["${SLUG}"]`);
		// What an admin marked incorrect, out of all of them, and what that share means.
		within(panel.getByRole("list", { name: "Observations checked by an admin" })).getByRole(
			"link",
			{ name: "1 of 33 marked incorrect" },
		);
		panel.getByText(
			"Only observations an admin found wrong are marked; an unmarked one is not confirmed correct.",
		);
		// Feedback opens the feedback citing this practice, by family.
		const cited = within(panel.getByRole("list", { name: "Feedback by delivery" }));
		await expect(cited.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"2 awaiting approval",
			"7 delivered",
			"2 withheld",
			"1 failed to deliver",
		]);
		const awaiting = new URL(
			cited.getByRole<HTMLAnchorElement>("link", { name: "2 awaiting approval" }).href,
		);
		await expect(awaiting.pathname).toBe("/w/demo/admin/practices/reviews/feedback");
		await expect(awaiting.searchParams.get("practiceSlug")).toBe(`["${SLUG}"]`);

		const [firstRow] = within(panel.getByRole("list", { name: "Observations" })).getAllByRole(
			"link",
		);
		if (!firstRow) {
			throw new Error("The level shows no observation");
		}
		await expect(levelsOpenedBy(firstRow)).toEqual([expect.stringMatching(/^observation:/u)]);
		// The list in its own order, newest first — not a ranking the preview did not use.
		const all = new URL(
			panel.getByRole<HTMLAnchorElement>("link", { name: "See all 9 observations" }).href,
		);
		await expect(all.searchParams.get("order")).toBeNull();
		await expect(all.searchParams.get("practiceSlug")).toBe(`["${SLUG}"]`);
		// One primary: edit the definition. Reading it in setup is the secondary.
		await expect(levelsOpenedBy(panel.getByRole("link", { name: "Edit practice" }))).toEqual([
			`practice-edit:${SLUG}`,
		]);
		await expect(
			levelsOpenedBy(panel.getByRole("link", { name: "Open in Practice setup" })),
		).toEqual([`practice:${SLUG}`]);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		within(panel).getByRole("list", { name: "Observations by outcome" });
		await expectNoPanelOverflow(panel);
	},
};

/**
 * Everything it recorded is shown, and the list is still one press away: it is where observations
 * are filtered, paged and compared.
 */
export const EveryObservationShown: Story = {
	args: { observations: ready(REVIEW_PREVIEW_SIZE) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("list", { name: "Observations" })).toBeVisible();
		panel.getByRole("link", { name: `See all ${REVIEW_PREVIEW_SIZE} observations` });
	},
};

/**
 * A practice with no observation in the range is not a practice with zero of everything: it was not
 * checked, and the level says why that may be rather than drawing an empty mix.
 */
export const NotCheckedInRange: Story = {
	args: {
		practiceSlug: "decisions-are-written-down",
		practice: workspacePractices.find((practice) => practice.slug === "decisions-are-written-down"),
		counts: { status: "ready", counts: undefined, stale: false },
		observations: { status: "ready", items: [], total: 0 },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("heading", { name: "Decisions are written down", level: 2 }),
		).toBeVisible();
		await expect(panel.getByText("Not checked in this range")).toBeVisible();
		await expect(panel.queryByRole("list", { name: /by outcome/u })).not.toBeInTheDocument();
		// Setup is still one press away: a practice that never fires is exactly what it is for.
		panel.getByRole("link", { name: "Open in Practice setup" });
	},
};

/**
 * The previous range did not check it: that says nothing about the range just chosen, so the counts
 * wait in their shape rather than claiming it was not checked.
 */
export const StaleNotChecked: Story = {
	args: {
		...NotCheckedInRange.args,
		counts: { status: "ready", counts: undefined, stale: true },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByText("Not checked in this range")).not.toBeInTheDocument();
		await expect(panel.queryByRole("list", { name: /by outcome/u })).not.toBeInTheDocument();
	},
};

/**
 * Opened from a link before the workspace's practices arrived: the counts name it, and the slug
 * stands in only when nothing else can.
 */
export const BeforeThePracticeArrives: Story = {
	args: { practice: undefined },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Thin controllers", level: 2 })).toBeVisible();
		await expect(panel.queryByText(THIN_CONTROLLERS.whyItMatters ?? "")).not.toBeInTheDocument();
	},
};

export const Loading: Story = {
	args: {
		practice: undefined,
		counts: { status: "loading" },
		observations: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName("Loading practice");
		panel.getByText("Loading observations");
		await expect(panel.queryByText("Not checked in this range")).not.toBeInTheDocument();
	},
};

/** The counts failing costs the counts; the observations below still answer. */
export const CountsFailed: Story = {
	args: {
		counts: {
			status: "error",
			error: { status: 500, detail: "Something went wrong." },
			onRetry: fn(),
		},
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load this practice's counts")).toBeVisible();
		panel.getByRole("list", { name: "Observations" });
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.counts.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.counts.onRetry).toHaveBeenCalledOnce();
	},
};

export const ObservationsFailed: Story = {
	args: {
		observations: {
			status: "error",
			error: { status: 503, detail: "The observation index is unavailable." },
			onRetry: fn(),
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load observations")).toBeVisible();
		panel.getByRole("list", { name: "Observations by outcome" });
	},
};
