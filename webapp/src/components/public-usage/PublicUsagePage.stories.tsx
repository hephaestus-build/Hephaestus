import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { PublicUsagePeriod } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";

import { PublicUsagePage, type PublicUsagePageState } from "./PublicUsagePage";

const day = (iso: string) => new Date(`${iso}T00:00:00Z`);

const SUMMER: PublicUsagePeriod = {
	period: "2026-04",
	firstDay: day("2026-04-01"),
	lastDay: day("2026-09-30"),
	releasedOn: day("2026-10-14"),
	status: "PUBLISHED",
	figures: {
		workspaces: 40,
		reviewedPeople: 1200,
		reviewedWork: 18_400,
		practiceReviews: 96_400,
		feedbackDelivered: 31_900,
	},
};

const WINTER: PublicUsagePeriod = {
	period: "2025-10",
	firstDay: day("2025-10-01"),
	lastDay: day("2026-03-31"),
	releasedOn: day("2026-04-14"),
	status: "PUBLISHED",
	figures: {
		workspaces: 30,
		reviewedPeople: 900,
		reviewedWork: 12_100,
		practiceReviews: 61_000,
	},
};

const QUIET: PublicUsagePeriod = {
	period: "2025-04",
	firstDay: day("2025-04-01"),
	lastDay: day("2025-09-30"),
	releasedOn: day("2025-10-14"),
	status: "NOT_ENOUGH_ACTIVITY",
	figures: {},
};

type ReadyState = Extract<PublicUsagePageState, { status: "ready" }>;

/** The selector keeps its own choice here, as the route does in the address. */
function StatefulPage(state: ReadyState) {
	return (
		<Stateful initial={state.selected}>
			{(selected, setSelected) => (
				<PublicUsagePage
					state={{
						...state,
						selected,
						onSelectPeriod: (period) => {
							state.onSelectPeriod(period);
							setSelected(period);
						},
					}}
				/>
			)}
		</Stateful>
	);
}

const meta = {
	component: PublicUsagePage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", periods: [SUMMER], selected: SUMMER.period, onSelectPeriod: fn() },
	},
} satisfies Meta<typeof PublicUsagePage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One released half-year: five figures with their definitions, and no series yet. */
export const Released: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "April – September 2026" })).toBeVisible();
		await expect(canvas.getByText("96,400")).toBeVisible();
		await expect(
			canvas.getByText(
				"Different people with at least one observation recorded in this half-year, each counted once.",
			),
		).toBeVisible();
		await expect(canvas.queryByRole("list", { name: /by half-year/u })).toBeNull();
		await expect(canvas.queryByRole("combobox", { name: "Half-year" })).toBeNull();
	},
};

/**
 * Three released half-years. Each figure shows a bar for each one; a figure that a half-year does not
 * publish is a hatched gap, never a bar of zero.
 */
export const History: Story = {
	args: {
		state: {
			status: "ready",
			periods: [SUMMER, WINTER, QUIET],
			selected: SUMMER.period,
			onSelectPeriod: fn(),
		},
	},
	play: async ({ canvas, userEvent }) => {
		const feedback = canvas.getByText("Practice feedback delivered").closest("div");
		if (feedback === null) {
			throw new Error("no feedback figure");
		}
		// The bars run oldest first, as the list does, and their ends name the first and the last half-year.
		// en-GB writes September short as "Sep" or "Sept", by ICU version.
		await expect(within(feedback).getByText(/^Apr – Sept? 2025$/u)).toBeVisible();
		await expect(within(feedback).getByText(/^Apr – Sept? 2026$/u)).toBeVisible();
		await userEvent.click(within(feedback).getByText(/^Each half-year/u, { selector: "summary" }));
		const series = canvas.getByRole("list", { name: "Practice feedback delivered by half-year" });
		const rows = within(series).getAllByRole("listitem");
		await expect(rows.map((row) => row.textContent)).toStrictEqual([
			"April – September 2025Not enough activity to publish.",
			"October 2025 – March 2026Not published",
			"April – September 2026at least 31,900",
		]);
		await expect(rows[2]).toHaveAttribute("aria-current", "true");
		await expect(rows[0]).not.toHaveAttribute("aria-current");
	},
};

