import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { LARGE_PEOPLE, PEOPLE, peopleOf, readyPeople } from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityPeopleTable } from "./ActivityPeopleTable";
import { personLevelLink } from "./people-links";

const meta = {
	component: ActivityPeopleTable,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyPeople(peopleOf([...PEOPLE].reverse())),
		providerType: "GITHUB",
		repo: [],
		personLink: personLevelLink,
	},
} satisfies Meta<typeof ActivityPeopleTable>;

export default meta;
type Story = StoryObj<typeof meta>;

function names(table: HTMLElement) {
	return within(table)
		.getAllByRole("link")
		.map((link) => link.textContent);
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(names(table)).toStrictEqual([
			"Ada Lovelace",
			"Bob Brenner",
			"Chen Wei",
			"Dana Okafor",
			"Élodie Brière",
		]);
		await expect(within(table).queryByRole("columnheader", { name: "Position" })).toBeNull();
		await expect(within(table).queryAllByRole("button")).toHaveLength(0);
		// Figures read right-aligned, a cell with nothing in it is one dash read as "None", and a
		// first contribution in the range is marked new.
		const contributions = within(table).getAllByRole("cell")[1];
		await expect(contributions && getComputedStyle(contributions).textAlign).toBe("right");
		const chen = within(table).getByRole("row", { name: /Chen Wei/u });
		const chenPullRequests = within(chen).getAllByRole("cell")[2];
		await expect(chenPullRequests?.textContent).toBe("—None");
		await expect(within(chen).queryByText("New")).not.toBeInTheDocument();
		const elodie = within(table).getByRole("row", { name: /Élodie Brière/u });
		await expect(
			within(elodie).getByRole("img", { name: "First contribution in this range" }),
		).toBeVisible();
		const trend = within(table).getByRole("columnheader", { name: "Weekly trend" });
		await expect(within(trend).getByText("Trend")).toBeVisible();
	},
};

/** In a row, every icon is reachable by the pointer and says what it counts. */
export const ChipTooltips: Story = {
	play: async ({ canvas, userEvent }) => {
		const ada = within(canvas.getByRole("table", { name: "People" })).getByRole("row", {
			name: /Ada Lovelace/u,
		});
		for (const part of within(ada).getAllByRole("img")) {
			// What a pointer at the part's centre hits: the part, not the row's stretched link over it.
			const box = part.getBoundingClientRect();
			const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
			await expect(part.contains(hit)).toBe(true);
		}
		const helped = within(ada).getByRole("img", { name: /^Reviewed the work of \d+ people$/u });
		await userEvent.hover(helped);
		await expect(await settledPopup()).toHaveTextContent(/^Reviewed the work of \d+ people$/u);
	},
};

export const ClearSearchOnNarrowTable: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas, userEvent }) => {
		const search = canvas.getByRole("searchbox", { name: "Search people" });
		await userEvent.type(search, "zz");
		await expect(window.innerWidth).toBe(320);
		const table = canvas.getByRole("table", { name: "People" });
		const container = table.closest<HTMLElement>("[data-slot='table-container']");
		if (!container) {
			throw new Error("The table has no scroll container");
		}
		const title = canvas.getByText("No one matches your search");
		await expect(title).toBeVisible();
		for (const scrollLeft of [0, container.scrollWidth]) {
			container.scrollLeft = scrollLeft;
			const box = title.getBoundingClientRect();
			await expect(box.width).toBeGreaterThan(0);
			await expect(box.left).toBeGreaterThanOrEqual(0);
			await expect(box.right).toBeLessThanOrEqual(window.innerWidth);
		}
		await expect(container.scrollLeft).toBeGreaterThan(0);
		container.scrollLeft = 0;
		await expectNoPageOverflow();
		await userEvent.clear(search);
		await expect(canvas.queryByText("No one matches your search")).not.toBeInTheDocument();
		await expect(
			within(table)
				.getAllByRole("link")
				.map((link) => link.textContent),
		).toStrictEqual(["Ada Lovelace", "Bob Brenner", "Chen Wei", "Dana Okafor", "Élodie Brière"]);
	},
};

