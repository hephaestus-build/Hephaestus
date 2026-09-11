import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { ListPracticeReviewObservationsResponse, ReviewObservation } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";

import { groupFacetOptions, practiceFacetOptions } from "./ObservationFilters";
import { ObservationsListPage } from "./ObservationsListPage";
import { type ObservationsSearch, observationsQuery, REVIEW_PAGE_SIZE } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";
import {
	manyObservations,
	practiceGroups,
	reviewObservations,
	workspaceMembers,
	workspacePractices,
} from "./story-mock-data";

const PEOPLE: ReviewPeople = {
	options: workspaceMembers
		.filter((member): member is typeof member & { userId: number } => member.userId != null)
		.map((member) => ({
			userId: member.userId,
			label: member.userName ?? `#${member.userId}`,
			secondary: member.userLogin,
		})),
	capped: false,
	isLoading: false,
	isError: false,
};
const GROUPS: FacetSource = {
	options: groupFacetOptions(practiceGroups),
	isLoading: false,
	isError: false,
};
const PRACTICES: FacetSource = {
	options: practiceFacetOptions(workspacePractices, practiceGroups),
	isLoading: false,
	isError: false,
};

/**
 * Every row a story has to choose from. It travels in the `observations` arg because that is the
 * prop the screen reads, and {@link observationPage} narrows it to one page before the screen sees
 * it — so an arg set in Controls is a pool to filter, not a page already cut.
 */
function pool(rows: ReviewObservation[]): ListPracticeReviewObservationsResponse {
	return {
		content: rows,
		page: {
			number: 0,
			size: REVIEW_PAGE_SIZE,
			totalElements: rows.length,
			totalPages: Math.max(1, Math.ceil(rows.length / REVIEW_PAGE_SIZE)),
		},
	};
}

/**
 * The route fetches; this screen only draws what it is handed. To keep the facets live in a story
 * without a network, the filtering the server does is applied here — over the query object the route
 * would actually send, so a facet that stopped reaching the request would stop working here too.
 * What the *endpoint* names those parameters is a separate contract, pinned by the route test.
 */
function observationPage(
	candidates: ReviewObservation[],
	search: ObservationsSearch,
): ListPracticeReviewObservationsResponse {
	const query = observationsQuery(search, REVIEW_PAGE_SIZE);
	const selects = (selected: string[] | undefined, actual: string | undefined) =>
		!selected?.length || (actual !== undefined && selected.includes(actual));
	const rows = candidates.filter(
		(row) =>
			(!query.from || row.observedAt >= new Date(query.from)) &&
			(!query.to || row.observedAt < new Date(query.to)) &&
			selects(query.groupSlug, row.group?.slug) &&
			selects(query.practiceSlug, row.practiceSlug) &&
			selects(query.presence, row.presence) &&
			selects(query.assessment, row.assessment) &&
			selects(query.severity, row.severity) &&
			(query.subjectUserId === undefined || row.subject?.id === query.subjectUserId),
	);
	const ordered = query.sort === "ACTIONABILITY" ? [...rows].sort(byActionability) : rows;
	const number = query.page;
	return {
		content: ordered.slice(number * REVIEW_PAGE_SIZE, (number + 1) * REVIEW_PAGE_SIZE),
		page: {
			number,
			size: REVIEW_PAGE_SIZE,
			totalElements: ordered.length,
			totalPages: Math.max(1, Math.ceil(ordered.length / REVIEW_PAGE_SIZE)),
		},
	};
}

/** Shortfalls worst-first, then strengths, then the observations that judged nothing. */
const ACTIONABILITY_RANK: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, INFO: 3 };
function actionability(row: ReviewObservation): number {
	if (row.assessmentStatus !== "ASSESSED" || !row.presence || !row.assessment) return 6;
	if ((row.presence === "PRESENT") !== (row.assessment === "GOOD"))
		return ACTIONABILITY_RANK[row.severity ?? "INFO"] ?? 4;
	return 5;
}
const byActionability = (a: ReviewObservation, b: ReviewObservation) =>
	actionability(a) - actionability(b) || b.observedAt.getTime() - a.observedAt.getTime();

