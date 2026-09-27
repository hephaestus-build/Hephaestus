import { type ListPage, listTarget, type WorkPage, type WorkPageProvider } from "~/shared/work-url";

/**
 * Where the practice review report goes in a provider's page: between the work's description and its
 * activity, as a section of the work's own content column. Each slot is a boundary the provider's DOM
 * names with its own test hooks, partial names or ids, recorded from signed-in pages
 * (`docs/contributor/browser-extension.mdx` § Where the report goes); never a hashed class, a
 * sticky header, a grid track or a timeline item of our own making.
 *
 * The conversation or overview and the diff each have a slot, so the report follows the reader
 * between them on the same work; commits, checks and pipelines have none. A provider keeps hidden
 * tab panes in the page, so only a slot the reader can see counts. A page with no visible slot gets
 * no report: there is no floating substitute.
 */
export interface ReportSlot {
	/** The provider element the report is a child of. */
	parent: Element;
	/** The provider child the report goes before (`null`: at the end). */
	before: Element | null;
	/** Which provider layout, for spacing that matches its neighbours. */
	layout: ReportLayout;
	/** A list row's title, which the row's preview lines up with. */
	alignWith?: Element;
}

export type ReportLayout =
	| "github-pull-request"
	| "github-diff"
	| "github-issue"
	| "gitlab-merge-request"
	| "gitlab-diff"
	| "gitlab-work-item"
	| "list-preview";

/** The report's host element, which the next reconciliation must see past to the provider's own. */
export const REPORT_HOST_TAG = "hephaestus-report";

/** The inspect button beside each recognised row of a list. */
export const INSPECT_TAG = "hephaestus-inspect";

function providerSibling(element: Element | null): Element | null {
	let sibling = element;
	while (sibling?.localName === REPORT_HOST_TAG) {
		sibling = sibling.nextElementSibling;
	}
	return sibling;
}

/**
 * A pull request's conversation renders the description and the timeline as two server partials,
 * in that order, inside `.js-discussion`. The report goes between them.
 */
function githubPullRequest(root: ParentNode): ReportSlot | undefined {
	for (const discussion of root.querySelectorAll(".js-pull-discussion-timeline > .js-discussion")) {
		const body = discussion.querySelector(
			':scope > [data-partial-name="pullRequestsConversationsRoute.Body"]',
		);
		if (body !== null) {
			return {
				parent: discussion,
				before: providerSibling(body.nextElementSibling),
				layout: "github-pull-request",
			};
		}
	}
	return undefined;
}

/**
 * A pull request's changes, in either view GitHub serves: the report goes before the list of file
 * diffs, inside the diff area.
 */
function githubDiff(root: ParentNode): ReportSlot | undefined {
	// The React changes view first; then the server-rendered files view GitHub still serves, where
	// `#files` holds a hidden filter blank slate and then the progressive list of file entries.
	const list =
		root.querySelector('[data-testid="diff-content"] > [data-testid="progressive-diffs-list"]') ??
		root.querySelector("#files > .js-diff-progressive-container");
	const parent = list?.parentElement;
	if (list === null || parent === null || parent === undefined) {
		return undefined;
	}
	return { parent, before: list, layout: "github-diff" };
}

/** An issue's description container, then its activity; the report goes after the description. */
function githubIssue(root: ParentNode): ReportSlot | undefined {
	const container = root.querySelector('[data-testid="issue-viewer-issue-container"]');
	const parent = container?.parentElement;
	if (container === null || parent === null || parent === undefined) {
		return undefined;
	}
	return {
		parent,
		before: providerSibling(container.nextElementSibling),
		layout: "github-issue",
	};
}

/**
 * A merge request's overview lists the description, the reactions, the merge request widgets and
 * the activity as children of `.issuable-discussion`; the report joins the widgets' reports, before
 * the activity. The outer tab pane also has the id `notes`, so nothing here looks for it.
 */
function gitlabMergeRequest(root: ParentNode): ReportSlot | undefined {
	const widget = root.querySelector(".issuable-discussion > #widget-state");
	const parent = widget?.parentElement;
	if (widget === null || parent === null || parent === undefined) {
		return undefined;
	}
	return {
		parent,
		before: providerSibling(widget.nextElementSibling),
		layout: "gitlab-merge-request",
	};
}

/**
 * A merge request's changes, as GitLab's Rapid Diffs renders them: the report goes before the list
 * of file diffs, inside the section that holds them.
 */
function gitlabDiff(root: ParentNode): ReportSlot | undefined {
	const list = root.querySelector(
		'section.rd-app-content[aria-label="Diff files"] > .rd-app-diffs-list',
	);
	const parent = list?.parentElement;
	if (list === null || parent === null || parent === undefined) {
		return undefined;
	}
	return { parent, before: list, layout: "gitlab-diff" };
}

/**
 * A work item lays out named grid areas; the activity area holds the notes. The report goes first
 * inside it, so the grid gets no child it does not know.
 */
