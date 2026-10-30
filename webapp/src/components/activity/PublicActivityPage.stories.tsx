import type { Meta, StoryObj } from "@storybook/react-vite";
import { useSearch } from "@tanstack/react-router";
import { expect, fn, within } from "storybook/test";
import type { PublicActivityPersonDetail } from "@/api/types.gen";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import type { PeopleTableState } from "./ActivityPeopleTable";

import {
	LARGE_PEOPLE,
	PEOPLE,
	publicActivityOf,
	readyPublicPeople,
	REPOSITORIES,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";
import { precedes } from "@/test/dom";

import {
	publicActivitySearchSchema,
	parsePublicActivityStack,
	parseActivityStack,
	PUBLIC_ACTIVITY_LEVEL_KINDS,
} from "./activity-search";
import { weekStarts } from "./activity-tally";
import { PublicActivityPage, type PublicActivityPageProps } from "./PublicActivityPage";
import { PublicActivityPageSkeleton } from "./PublicActivityPageSkeleton";

const activity = publicActivityOf(PEOPLE);

/** Synthetic independent reads preserve the page fixture's supplied totals and UTC contribution weeks. */
function publicPersonReads(
	people: PeopleTableState,
	login: string,
	provider: PublicActivityPageProps["providerType"],
): PublicActivityPageProps["personActivity"] {
	const unsettled = { status: "loading" as const };
	if (people.status !== "ready") {
		return { person: people, workLog: unsettled, categoryWorkLog: unsettled };
	}
	const row = people.people.people.find(({ person }) => person.login === login);
	if (row === undefined) {
		return {
			person: { status: "error", error: { status: 404 }, onRetry: fn() },
			workLog: unsettled,
			categoryWorkLog: unsettled,
		};
	}
	const { person, counts } = row;
	const detail: PublicActivityPersonDetail = {
		person: {
			login: person.login,
			name: person.name,
			avatarUrl: person.avatarUrl,
			htmlUrl: person.htmlUrl,
		},
		from: people.people.from,
		to: people.people.to,
		counts,
		breakdown: { approvals: counts.pullRequestsReviewed, changeRequests: 0, commentReviews: 0 },
		weeks: row.weeks.map(({ start, contributions }, index) => ({
			start,
			counts: {
				...(index === 0
					? counts
					: {
							contributions: 0,
							pullRequestsOpened: 0,
							pullRequestsMerged: 0,
							pullRequestsReviewed: 0,
							peopleHelped: 0,
							issuesOpened: 0,
							activeWeeks: 0,
						}),
				contributions,
			},
			breakdown: {
				approvals: index === 0 ? counts.pullRequestsReviewed : 0,
				changeRequests: 0,
				commentReviews: 0,
			},
		})),
		repositories: [{ rowKey: "1", repository: { key: "acme/api", name: "api" }, counts }],
		supportedKinds: [
			"PULL_REQUEST_OPENED",
			"PULL_REQUEST_MERGED",
			"REVIEW_APPROVED",
			"REVIEW_CHANGES_REQUESTED",
			"REVIEW_COMMENTED",
			"ISSUE_OPENED",
		],
	};
	const workLog = {
		status: "ready" as const,
		stale: people.stale,
		items: [
			{
				id: "pr:42",
				lastOccurredAt: new Date(people.people.from.getTime() + 3_600_000),
				work: {
					type: "PULL_REQUEST" as const,
					number: 42,
					title: "Keep public activity scoped",
					state: "OPEN" as const,
					isDraft: false,
					htmlUrl:
						provider === "GITLAB"
							? "https://gitlab.com/acme/api/-/merge_requests/42"
							: "https://github.com/acme/api/pull/42",
					repository: { name: "api" },
				},
				actions: [{ kind: "REVIEW_APPROVED" as const, count: 1 }],
			},
		],
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
	};
	return {
		person: { status: "ready", person: detail, stale: people.stale },
		workLog,
		categoryWorkLog: workLog,
	};
}

/** Native URL state owns opening and dismissal, exactly as on the public route. */
function PublicActivityStory(args: PublicActivityPageProps) {
	const search = useSearch({ strict: false });
	const stack = parsePublicActivityStack(publicActivitySearchSchema.parse(search).detail);
	const controls = useDetailStack();
	const login = stack.find(({ target }) => target.kind === "person")?.id ?? "ada";
	return (
		<PublicActivityPage
			{...args}
			detail={{ stack, onClose: controls.close }}
			personActivity={publicPersonReads(
				args.people.status === "ready"
					? { ...args.people, stale: args.people.stale || args.updating }
					: args.people,
				login,
				args.providerType,
			)}
		/>
	);
}

const meta = {
	component: PublicActivityPage,
	parameters: {
		router: { initialUrl: "/" },
		layout: "fullscreen",
		chromatic: { viewports: [320, 1440] },
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		personActivity: publicPersonReads(readyPublicPeople(activity), "ada", "GITHUB"),
		workspaceName: activity.workspaceName,
		providerType: "GITHUB",
		viewer: { status: "signed-out", onSignIn: fn() },
		period: { kind: "preset", preset: "90d" },
		onPeriodChange: fn(),
		order: { sort: "contributions", desc: true },
		onOrderChange: fn(),
		repo: [],
		onRepoChange: fn(),
		repositories: activity.repositories,
		coverage: activity.coverage,
		people: readyPublicPeople(activity),
		updating: false,
		detail: { stack: [], onClose: fn() },
	},
	render: (args) => <PublicActivityStory {...args} />,
} satisfies Meta<typeof PublicActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** What a visitor sees: the people with their figures, and the way to hide oneself. */
export const SignedOut: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Hephaestus activity" }),
		).toBeVisible();
		await expect(
			canvas.getByText(
				/This page shows pull requests, reviews and issues in public repositories\./u,
			),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Sign in" }));
		await expect(
			args.viewer.status === "signed-out" ? args.viewer.onSignIn : undefined,
		).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("table", { name: "People" })).toBeVisible();
	},
};