const meta = {
	title: "Workspace admin/Practice reviews/Observations",
	component: ObservationsListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: { presence: undefined, assessment: undefined, severity: undefined },
		onSearchChange: fn(),
		observations: pool(reviewObservations),
		isLoading: false,
		error: undefined,
		onRetry: fn(),
		groups: GROUPS,
		practices: PRACTICES,
		// The facet needs a label per slug; the hover card on a row's practice name needs the record.
		practiceRecords: workspacePractices,
		people: PEOPLE,
	},
	// The screen is controlled: with a frozen `search` prop every facet reads as dead, because
	// `selected` comes back through the same prop the choice was reported on. The rows are recomputed
	// from that search the way the route's query would be re-run.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onSearchChange) => (
				<ObservationsListPage
					{...args}
					search={search}
					onSearchChange={onSearchChange}
					observations={
						args.observations && observationPage(args.observations.content ?? [], search)
					}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ObservationsListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * These facets are multi-select, so the popup stays open after a choice; leaving it open would put a
 * second listbox on screen for the next facet and make a bare `findByRole("listbox")` throw. The
 * trigger is captured before the click because choosing an option renames it.
 */
async function pickFacet(
	canvas: StoryContext["canvas"],
	userEvent: { click: (element: Element) => Promise<void> },
	facet: string,
	option: RegExp,
) {
	const trigger = canvas.getByRole("combobox", { name: facet });
	await userEvent.click(trigger);
	const listbox = await screen.findByRole("listbox", { name: `${facet} options` });
	await userEvent.click(await within(listbox).findByRole("option", { name: option }));
	await userEvent.click(trigger);
}

export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText("12 observations.");
		for (const name of [
			"Group",
			"Practice",
			"Result",
			"Severity",
			"Assessment status",
			"Presence",
		]) {
			canvas.getByRole("combobox", { name });
		}
		canvas.getByRole("combobox", { name: "Developer" });
		canvas.getByRole("button", { name: "Observed" });
		// A pull request, a chat thread and a document all reach this list and have to read as one
		// language, so one row of each work kind is asserted rather than any one row.
		canvas.getByText("The controller delegates before it does anything else");
		canvas.getByText("The thread ends without naming what was chosen");
		canvas.getByText("The runbook opens with the one step that cannot be undone");
	},
};

export const FilterToOneSeverity: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Major/);
		await canvas.findByText("2 observations match your filters.");
		await expect(canvas.queryByText(/leaks the ledger's table name/)).not.toBeInTheDocument();
	},
};

export const FilterToOnePerson: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		await userEvent.click(canvas.getByRole("combobox", { name: "Developer" }));
		const listbox = await screen.findByRole("listbox");
		await userEvent.click(await within(listbox).findByRole("option", { name: /Grace Hopper/ }));
		await canvas.findByRole("combobox", { name: "Developer: Grace Hopper" });
		await canvas.findByText("2 observations match your filters.");
	},
};

export const FilteredToNothing: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Critical/);
		await pickFacet(canvas, userEvent, "Group", /Testing/);
		await canvas.findByText("No observations match these filters");
		canvas.getByRole("button", { name: "Reset" });
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await canvas.findByText("12 observations.");
	},
};

/**
 * `ACTIONABILITY` is the server's ordering, not one the browser applies: shortfalls worst-first, then
 * strengths, then the observations that judged nothing. The control's only job is to put the choice
 * in the search the route turns into a request — that the endpoint spells it `sort` is pinned by
 * `-lists-route.test.tsx`, which is the only place the wire name can be checked.
 */