function gitlabWorkItem(root: ParentNode): ReportSlot | undefined {
	const activity = root.querySelector(
		'[data-testid="detail-layout-container"] [data-testid="detail-layout-activity"]',
	);
	if (activity === null) {
		return undefined;
	}
	return {
		parent: activity,
		before: providerSibling(activity.firstElementChild),
		layout: "gitlab-work-item",
	};
}

/** Whether the reader can see the slot: none of its ancestors is hidden, as a closed tab pane is. */
function isDisplayed(slot: ReportSlot): boolean {
	return isElementDisplayed(slot.parent);
}

function isElementDisplayed(element: Element): boolean {
	for (let node: Element | null = element; node !== null; node = node.parentElement) {
		if (node.hasAttribute("hidden")) {
			return false;
		}
		const style = getComputedStyle(node);
		if (
			style.display === "none" ||
			style.visibility === "hidden" ||
			style.contentVisibility === "hidden"
		) {
			return false;
		}
	}
	return true;
}

const FINDERS: Record<WorkPageProvider, readonly ((root: ParentNode) => ReportSlot | undefined)[]> =
	{
		GITHUB: [githubDiff, githubPullRequest, githubIssue],
		GITLAB: [gitlabDiff, gitlabMergeRequest, gitlabWorkItem],
	};

export function findReportSlot(
	root: ParentNode,
	provider: WorkPageProvider,
): ReportSlot | undefined {
	for (const find of FINDERS[provider]) {
		const slot = find(root);
		if (slot !== undefined && isDisplayed(slot)) {
			return slot;
		}
	}
	return undefined;
}

/**
 * GitLab names the page it rendered on `<body data-page>`. A project detail page is
 * `projects:<area>:show`; anything else on the same path shape (a group page, an error page) is not
 * work. An absent attribute is tolerated, since a redesign may drop it.
 */
export function gitLabPageAllowed(body: Pick<HTMLElement, "dataset"> | null): boolean {
	const page = body?.dataset.page;
	return page === undefined || page.startsWith("projects:");
}

/**
 * How a provider's repository list marks a row's title link, as its own test ids name it — never by
 * position or by any link, since a row can carry other links (GitLab's work items put a full-row overlay
 * link first) and the page can hold other lists. An unknown list shape matches nothing and gets nothing.
 *
 * - GitHub's list view: `ul[data-listview-component="items-list"] li`, whose title container holds
 *   `h3 > a`, named `listitem-title-link` on the pull request list and `issue-pr-title-link` on the
 *   issue list. The preview goes into the title container's own parent — the column the title and
 *   its meta line share — so it lines up with them; where that parent is the row, across the row.
 * - GitLab's issuable list (merge requests, issues, work items):
 *   `li[data-testid="issuable-container"] > .issuable-main-info`, whose title holds
 *   `a[data-testid="issuable-title-link"]`. The preview goes last into `.issuable-main-info`.
 */
const LIST_TITLE: Record<
	WorkPageProvider,
	{ title: string; row: string; preview: (title: Element) => Element | null | undefined }
> = {
	GITHUB: {
		title: [
			'[data-listview-component="items-list"] li [data-listview-item-title-container] a[data-testid="listitem-title-link"]',
			'[data-listview-component="items-list"] li [data-listview-item-title-container] a[data-testid="issue-pr-title-link"]',
		].join(", "),
		row: "li",
		preview: (title) => title.closest("[data-listview-item-title-container]")?.parentElement,
	},
	GITLAB: {
		title: 'li[data-testid="issuable-container"] a[data-testid="issuable-title-link"]',
		row: 'li[data-testid="issuable-container"]',
		preview: (title) => title.closest(".issuable-main-info"),
	},
};

/** A page of a list shows a few dozen rows; anything beyond this is not handled. */
export const MAX_LIST_ROWS = 100;

export interface ListRow {
	row: Element;
	/**
	 * What the inspect control goes right after: the title's heading when it has one, so the heading's
	 * accessible name stays the provider's, else the title link itself.
	 */
	after: Element;
	/** Where the row's preview goes, inside the row and across its whole width. */
	preview: ReportSlot;
	work: WorkPage;
}

export function findListRows(root: ParentNode, list: ListPage): ListRow[] {
	const shape = LIST_TITLE[list.provider];
	const rows: ListRow[] = [];
	const seen = new Set<number>();
	for (const title of root.querySelectorAll<HTMLAnchorElement>(shape.title)) {
		if (rows.length >= MAX_LIST_ROWS) {
			break;
		}
		let url: URL;
		try {
			url = new URL(title.href);
		} catch {
			continue;
		}
		const work = listTarget(list, `${url.origin}${url.pathname}`);
		const row = title.closest(shape.row);
		const container = shape.preview(title);
		if (
			work === undefined ||
			seen.has(work.number) ||
			row === null ||
			container === null ||
			container === undefined ||
			!row.contains(container) ||
			!isElementDisplayed(row)
		) {
			continue;
		}
		const heading = title.closest("h1, h2, h3, h4, h5, h6");
		seen.add(work.number);
		rows.push({
			row,
			after: heading !== null && row.contains(heading) ? heading : title,
			preview: { parent: container, before: null, layout: "list-preview", alignWith: title },
			work,
		});
	}
	return rows;
}