/** Search folds case and accents. */
export const Search: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search people" }), "elodie");
		await expect(names(canvas.getByRole("table", { name: "People" }))).toStrictEqual([
			"Élodie Brière",
		]);
		await expect(canvas.getByText("1 of 5 people")).toBeVisible();
	},
};

export const NoMatch: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(canvas.getByRole("searchbox", { name: "Search people" }), "zz");
		await expect(canvas.getByText("No one matches your search")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { state: readyPeople(peopleOf([])) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No activity in this range")).toBeVisible();
	},
};

/** With repositories picked, the empty table names its scope. */
export const EmptyForRepositories: Story = {
	args: { state: readyPeople(peopleOf([])), repo: ["hephaestus-build/Hephaestus"] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No activity in these repositories in this range")).toBeVisible();
	},
};

/** The previous range's figures stand in drained of their state colours, never as the new range's. */
export const Stale: Story = {
	args: { state: { status: "ready", people: peopleOf(PEOPLE), stale: true } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(getComputedStyle(table).filter).toContain("grayscale");
	},
};

/** An empty result that stands in while the next range loads is marked as the previous one's. */
export const StaleEmpty: Story = {
	args: { state: { status: "ready", people: peopleOf([]), stale: true } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No activity in this range").closest("[aria-busy='true']"),
		).not.toBeNull();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

/** A failed refresh keeps the people already read, with a warning and Retry above them. */
export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			people: peopleOf(PEOPLE),
			stale: false,
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not refresh people");
		const table = canvas.getByRole("table", { name: "People" });
		await expect(within(table).getAllByRole("link")).toHaveLength(PEOPLE.length);
	},
};

