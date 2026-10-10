import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import {
	AUTOMATION,
	LARGE_PEOPLE,
	PEOPLE,
	peopleOf,
	readyPeople,
	REPOSITORIES,
	TEAMS,
	WORKSPACE_WORK_LOG,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { WorkspaceActivityPage } from "./WorkspaceActivityPage";

const onCopy = fn(async () => {
	/* the copy is the route's */
});

const meta = {
	component: WorkspaceActivityPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		period: { kind: "preset", preset: "30d" },
		onPeriodChange: fn(),
		team: undefined,
		onTeamChange: fn(),
		repo: [],
		onRepoChange: fn(),
		order: { sort: "contributions", desc: true },
		onOrderChange: fn(),
		people: readyPeople(peopleOf(PEOPLE, { automation: AUTOMATION, highlights: true })),
		facets: { teams: TEAMS, repositories: REPOSITORIES },
		timeline: {
			status: "ready",
			stale: false,
			items: WORKSPACE_WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
			onCopy,
		},
	},
} satisfies Meta<typeof WorkspaceActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const headings = canvas
			.getAllByRole("heading", { level: 2 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual(["Highlights", "People", "Automation", "Timeline"]);
		await expect(canvas.getByText(/^History since .* for 2 of 2 repositories\.$/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("combobox", { name: "Team: Everyone" }));
		const options = within(await settledPopup()).getAllByRole("option");
		await expect(options.map((option) => option.textContent)).toStrictEqual([
			"Everyone",
			"Platform",
			"Platform / Payments",
			"Web",
		]);
		await userEvent.click(screen.getByRole("option", { name: "Platform / Payments" }));
		await expect(args.onTeamChange).toHaveBeenCalledWith("payments");
	},
};

/** Automation stays out of the people and their positions. */
export const AutomationApart: Story = {
	play: async ({ canvas }) => {
		const people = canvas.getByRole("table", { name: "People" });
		await expect(within(people).queryByText("dependabot[bot]")).not.toBeInTheDocument();
		await expect(canvas.getByText(/^Bot account · 14 contributions$/u)).toBeVisible();
		await expect(canvas.getByText(/^Treated as automation · 4 contributions$/u)).toBeVisible();
	},
};

/** 250 people: the table renders 50 rows, and more as its end scrolls into view. */
export const LargeWorkspace: Story = {
	args: { people: readyPeople(peopleOf(LARGE_PEOPLE)) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("250 people")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Show more people" })).toBeVisible();
	},
};

/** While the next period loads, everything that counts it is marked as the previous one's. */
export const Stale: Story = {
	args: {
		people: {
			status: "ready",
			people: peopleOf(PEOPLE, { automation: AUTOMATION, highlights: true }),
			stale: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		for (const name of ["Highlights", "Automation"]) {
			const region = canvas.getByRole("region", { name });
			await expect(region.querySelector("[aria-busy='true']")).not.toBeNull();
		}
	},
};

/** Some repositories' history is complete: the note says since when, and for how many. */
export const PartialHistory: Story = {
	args: {
		people: readyPeople({
			...peopleOf(PEOPLE),
			coverage: { since: daysBefore(400), completeRepositories: 1, totalRepositories: 2 },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^History since .* for 1 of 2 repositories\.$/u)).toBeVisible();
	},
};

/** No repository's history is complete yet, so the counts can be low, and the page says so. */
export const NoCompleteHistory: Story = {
	args: {
		people: readyPeople({
			...peopleOf(PEOPLE),
			coverage: { completeRepositories: 0, totalRepositories: 2 },
		}),
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"The history of 2 of 2 repositories is not complete yet, so the counts can be low.",
			),
		).toBeVisible();
	},
};

/** A workspace with no teams has no team to pick. */
export const NoTeams: Story = {
	args: { facets: { teams: [], repositories: REPOSITORIES } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: /^Team/u })).not.toBeInTheDocument();
	},
};

export const OneTeam: Story = {
	args: { team: "payments" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Team: Platform / Payments" })).toBeVisible();
	},
};

/** Nobody contributed in the period; the period and the team can still change. */
export const Empty: Story = {
	args: { people: readyPeople(peopleOf([])) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No contributions in this range")).toBeVisible();
		await expect(canvas.queryByRole("heading", { name: "Highlights" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("combobox", { name: "Team: Everyone" })).toBeVisible();
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: /Merge requests/u })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** The highlights hold their place while the people load. */
export const Loading: Story = {
	args: { people: { status: "loading" }, facets: undefined, timeline: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.getByRole("heading", { name: "Highlights" })).toBeVisible();
	},
};

const retry = fn();

export const Failed: Story = {
	args: {
		team: "gone",
		people: { status: "error", error: new Error("Team not found"), onRetry: retry },
		facets: undefined,
	},
	play: async ({ canvas, userEvent }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("We could not load people");
		await userEvent.click(within(alert).getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
		// The team still says what the page asked for, so the reader can pick another.
		await expect(canvas.getByRole("combobox", { name: "Team: gone" })).toBeVisible();
	},
};