/** The history at phone width in dark mode: the figures stack, and each half-year stays readable. */
export const HistoryOnAPhone: Story = {
	args: History.args,
	globals: { theme: "dark" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "April – September 2026" })).toBeVisible();
		const workspaces = canvas.getByText("Workspaces").closest("div");
		if (workspaces === null) {
			throw new Error("no workspaces figure");
		}
		await userEvent.click(
			within(workspaces).getByText(/^Each half-year/u, { selector: "summary" }),
		);
		await expect(canvas.getByRole("list", { name: "Workspaces by half-year" })).toHaveTextContent(
			"October 2025 – March 2026at least 30",
		);
	},
};

/** Selecting a half-year shows it; the route keeps the choice in the address. */
export const SelectAHalfYear: Story = {
	args: History.args,
	render: (args) =>
		args.state.status === "ready" ? (
			<StatefulPage {...args.state} />
		) : (
			<PublicUsagePage state={args.state} />
		),
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Half-year" }));
		await userEvent.click(await screen.findByRole("option", { name: "October 2025 – March 2026" }));
		if (args.state.status === "ready") {
			await expect(args.state.onSelectPeriod).toHaveBeenCalledWith("2025-10");
		}
		await expect(canvas.getByRole("heading", { name: "October 2025 – March 2026" })).toBeVisible();
	},
};

/** Some figures of a half-year are hidden: each reads Not published, without a reason. */
export const PartlyPublished: Story = {
	args: {
		state: {
			status: "ready",
			periods: [SUMMER, WINTER],
			selected: WINTER.period,
			onSelectPeriod: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "October 2025 – March 2026" })).toBeVisible();
		const feedback = canvas.getByText("Practice feedback delivered").closest("div");
		if (feedback === null) {
			throw new Error("no feedback figure");
		}
		await expect(within(feedback).getAllByText("Not published")[0]).toBeVisible();
		const developers = canvas.getByText("Developers whose work was reviewed").closest("div");
		if (developers === null) {
			throw new Error("no developers figure");
		}
		await expect(within(developers).getByText("900")).toBeVisible();
		await expect(canvas.getByText("12,100")).toBeVisible();
	},
};

/** An administrator withdrew the whole half-year: every figure reads Not published, without a reason. */
export const Withdrawn: Story = {
	args: {
		state: {
			status: "ready",
			periods: [{ ...SUMMER, status: "NOT_PUBLISHED", figures: {} }],
			selected: SUMMER.period,
			onSelectPeriod: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "April – September 2026" })).toBeVisible();
		await expect(canvas.getAllByText("Not published")).toHaveLength(5);
		await expect(canvas.queryAllByText("at least")).toHaveLength(0);
	},
};

/** A half-year below the floors of 5 workspaces and 5 developers publishes no figure. */
export const NotEnoughActivity: Story = {
	args: {
		state: { status: "ready", periods: [QUIET], selected: QUIET.period, onSelectPeriod: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not enough activity to publish.")).toBeVisible();
		await expect(canvas.queryByText("Workspaces")).toBeNull();
	},
};

/** The page is on, but no half-year has been released yet. */
export const NothingReleasedYet: Story = {
	args: { state: { status: "ready", periods: [], selected: "", onSelectPeriod: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/No half-year is released yet/u)).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await canvas.findByText("Loading the figures…");
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the figures")).toBeVisible();
		await expect(canvas.getByRole("button", { name: /Retry/u })).toBeVisible();
	},
};

/** The operator has not turned the page on. */
export const Unavailable: Story = {
	args: { state: { status: "unavailable" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This server does not publish its figures.")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Read the privacy notice" })).toBeVisible();
	},
};