/** A person on the page signs in, and finds the switch in User settings. */
export const SignedIn: Story = {
	args: { viewer: { status: "signed-in" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "User settings" })).toHaveAttribute(
			"href",
			"/settings",
		);
	},
};

/** Names open the published figures here; the provider profile is a separate link. */
export const PersonLinks: Story = {
	play: async ({ canvas }) => {
		const link = within(canvas.getByRole("table", { name: "People" })).getByRole("link", {
			name: "Ada Lovelace",
		});
		await expect(
			new URL(link.getAttribute("href") ?? "", window.location.href).searchParams.get("detail"),
		).toBe("person:ada");
		await expect(link).not.toHaveAttribute("target");
	},
};

export const Loading: Story = {
	args: { people: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

/** The first read of the page: its shape, and nothing that stands in for the workspace or its people. */
export const FirstLoad: Story = {
	render: () => <PublicActivityPageSkeleton />,
	play: async ({ canvas }) => {
		canvas.getByText("Loading public activity");
		await expect(canvas.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("table", { name: "People" })).not.toBeInTheDocument();
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: {
		workspaceName: undefined,
		coverage: undefined,
		repositories: [],
		people: { status: "error", error: new Error("Network down"), onRetry },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Public activity" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

/** Nobody contributed to a public repository in the period. */
export const Empty: Story = {
	args: { people: readyPublicPeople(publicActivityOf([])) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No activity in this range")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Custom range" })).toBeVisible();
	},
};

/** Some repositories' history is complete: the page says since when, and for how many. */
export const PartialHistory: Story = {
	args: {
		coverage: { since: daysBefore(400), completeRepositories: 1, totalRepositories: 2 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/History since .* for 1 of 2 repositories\./u)).toBeVisible();
	},
};

/** 250 people: the table renders 50 rows, and more as its end scrolls into view. */
export const LargeWorkspace: Story = {
	args: { people: readyPublicPeople(publicActivityOf(LARGE_PEOPLE)) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("250 people")).toBeVisible();
	},
};

