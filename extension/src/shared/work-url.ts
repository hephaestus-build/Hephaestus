/**
 * Which provider pages carry a piece of reviewed work, and which work. A pure grammar over the tab's
 * URL: it never reads the page, and it only decides whether to ask the server — the server resolves
 * the URL against the workspace's connected origin and mirrored work, and is the only authority on
 * what the work is (`resolveReviewContext`).
 *
 * The grammar is deliberately tight. A path that merely starts like a pull request is not one, a
 * query or fragment never changes the work, and a sub-page (`/files`, `/diffs`) is the same work.
 */
export type WorkPageProvider = "GITHUB" | "GITLAB";
export type WorkPageKind = "PULL_REQUEST" | "MERGE_REQUEST" | "ISSUE";

export interface WorkPage {
	provider: WorkPageProvider;
	/** Lower-case scheme and host, default port dropped — `URL.origin`. */
	origin: string;
	/** `owner/repo` on GitHub, `group/subgroup/project` on GitLab, decoded once. */
	repository: string;
	kind: WorkPageKind;
	number: number;
	/** The page with sub-page, query and fragment removed: what is sent to the server. */
	canonicalUrl: string;
	/** Which view of the work the page shows: its changes (diff) or everything else. */
	view: WorkPageView;
}

export type WorkPageView = "overview" | "changes";

const GITHUB_CHANGES_TABS = new Set(["files", "changes"]);

const MAX_WORK_NUMBER = 2_147_483_647;
const GITHUB_PULL_TABS = new Set(["files", "commits", "checks", "changes"]);
const GITLAB_MERGE_REQUEST_TABS = new Set(["diffs", "commits", "pipelines", "reports"]);
// Percent-encoded slash, backslash and NUL would let one segment pose as two.
const AMBIGUOUS_ENCODING = /%(?:2f|5c|00)/iu;
const COMMIT_SHA = /^[0-9a-f]{7,64}$/u;

function workNumber(segment: string | undefined): number | undefined {
	if (segment === undefined || !/^[1-9]\d{0,9}$/u.test(segment)) {
		return undefined;
	}
	const value = Number(segment);
	return value <= MAX_WORK_NUMBER ? value : undefined;
}

/** Splits a raw path into decoded segments, or `undefined` if any segment is unsafe to read. */
function pathSegments(url: URL): string[] | undefined {
	const raw = url.pathname;
	if (AMBIGUOUS_ENCODING.test(raw) || raw.includes("\\") || raw.includes("//")) {
		return undefined;
	}
	const segments = raw.split("/").slice(1);
	if (segments.at(-1) === "") {
		segments.pop();
	}
	const decoded: string[] = [];
	for (const segment of segments) {
		let value: string;
		try {
			value = decodeURIComponent(segment);
		} catch {
			return undefined;
		}
		if (value === "" || value === "." || value === ".." || value.includes("/")) {
			return undefined;
		}
		decoded.push(value);
	}
	return decoded;
}

function isWebOrigin(url: URL): boolean {
	return (
		(url.protocol === "https:" || url.protocol === "http:") &&
		url.username === "" &&
		url.password === ""
	);
}

function canonical(url: URL, segments: readonly string[]): string {
	return `${url.origin}/${segments.map((segment) => encodeURIComponent(segment)).join("/")}`;
}

function githubTabAllowed(kind: string, rest: readonly string[]): boolean {
	if (rest.length === 0) {
		return true;
	}
	if (kind !== "pull") {
		return false;
	}
	const [tab, detail, ...extra] = rest;
	if (tab === undefined || !GITHUB_PULL_TABS.has(tab) || extra.length > 0) {
		return false;
	}
	return detail === undefined || (tab === "commits" && COMMIT_SHA.test(detail));
}

/** `/owner/repo/pull/N[/tab]` or `/owner/repo/issues/N`, at any origin (GitHub Enterprise too). */
export function parseGitHubWorkPage(url: URL): WorkPage | undefined {
	const segments = pathSegments(url);
	if (!isWebOrigin(url) || segments === undefined) {
		return undefined;
	}
	const [owner, repo, kind, numberSegment, ...rest] = segments;
	const number = workNumber(numberSegment);
	if (
		owner === undefined ||
		repo === undefined ||
		// GitLab's route marker; keeps the two grammars from ever matching one path.
		owner === "-" ||
		repo === "-" ||
		number === undefined ||
		(kind !== "pull" && kind !== "issues") ||
		!githubTabAllowed(kind, rest)
	) {
		return undefined;
	}
	return {
		provider: "GITHUB",
		origin: url.origin,
		repository: `${owner}/${repo}`,
		kind: kind === "pull" ? "PULL_REQUEST" : "ISSUE",
		number,
		canonicalUrl: canonical(url, [owner, repo, kind, String(number)]),
		view: rest[0] !== undefined && GITHUB_CHANGES_TABS.has(rest[0]) ? "changes" : "overview",
	};
}