/** While the refresh is tried again, its Retry is busy and the people stay. */
export const RefreshRetrying: Story = {
	args: {
		state: {
			status: "ready",
			people: peopleOf(PEOPLE),
			stale: false,
			refreshFailure: { error: { status: 503 }, pending: true, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(canvas.getByRole("table", { name: "People" })).toBeVisible();
	},
};

/** A range that was read as empty stays empty when its refresh fails, with the warning above it. */
export const RefreshFailedEmpty: Story = {
	args: {
		state: {
			status: "ready",
			people: peopleOf([]),
			stale: false,
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not refresh people");
		await expect(canvas.getByText("No activity in this range")).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** GitLab's own icons and colours: Pajamas merge requests, merged in blue. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: /Merge requests/u })).toBeVisible();
		await expect(canvas.getAllByRole("img", { name: /merged$/u }).length).toBeGreaterThan(0);
	},
};

export const GitLabDark: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

const LONG_NAME = "Abigail Alexandra Featherstonehaugh-Wolfeschlegelsteinhausen";
const TWO_WORD_NAME = "Adelbert Abberthorne";
const large = peopleOf(LARGE_PEOPLE);
const [longFirst, twoWordSecond] = large.people;

/** Four-digit pairs in both two-part columns. */
const BUSY = {
	pullRequestsOpened: 1234,
	pullRequestsMerged: 1187,
	pullRequestsReviewed: 2048,
	peopleHelped: 1024,
	issuesOpened: 3,
};

function renamed(row: (typeof large.people)[number]) {
	if (row === longFirst) {
		return {
			...row,
			person: { ...row.person, name: LONG_NAME },
			counts: {
				...row.counts,
				...BUSY,
				contributions: BUSY.pullRequestsOpened + BUSY.pullRequestsReviewed + BUSY.issuesOpened,
			},
		};
	}
	if (row === twoWordSecond) {
		return { ...row, person: { ...row.person, name: TWO_WORD_NAME } };
	}
	return row;
}

const busyPeople = readyPeople({
	...large,
	people: large.people.map(renamed),
	highlights: {
		...large.highlights,
		firstContributors: longFirst ? [longFirst.person.id] : [],
	},
});

/** 250 people render 50 at a time; at full width a four-digit pair sits side by side. */
export const Large: Story = {
	args: { state: busyPeople },
	play: async ({ canvas, userEvent }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(names(table)).toHaveLength(50);
		const header = within(table).getByRole("columnheader", { name: /^Pull requests/u });
		const longRow = within(table).getByRole("row", { name: new RegExp(LONG_NAME, "u") });
		const cell =
			within(longRow).getAllByRole("cell")[
				within(table).getAllByRole("columnheader").indexOf(header)
			];
		const [opened, merged] = cell ? within(cell).getAllByRole("img") : [];
		if (!opened || !merged) {
			throw new Error("The pull request figures are not both shown");
		}
		await expect(merged.getBoundingClientRect().left).toBeGreaterThanOrEqual(
			opened.getBoundingClientRect().right,
		);
		await expect(
			Math.abs(merged.getBoundingClientRect().top - opened.getBoundingClientRect().top),
		).toBeLessThanOrEqual(1);
		await userEvent.click(canvas.getByRole("button", { name: "Show more people" }));
		await expect(names(table)).toHaveLength(100);
	},
};

/**
 * On narrow screens, the pinned name stays visible as the figures scroll.
 */
async function expectNarrowTable(
	{ canvas, userEvent }: Parameters<NonNullable<Story["play"]>>[0],
	expected: { width: number; region: number; pullRequests: RegExp },
) {
	await expect(window.innerWidth).toBe(expected.width);
	const table = canvas.getByRole("table", { name: "People" });
	const container = table.closest<HTMLElement>("[data-slot='table-container']");
	if (!container) {
		throw new Error("The table has no scroll container");
	}
	await expect(Math.round(container.getBoundingClientRect().width)).toBe(expected.region);
	const NARROW_METRICS = [
		/^Total contributions/u,
		expected.pullRequests,
		/^Reviews/u,
		/^Issues/u,
		/^Active weeks/u,
		/^Weekly trend/u,
	];

	const longRow = within(table).getByRole("row", { name: new RegExp(LONG_NAME, "u") });
	longRow.scrollIntoView({ block: "center" });
	const cells = within(longRow).getAllByRole("cell");
	const headers = within(table).getAllByRole("columnheader");
	const [nameCell] = cells;
	if (!nameCell) {
		throw new Error("The row has no name cell");
	}
	container.scrollLeft = 0;
	const innerLeft = container.getBoundingClientRect().left + container.clientLeft;
	const innerRight = innerLeft + container.clientWidth;
	// The name is pinned at the edge.
	await expect(Math.abs(nameCell.getBoundingClientRect().left - innerLeft)).toBeLessThanOrEqual(1);
	for (const metric of NARROW_METRICS) {
		const header = within(table).getByRole("columnheader", { name: metric });
		const cell = cells[headers.indexOf(header)];
		if (!cell) {
			throw new Error(`No figures under ${String(metric)}`);
		}
		const label = header.firstElementChild;
		const chips = within(cell).queryAllByRole("img");
		const figures = chips.length > 0 ? chips : [cell.firstElementChild];
		if (!label || figures.some((figure) => figure === null)) {
			throw new Error("A metric has no visible label or figures");
		}
		// A four-digit figure can be wider than its header. Scroll from the leftmost part.
		const edge = nameCell.getBoundingClientRect().right;
		const left = Math.min(
			label.getBoundingClientRect().left,
			...figures.map((figure) => figure?.getBoundingClientRect().left ?? Infinity),
		);
		container.scrollLeft += left - edge;
		for (const part of [label, ...figures]) {
			if (!part) {
				throw new Error("A metric has no figures");
			}
			const box = part.getBoundingClientRect();
			await expect(box.left).toBeGreaterThanOrEqual(edge - 1);
			await expect(box.right).toBeLessThanOrEqual(innerRight + 1);
		}
		await expect(cell.scrollWidth).toBeLessThanOrEqual(cell.clientWidth + 1);
		// Each figure is what a pointer at it reaches, not the pinned name over it.
		for (const chip of within(cell).queryAllByRole("img")) {
			const box = chip.getBoundingClientRect();
			const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
			await expect(chip.contains(hit)).toBe(true);
		}
	}
	// Four-digit pairs stay whole and stack: merged under opened.
	const pullRequestsHeader = within(table).getByRole("columnheader", {
		name: expected.pullRequests,
	});
	const pullRequests = cells[headers.indexOf(pullRequestsHeader)];
	if (!pullRequests) {
		throw new Error("No pull request figures");
	}
	await expect(within(pullRequests).getByText("1,234")).toBeVisible();
	await expect(within(pullRequests).getByText("1,187")).toBeVisible();
	const [opened, merged] = within(pullRequests).getAllByRole("img");
	if (!opened || !merged) {
		throw new Error("The pull request figures are not both shown");
	}
	await expect(merged.getBoundingClientRect().top).toBeGreaterThanOrEqual(
		opened.getBoundingClientRect().bottom - 1,
	);

	// The keyboard reaches the person's link while the table is scrolled.
	const link = within(longRow).getByRole("link", { name: LONG_NAME });
	canvas.getByRole("searchbox", { name: "Search people" }).focus();
	for (let step = 0; step < 12 && document.activeElement !== link; step += 1) {
		await userEvent.tab();
	}
	await expect(link).toHaveFocus();
	const linkBox = link.getBoundingClientRect();
	const hit = document.elementFromPoint(
		linkBox.left + linkBox.width / 2,
		linkBox.top + linkBox.height / 2,
	);
	await expect(link.contains(hit)).toBe(true);
	await expectNoPageOverflow();

	const lineHeight = Number.parseFloat(getComputedStyle(link).lineHeight);
	await expect(link.clientHeight).toBeLessThanOrEqual(2 * lineHeight + 1);
	await expect(link.clientHeight).toBeGreaterThan(lineHeight + 1);
	await expect(link.scrollHeight).toBeGreaterThan(link.clientHeight);
	const twoWord = within(table).getByRole("link", { name: TWO_WORD_NAME });
	await expect(twoWord.clientHeight).toBeGreaterThan(lineHeight + 1);
	await expect(twoWord.scrollHeight).toBeLessThanOrEqual(twoWord.clientHeight + 1);
	await expect(
		within(longRow).getByRole("img", { name: "First contribution in this range" }),
	).toBeVisible();
}

/** The page's own table region at 320px. */
export const NarrowScroll: Story = {
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: { state: busyPeople },
	play: async (context) => {
		await expectNarrowTable(context, {
			width: 320,
			region: 288,
			pullRequests: /^PRs \(Pull requests\)/u,
		});
	},
};

/** Nested in a padded region at 320px. */
export const NarrowScrollNested: Story = {
	decorators: [
		(Story) => (
			<div className="px-4">
				<Story />
			</div>
		),
	],
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: { state: busyPeople },
	play: async (context) => {
		await expectNarrowTable(context, {
			width: 320,
			region: 256,
			pullRequests: /^PRs \(Pull requests\)/u,
		});
	},
};

export const NarrowScroll390: Story = {
	parameters: {
		layout: "fullscreen",
		viewport: {
			options: { phone390: { name: "Phone (390px)", styles: { width: "390px", height: "844px" } } },
			defaultViewport: "phone390",
		},
		chromatic: { viewports: [390] },
	},
	args: { state: busyPeople },
	play: async (context) => {
		await expectNarrowTable(context, {
			width: 390,
			region: 358,
			pullRequests: /^PRs \(Pull requests\)/u,
		});
	},
};

export const NarrowScrollGitLab: Story = {
	decorators: [withProvider("GITLAB")],
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: { state: busyPeople, providerType: "GITLAB" },
	play: async (context) => {
		await expectNarrowTable(context, {
			width: 320,
			region: 288,
			pullRequests: /^MRs \(Merge requests\)/u,
		});
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const LoadingNarrow: Story = {
	...Loading,
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.getByRole("columnheader", { name: /^Total contributions/u })).toBeVisible();
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
		await expectNoPageOverflow();
	},
};