export const OneRepository: Story = {
	args: { repo: [REPOSITORIES[0]?.key ?? ""], repositories: activity.repositories },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("combobox", { name: "Repository: hephaestus-build/Hephaestus" }),
		).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** GitLab's own words, icons and colours: merge requests, projects, Pajamas icons. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/This page shows merge requests, reviews and issues in public projects\./u),
		).toBeVisible();
		await expect(canvas.getByRole("columnheader", { name: /Merge requests/u })).toBeVisible();
	},
};

export const GitLabDark: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

/** Wide screens keep the page to its readable width, so names and figures stay close. */
export const Wide: Story = {
	parameters: { chromatic: { viewports: [1920] } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(table.getBoundingClientRect().width).toBeLessThanOrEqual(1152);
	},
};

const adaDetail = {
	stack: parseActivityStack(["person:ada"], PUBLIC_ACTIVITY_LEVEL_KINDS),
	onClose: fn(),
};

/** Ada's published totals, as the story data counts them. */
function adaCounts() {
	const ada = activity.people.find(({ login }) => login === "ada");
	if (ada === undefined) {
		throw new Error("The story data has no ada");
	}
	return ada.counts;
}

/**
 * The figure on the tile with this title, as assistive technology reads it: the chart under a
 * figure is hidden from it, so the chart's peak label is never mistaken for the figure.
 */
function tileFigure(totals: HTMLElement, title: string): HTMLElement {
	const tile = within(totals)
		.getAllByRole("listitem")
		.find((item) => within(item).queryByText(title) !== null);
	if (tile === undefined) {
		throw new Error(`The public totals have no ${title} tile`);
	}
	return within(tile).getByText(/^[\d,]+$/u, { ignore: "[aria-hidden] *" });
}

export const PersonDetails: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: {
		detail: adaDetail,
		period: {
			kind: "custom",
			from: new Date("2026-12-28T00:00:00Z"),
			to: new Date("2027-01-10T00:00:00Z"),
		},
		people: readyPublicPeople(
			publicActivityOf(PEOPLE, {
				from: new Date("2026-12-28T00:00:00Z"),
				to: new Date("2027-01-11T00:00:00Z"),
				people: activity.people.map((person) => ({
					...person,
					counts: { ...person.counts, activeWeeks: 2 },
					weeks: [
						{ start: new Date("2026-12-28T00:00:00Z"), contributions: person.counts.contributions },
						{ start: new Date("2027-01-04T00:00:00Z"), contributions: 0 },
					],
				})),
			}),
		),
	},
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
		await expect(
			panel.getByText(/28 December 2026.*10 January 2027/u, { selector: "p" }),
		).toBeVisible();
		await expect(
			panel.getByRole("heading", { name: /28 December 2026.*10 January 2027/u }),
		).toBeVisible();
		const totals = panel.getByRole("list", { name: "Published totals" });
		const counts = adaCounts();
		await expect(tileFigure(totals, "Contributions")).toHaveTextContent(
			counts.contributions.toLocaleString("en-GB"),
		);
		await expect(tileFigure(totals, "Reviews")).toHaveTextContent(
			counts.pullRequestsReviewed.toLocaleString("en-GB"),
		);
		await expect(within(totals).getByText("2 active weeks")).toBeVisible();
		await expect(tileFigure(totals, "Pull requests")).toHaveTextContent(
			String(counts.pullRequestsMerged),
		);
		await expect(within(totals).getByText(`${counts.pullRequestsOpened} opened`)).toBeVisible();
		await expect(within(totals).getByText(`${counts.peopleHelped} people helped`)).toBeVisible();
		await expect(within(totals).getByRole("link", { name: /^Reviews/u })).toHaveAttribute(
			"href",
			expect.stringContaining("activity:reviews"),
		);
		await expect(
			panel.queryByRole("table", { name: "Contributions by week" }),
		).not.toBeInTheDocument();
		await userEvent.click(panel.getByRole("button", { name: "Show weekly contributions" }));
		const weeks = within(panel.getByRole("table", { name: "Contributions by week" }));
		await expect(weeks.getByRole("cell", { name: "28 December 2026" })).toBeVisible();
		await expect(weeks.getByRole("cell", { name: "4 January 2027" })).toBeVisible();
		await expect(
			precedes(
				panel.getByRole("link", { name: /ada on GitHub/u }),
				panel.getByRole("heading", { name: /28 December 2026.*10 January 2027/u }),
			),
		).toBe(true);
		await expect(panel.getByRole("link", { name: /ada on GitHub/u })).toHaveAttribute(
			"href",
			"https://github.com/ada",
		);
		await expect(panel.getByRole("link", { name: /ada on GitHub/u })).toHaveAttribute(
			"target",
			"_blank",
		);
	},
};