export const SortByActionability: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		const firstRowBefore = within(
			await canvas.findByRole("list", { name: "Observations" }),
		).getAllByRole("listitem")[0];
		await expect(firstRowBefore).toHaveTextContent("A dropped delivery is logged at debug");

		await userEvent.click(canvas.getByRole("combobox", { name: /Sort/ }));
		await userEvent.click(await screen.findByRole("option", { name: "Most actionable first" }));

		const rows = await within(
			await canvas.findByRole("list", { name: "Observations" }),
		).findAllByRole("listitem");
		await expect(rows[0]).toHaveTextContent("Invoice numbering leaks the ledger's table name");
		await expect(rows[0]).toHaveTextContent("Critical");
		const titles = rows.map((row) => row.textContent);
		const problems = titles.flatMap((text, index) =>
			text.includes("Negative outcome") ? [index] : [],
		);
		const strengths = titles.flatMap((text, index) =>
			text.includes("Positive outcome") ? [index] : [],
		);
		await expect(problems.length).toBeGreaterThan(0);
		await expect(strengths.length).toBeGreaterThan(0);
		await expect(Math.min(...strengths)).toBeGreaterThan(Math.max(...problems));
	},
};

/**
 * `FacetMultiSelect` collapses its value chips below `sm` into a bare count, so at this width the
 * applied values only reach the reader through a separate pill row.
 */
export const MobileAppliedFilters: Story = {
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		await pickFacet(canvas, userEvent, "Severity", /Major/);
		await canvas.findByText("2 observations match your filters.");
		// Queried by label rather than by role because whether the `sm:` breakpoint is live depends on
		// how the runner applies the viewport, while what the pill says and does must hold either way.
		await canvas.findByTitle("Severity: Major");
		await userEvent.click(canvas.getByLabelText("Clear severity filter (Major)"));
		await canvas.findByText("12 observations.");
		await expectNoPageOverflow();
	},
};

export const PickADateRange: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("12 observations.");
		await userEvent.click(canvas.getByRole("button", { name: "Observed" }));
		const dialog = await screen.findByRole("dialog");
		// The month is the only `role="grid"` on screen and the day buttons are the only buttons
		// inside it. Testing Library maps a `<td>` to `cell`, not `gridcell`, whatever role its
		// ancestor table carries, so the grid is the anchor rather than the cells.
		const grid = await within(dialog).findByRole("grid");
		const days = within(grid).getAllByRole("button");
		const [rangeStart, rangeEnd] = [days[4], days[10]];
		if (!rangeStart || !rangeEnd) throw new Error("The month grid rendered too few days");
		await userEvent.click(rangeStart);
		await userEvent.click(rangeEnd);
		await canvas.findByText(/–/);
	},
};

export const SeverityFacetOpen: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Severity" }));
		const listbox = await screen.findByRole("listbox");
		await within(listbox).findByRole("option", { name: /Critical/ });
		within(listbox).getByRole("option", { name: /Informational/ });
	},
};

export const MoreThanOnePage: Story = {
	// Pagination is real links so a page can be bookmarked, which means the page travels through the
	// router rather than `onSearchChange`. Storybook mounts this screen under a single bare route, so
	// a click would go nowhere and the page has to be set in `args`.
	args: {
		search: { page: 1, presence: undefined, assessment: undefined, severity: undefined },
		observations: pool(manyObservations(64)),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("64 observations.");
		const current = await canvas.findByRole("link", { name: "Go to page 2" });
		await expect(current).toHaveAttribute("aria-current", "page");
		canvas.getByRole("link", { name: "Go to previous page" });
		canvas.getByRole("link", { name: "Go to next page" });
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("12 observations.");
		await expectNoPageOverflow();
	},
};

/**
 * The error arrives as a prop, so nothing here depends on a request failing at the right moment. A
 * status-less error is the one that reads "check your connection" — see `QueryErrorAlert`.
 */
export const LoadFailed: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: {
		observations: undefined,
		error: { status: 500, detail: "Something went wrong." },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("Couldn't load observations");
	},
};

export const Loading: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { observations: undefined, isLoading: true },
	play: async ({ canvas }) => {
		await canvas.findByText("Loading observations");
	},
};

/** Nothing has been observed yet, which is not the same as a filter that matched nothing. */
export const NoObservationsYet: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { observations: pool([]) },
	play: async ({ canvas }) => {
		await canvas.findByText("No observations yet");
	},
};