/**
 * `/<namespace…>/<project>/-/merge_requests/N[/tab]`, `…/-/issues/N` or `…/-/work_items/N`. The
 * project path is split on GitLab's own route marker `/-/`, never on a fixed index, so nested groups
 * work. Group-level work items (`/groups/…`) have no project and are not work Hephaestus reviews.
 */
export function parseGitLabWorkPage(url: URL): WorkPage | undefined {
	const segments = pathSegments(url);
	if (!isWebOrigin(url) || segments === undefined) {
		return undefined;
	}
	const marker = segments.indexOf("-");
	if (marker < 2 || segments[0] === "groups") {
		return undefined;
	}
	const project = segments.slice(0, marker);
	const [kind, numberSegment, tab, ...extra] = segments.slice(marker + 1);
	const number = workNumber(numberSegment);
	if (number === undefined || extra.length > 0) {
		return undefined;
	}
	const tabAllowed =
		tab === undefined || (kind === "merge_requests" && GITLAB_MERGE_REQUEST_TABS.has(tab));
	if (
		!tabAllowed ||
		(kind !== "merge_requests" && kind !== "issues" && kind !== "work_items") ||
		project.includes("-")
	) {
		return undefined;
	}
	return {
		provider: "GITLAB",
		origin: url.origin,
		repository: project.join("/"),
		kind: kind === "merge_requests" ? "MERGE_REQUEST" : "ISSUE",
		number,
		canonicalUrl: canonical(url, [...project, "-", kind, String(number)]),
		view: tab === "diffs" ? "changes" : "overview",
	};
}

/**
 * Either grammar. The two cannot both match one path: a GitLab route needs the `-` segment, which
 * the GitHub grammar never accepts as an owner or repository.
 */
export function parseWorkPage(href: string | undefined): WorkPage | undefined {
	if (href === undefined) {
		return undefined;
	}
	let url: URL;
	try {
		url = new URL(href);
	} catch {
		return undefined;
	}
	return parseGitLabWorkPage(url) ?? parseGitHubWorkPage(url);
}

/** How the page names its work, for copy before the server has answered: `#12`, `!12`. */
export function workPageLabel(page: Pick<WorkPage, "kind" | "number">): string {
	return page.kind === "MERGE_REQUEST" ? `!${page.number}` : `#${page.number}`;
}

/**
 * A repository's or project's list of pull requests, merge requests or issues. Rows on it can be
 * previewed one at a time; the list itself is never looked up, and only a row the reader opens is.
 */
export interface ListPage {
	provider: WorkPageProvider;
	origin: string;
	repository: string;
	/** The kind of work its rows are. */
	kind: WorkPageKind;
}

const GITHUB_LISTS: Record<string, WorkPageKind> = { pulls: "PULL_REQUEST", issues: "ISSUE" };
const GITLAB_LISTS: Record<string, WorkPageKind> = {
	merge_requests: "MERGE_REQUEST",
	issues: "ISSUE",
	work_items: "ISSUE",
};

/**
 * `/owner/repo/pulls`, `/owner/repo/issues`, `…/-/merge_requests`, `…/-/issues` or `…/-/work_items`:
 * exactly those paths, whatever the query (filters, page). Anything deeper is not the list.
 */
export function parseListPage(href: string | undefined): ListPage | undefined {
	let url: URL;
	try {
		url = new URL(href ?? "");
	} catch {
		return undefined;
	}
	const segments = pathSegments(url);
	if (!isWebOrigin(url) || segments === undefined) {
		return undefined;
	}
	const marker = segments.indexOf("-");
	if (marker === -1) {
		const [owner, repo, list, ...rest] = segments;
		const kind = list === undefined ? undefined : GITHUB_LISTS[list];
		if (
			owner === undefined ||
			repo === undefined ||
			kind === undefined ||
			!Object.hasOwn(GITHUB_LISTS, list ?? "") ||
			rest.length > 0
		) {
			return undefined;
		}
		return { provider: "GITHUB", origin: url.origin, repository: `${owner}/${repo}`, kind };
	}
	const project = segments.slice(0, marker);
	const [list, ...rest] = segments.slice(marker + 1);
	if (
		marker < 2 ||
		segments[0] === "groups" ||
		list === undefined ||
		!Object.hasOwn(GITLAB_LISTS, list) ||
		rest.length > 0
	) {
		return undefined;
	}
	return {
		provider: "GITLAB",
		origin: url.origin,
		repository: project.join("/"),
		kind: GITLAB_LISTS[list] ?? "ISSUE",
	};
}

/**
 * The work a list row names, if it is exactly a canonical work address of this list's repository and
 * kind. It is only ever a selector: the worker checks it against the tab's actual list, and the server
 * decides what the work is.
 */
export function listTarget(list: ListPage, href: string): WorkPage | undefined {
	const page = parseWorkPage(href);
	if (
		page === undefined ||
		page.provider !== list.provider ||
		page.origin !== list.origin ||
		page.repository !== list.repository ||
		page.kind !== list.kind ||
		page.canonicalUrl !== href
	) {
		return undefined;
	}
	return page;
}