/** Merges can be public without any of the work counted as Contributions in this range. */
export const PersonOnlyMerges: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: {
		detail: adaDetail,
		period: {
			kind: "custom",
			from: new Date("2026-09-21T00:00:00Z"),
			to: new Date("2026-09-27T00:00:00Z"),
		},
		people: readyPublicPeople(
			publicActivityOf(PEOPLE, {
				from: new Date("2026-09-21T00:00:00Z"),
				to: new Date("2026-09-28T00:00:00Z"),
				people: activity.people.map((person) => ({
					...person,
					counts: {
						contributions: 0,
						pullRequestsOpened: 0,
						pullRequestsMerged: 3,
						pullRequestsReviewed: 0,
						peopleHelped: 0,
						issuesOpened: 0,
						activeWeeks: 1,
					},
					weeks: [{ start: new Date("2026-09-21T00:00:00Z"), contributions: 0 }],
				})),
			}),
		),
	},
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const totals = panel.getByRole("list", { name: "Published totals" });
		await expect(tileFigure(totals, "Contributions")).toHaveTextContent("0");
		await expect(tileFigure(totals, "Pull requests")).toHaveTextContent("3");
		await expect(panel.getByText("0 opened")).toBeVisible();
		await expect(panel.getByText("1 active week")).toBeVisible();
		await userEvent.click(panel.getByRole("button", { name: "Show weekly contributions" }));
		const weeks = within(panel.getByRole("table", { name: "Contributions by week" }));
		await expect(weeks.getByRole("columnheader", { name: "Week of (UTC)" })).toBeVisible();
		await expect(weeks.getByRole("cell", { name: "21 September 2026" })).toBeVisible();
		await expect(weeks.getByRole("cell", { name: "0" })).toBeVisible();
		await expect(within(totals).getByRole("link", { name: /^Pull requests/u })).toHaveAttribute(
			"href",
			expect.stringContaining("activity:pull-requests"),
		);
	},
};

/** Openings keep the category active even when nothing merged; unrelated zero totals stay muted. */
export const PersonOnlyOpenings: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: {
		detail: adaDetail,
		period: {
			kind: "custom",
			from: new Date("2026-09-21T00:00:00Z"),
			to: new Date("2026-09-27T00:00:00Z"),
		},
		people: readyPublicPeople(
			publicActivityOf(PEOPLE, {
				from: new Date("2026-09-21T00:00:00Z"),
				to: new Date("2026-09-28T00:00:00Z"),
				people: activity.people.map((person) => ({
					...person,
					counts: {
						contributions: 4,
						pullRequestsOpened: 4,
						pullRequestsMerged: 0,
						pullRequestsReviewed: 0,
						peopleHelped: 0,
						issuesOpened: 0,
						activeWeeks: 1,
					},
					weeks: [{ start: new Date("2026-09-21T00:00:00Z"), contributions: 4 }],
				})),
			}),
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const totals = panel.getByRole("list", { name: "Published totals" });
		const merged = tileFigure(totals, "Pull requests");
		await expect(merged).toHaveTextContent("0");
		await expect(panel.getByText("4 opened")).toBeVisible();
		await expect(getComputedStyle(merged).color).toBe(
			getComputedStyle(tileFigure(totals, "Contributions")).color,
		);
		await expect(getComputedStyle(tileFigure(totals, "Reviews")).color).not.toBe(
			getComputedStyle(merged).color,
		);
		await expect(getComputedStyle(tileFigure(totals, "Issues")).color).not.toBe(
			getComputedStyle(merged).color,
		);
	},
};

/** The same supplied totals use singular units when one person and one week are counted. */
export const PersonSingleton: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: {
		detail: adaDetail,
		people: readyPublicPeople(
			publicActivityOf(PEOPLE, {
				people: activity.people.map((person) => ({
					...person,
					counts: {
						contributions: 3,
						pullRequestsOpened: 1,
						pullRequestsMerged: 1,
						pullRequestsReviewed: 1,
						peopleHelped: 1,
						issuesOpened: 1,
						activeWeeks: 1,
					},
					weeks: weekStarts(activity.from, activity.to)
						.slice(-1)
						.map((start) => ({ start, contributions: 3 })),
				})),
			}),
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("1 person helped")).toBeVisible();
		await expect(panel.getByText("1 active week")).toBeVisible();
		await expect(panel.getByText("1 opened")).toBeVisible();
	},
};

/** Provider terminology and profile links follow the published GitLab response. */
export const PersonGitLab: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
		detail: adaDetail,
		people: readyPublicPeople(
			publicActivityOf(PEOPLE, {
				people: activity.people.map((person) => ({
					...person,
					profileUrl: `https://gitlab.com/${person.login}`,
				})),
			}),
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			within(panel.getByRole("list", { name: "Published totals" })).getByRole("link", {
				name: /^Merge requests/u,
			}),
		).toBeVisible();
		await expect(panel.getByText("distinct merge requests")).toBeVisible();
		await expect(
			panel.getByRole("link", { name: "ada on GitLab (opens in a new tab)" }),
		).toHaveAttribute("href", "https://gitlab.com/ada");
	},
};

export const PersonReflow: Story = {
	args: { detail: adaDetail },
	parameters: {
		router: { initialUrl: "/?detail=person:ada" },
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await settledDrawerPanel();
		await expectNoPageOverflow();
	},
};

export const PersonMissing: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: { detail: adaDetail, people: readyPublicPeople(publicActivityOf([])) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not find this person’s activity")).toBeVisible();
		await expect(panel.queryByRole("link", { name: /on GitHub/u })).not.toBeInTheDocument();
	},
};

export const PersonLoading: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: { detail: adaDetail, people: { status: "loading" } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelector('[aria-busy="true"]')).toBeVisible();
		await expect(within(panel).queryByRole("link", { name: /on GitHub/u })).not.toBeInTheDocument();
		await expect(
			within(panel).queryByRole("heading", { name: "In this range" }),
		).not.toBeInTheDocument();
		await expect(within(panel).queryByRole("table")).not.toBeInTheDocument();
	},
};

export const PersonFailed: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: {
		detail: adaDetail,
		people: { status: "error", error: new Error("Network down"), onRetry: fn() },
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load this person’s activity")).toBeVisible();
		const summaryError = panel
			.getByText("We could not load this person’s activity")
			.closest<HTMLElement>('[role="alert"]');
		if (summaryError === null) {
			throw new Error("The person summary failure is not an alert");
		}
		await userEvent.click(within(summaryError).getByRole("button", { name: /Retry/u }));
		await expect(
			args.people.status === "error" ? args.people.onRetry : undefined,
		).toHaveBeenCalledOnce();
	},
};

export const PersonUpdating: Story = {
	parameters: { router: { initialUrl: "/?detail=person:ada" } },
	args: { ...PersonDetails.args, updating: true },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(
			within(panel).getByText(
				/These figures are for 28 December 2026.*10 January 2027 while activity updates\./u,
			),
		).toBeVisible();
		await expect(panel.querySelector('[aria-busy="true"]')).toBeVisible();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const RefreshFailed: Story = {
	args: { refreshError: { error: { status: 503 }, onRetry: fn(), pending: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not refresh activity")).toBeVisible();
		await expect(canvas.getByRole("table", { name: "People" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

export const RefreshRetrying: Story = {
	args: { refreshError: { error: { status: 503 }, onRetry: fn(), pending: true }, updating: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
	},
};
