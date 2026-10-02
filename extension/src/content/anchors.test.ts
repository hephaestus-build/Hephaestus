// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";

import {
	findListRows,
	findReportSlot,
	gitLabPageAllowed,
	INSPECT_TAG,
	MAX_LIST_ROWS,
	REPORT_HOST_TAG,
} from "~/content/anchors";
import { parseListPage } from "~/shared/work-url";
import { required } from "~/testing/required";

afterEach(() => {
	document.body.innerHTML = "";
});

/** The shapes recorded from signed-in provider pages (`docs/contributor/browser-extension.mdx`). */
const GITHUB_CONVERSATION = `
	<div class="js-pull-discussion-timeline">
		<div class="js-discussion">
			<div id="body" data-partial-name="pullRequestsConversationsRoute.Body"></div>
			<div id="timeline" data-partial-name="pullRequestsConversationsRoute.Timeline"></div>
		</div>
	</div>`;

const GITHUB_CHANGES = `
	<div id="diff" data-testid="diff-content">
		<div id="files" data-testid="progressive-diffs-list"><div role="region" id="diff-abc"></div></div>
	</div>`;

/** The server-rendered files view a signed-out visitor is redirected to from `/changes`. */
const GITHUB_FILES = `
	<div class="Layout-main">
		<div id="files" class="diff-view js-diff-container">
			<div class="js-file-filter-blankslate" hidden>No files match</div>
			<div id="rails-list" class="js-diff-progressive-container"><copilot-diff-entry></copilot-diff-entry></div>
		</div>
	</div>`;

const GITHUB_ISSUE = `
	<div id="column">
		<div id="issue" data-testid="issue-viewer-issue-container"><div data-testid="issue-body"></div></div>
		<div id="activity" class="react-comments-container"></div>
	</div>`;

const GITLAB_OVERVIEW = `
	<div class="merge-request-overview"><section>
		<div id="discussion" class="issuable-discussion">
			<div class="detail-page-description"></div>
			<div class="emoji-block"></div>
			<h2 id="merge-request-widgets-heading">Merge request reports</h2>
			<div id="widget-state" class="mr-state-widget"></div>
			<div id="notes"></div>
		</div>
	</section></div>`;

const GITLAB_CHANGES = `
	<div id="diffs" class="tab-pane diffs">
		<article class="rd-app" aria-label="Changes view"><div class="rd-app-body">
			<section id="diff-files" class="rd-app-content" aria-label="Diff files">
				<div id="diff-list" class="rd-app-diffs-list"><diff-file data-testid="rd-diff-file"></diff-file></div>
			</section>
		</div></article>
	</div>`;

const GITLAB_WORK_ITEM = `
	<div data-testid="detail-layout-container">
		<div data-testid="detail-layout-content"><section class="work-item-view"></section></div>
		<div data-testid="detail-layout-sidebar"></div>
		<div id="work-item-activity" data-testid="detail-layout-activity"><div id="work-item-notes" class="work-item-notes"></div></div>
	</div>`;

function byId(id: string): Element | null {
	return document.getElementById(id);
}

describe("GitHub report slots", () => {
	it("puts the report between a pull request's description and its timeline", () => {
		document.body.innerHTML = GITHUB_CONVERSATION;
		expect(findReportSlot(document, "GITHUB")).toStrictEqual({
			parent: document.querySelector(".js-discussion"),
			before: byId("timeline"),
			layout: "github-pull-request",
		});
	});

	it("puts the report above the changed files", () => {
		document.body.innerHTML = GITHUB_CHANGES;
		expect(findReportSlot(document, "GITHUB")).toStrictEqual({
			parent: byId("diff"),
			before: byId("files"),
			layout: "github-diff",
		});
	});

	it("puts the report above the changed files in the server-rendered files view", () => {
		document.body.innerHTML = GITHUB_FILES;
		expect(findReportSlot(document, "GITHUB")).toStrictEqual({
			parent: byId("files"),
			before: byId("rails-list"),
			layout: "github-diff",
		});
	});

	it("puts the report between an issue's description and its activity", () => {
		document.body.innerHTML = GITHUB_ISSUE;
		expect(findReportSlot(document, "GITHUB")).toStrictEqual({
			parent: byId("column"),
			before: byId("activity"),
			layout: "github-issue",
		});
	});
});

describe("GitLab report slots", () => {
	it("joins the merge request's reports, before its activity and never at a global #notes", () => {
		document.body.innerHTML = `<div id="notes" class="tab-pane"></div>${GITLAB_OVERVIEW}`;
		expect(findReportSlot(document, "GITLAB")).toStrictEqual({
			parent: byId("discussion"),
			before: document.querySelector(".issuable-discussion > #notes"),
			layout: "gitlab-merge-request",
		});
	});

	it("puts the report above the Rapid Diffs file list", () => {
		document.body.innerHTML = GITLAB_CHANGES;
		expect(findReportSlot(document, "GITLAB")).toStrictEqual({
			parent: byId("diff-files"),
			before: byId("diff-list"),
			layout: "gitlab-diff",
		});
	});

	it("goes first inside a work item's activity area, adding no grid child", () => {
		document.body.innerHTML = GITLAB_WORK_ITEM;
		const slot = findReportSlot(document, "GITLAB");
		expect(slot).toStrictEqual({
			parent: byId("work-item-activity"),
			before: byId("work-item-notes"),
			layout: "gitlab-work-item",
		});
	});
});

describe("choosing among slots", () => {
	it("takes the visible changes tab over the hidden overview pane, and the reverse", () => {
		document.body.innerHTML = `
			<div id="overview-pane">${GITLAB_OVERVIEW}</div>
			<div id="changes-pane" style="display: none">${GITLAB_CHANGES}</div>`;
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-merge-request");
		byId("overview-pane")?.setAttribute("style", "display: none");
		byId("changes-pane")?.removeAttribute("style");
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-diff");
	});

	it("ignores GitLab's inactive content-visibility pane and restores it when shown", () => {
		document.body.innerHTML = `${GITLAB_OVERVIEW}${GITLAB_CHANGES}`;
		const diff = byId("diffs");
		const overview = document.querySelector(".merge-request-overview");
		diff?.setAttribute("style", "display: block; visibility: visible; content-visibility: hidden");
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-merge-request");
		overview?.setAttribute("style", "content-visibility: hidden");
		expect(findReportSlot(document, "GITLAB")).toBeUndefined();
		diff?.setAttribute("style", "content-visibility: visible");
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-diff");
		// Auto is a rendering optimisation, not a closed tab: it must remain eligible offscreen.
		diff?.setAttribute("style", "content-visibility: auto");
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-diff");
		diff?.setAttribute("style", "content-visibility: hidden");
		overview?.removeAttribute("style");
		expect(findReportSlot(document, "GITLAB")?.layout).toBe("gitlab-merge-request");
	});

	it("skips a slot inside a hidden element", () => {
		document.body.innerHTML = `<div hidden>${GITHUB_CHANGES}</div>${GITHUB_CONVERSATION}`;
		expect(findReportSlot(document, "GITHUB")?.layout).toBe("github-pull-request");
	});

	it("gives no slot when nothing the provider renders matches, rather than a substitute", () => {
		document.body.innerHTML = `<div data-component="PageHeader"><h1>Title</h1></div>`;
		expect(findReportSlot(document, "GITHUB")).toBeUndefined();
		document.body.innerHTML = `<div style="display: none">${GITLAB_CHANGES}</div>`;
		expect(findReportSlot(document, "GITLAB")).toBeUndefined();
	});

	it.each([
		["GITHUB", GITHUB_CONVERSATION],
		["GITHUB", GITHUB_CHANGES],
		["GITHUB", GITHUB_FILES],
		["GITHUB", GITHUB_ISSUE],
		["GITLAB", GITLAB_OVERVIEW],
		["GITLAB", GITLAB_CHANGES],
		["GITLAB", GITLAB_WORK_ITEM],
	] as const)("keeps the same %s slot once the report is inserted in it", (provider, html) => {
		document.body.innerHTML = html;
		const before = required(findReportSlot(document, provider), "a slot");
		before.parent.insertBefore(document.createElement(REPORT_HOST_TAG), before.before);
		expect(findReportSlot(document, provider)).toStrictEqual(before);
	});
});

describe("gitLabPageAllowed", () => {
	it("accepts project pages and a page without the attribute, and nothing else", () => {
		expect(gitLabPageAllowed({ dataset: { page: "projects:merge_requests:show" } })).toBe(true);
		expect(gitLabPageAllowed({ dataset: {} })).toBe(true);
		expect(gitLabPageAllowed({ dataset: { page: "groups:work_items:show" } })).toBe(false);
	});
});

/** A row of GitHub's list view, as the pull request list names its title link, or the issue list its. */
const githubRow = (
	id: string,
	href: string,
	testId: "listitem-title-link" | "issue-pr-title-link" = "listitem-title-link",
	title = "Fix the flaky test",
) => `
	<li id="${id}" tabindex="0" aria-label="${title}.">
		<div data-listview-item-title-container="true">
			<h3 id="${id}-heading"><a id="${id}-title" data-testid="${testId}" tabindex="-1" href="${href}"><span data-component="Text">${title}</span></a></h3>
		</div>
		<div><a href="${href}">3 comments</a></div>
	</li>`;

/** A row of GitLab's issuable list, with the full-row overlay link its work items put first. */
const gitlabRow = (id: string, href: string) => `
	<li id="${id}" data-testid="issuable-container">
		<a data-testid="issuable-card-link-overlay" aria-hidden="true" tabindex="-1" href="${href}"></a>
		<div id="${id}-main" class="issuable-main-info">
			<div data-testid="issuable-title"><a id="${id}-title" data-testid="issuable-title-link" href="${href}">Title</a></div>
			<div class="issuable-info"></div>
		</div>
		<div class="issuable-meta"></div>
	</li>`;

describe("list rows", () => {
	const pulls = parseListPage("https://github.com/octo/repo/pulls");
	const workItems = parseListPage("https://gitlab.example.test/g/p/-/work_items");

	it("finds GitHub's rows by their title link, with the control after the heading and the preview in the row", () => {
		document.body.innerHTML = `
			<ul role="list" data-listview-component="items-list">
				${githubRow("row-12", "https://github.com/octo/repo/pull/12")}
				${githubRow("row-13", "https://github.com/octo/repo/pull/13?w=1#top")}
				${githubRow("row-14", "https://github.com/octo/other/pull/14")}
				${githubRow("row-15", "https://github.com/octo/repo/pull/15/files")}
			</ul>`;
		const rows = findListRows(document, required(pulls));
		expect(
			rows.map(({ work, row, after, preview }) => [
				work.number,
				row.id,
				after.id,
				preview.parent.id,
				preview.before,
				preview.layout,
			]),
		).toStrictEqual([
			[12, "row-12", "row-12-heading", "row-12", null, "list-preview"],
			[13, "row-13", "row-13-heading", "row-13", null, "list-preview"],
		]);
	});

	it("puts GitHub's preview in the column its title and meta line share, when the row has one", () => {
		document.body.innerHTML = `
			<ul role="list" data-listview-component="items-list">
				<li id="row-12">
					<input type="checkbox" aria-label="Select" />
					<div id="main">
						<div data-listview-item-title-container="true">
							<h3><a data-testid="listitem-title-link" href="https://github.com/octo/repo/pull/12">Fix</a></h3>
						</div>
						<div>#12 · opened today</div>
					</div>
				</li>
			</ul>`;
		const [row] = findListRows(document, required(pulls));
		expect(row?.row.id).toBe("row-12");
		expect(row?.preview.parent.id).toBe("main");
	});

	it("finds GitHub's issue rows through its virtualization wrappers by the title link", () => {
		const issues = required(parseListPage("https://github.com/octo/repo/issues"));
		document.body.innerHTML = `
			<ul role="list" data-listview-component="items-list">
				<div><div>${githubRow("row-7", "https://github.com/octo/repo/issues/7", "issue-pr-title-link")}</div></div>
				${githubRow("row-8", "https://github.com/octo/repo/issues/8", "issue-pr-title-link")}
			</ul>
			<ul><li><div data-listview-item-title-container="true"><h3><a data-testid="issue-pr-title-link" href="https://github.com/octo/repo/issues/9">Outside the list view</a></h3></div></li></ul>`;
		expect(
			findListRows(document, issues).map(({ work, row, after }) => [work.number, row.id, after.id]),
		).toStrictEqual([
			[7, "row-7", "row-7-heading"],
			[8, "row-8", "row-8-heading"],
		]);
	});

	it("finds GitLab's rows by their title link, never the row's overlay link", () => {
		document.body.innerHTML = `
			<ul data-testid="work-item-list-wrapper">
				${gitlabRow("a", "https://gitlab.example.test/g/p/-/work_items/5")}
				${gitlabRow("b", "https://gitlab.example.test/g/p/-/issues/6")}
			</ul>`;
		expect(
			findListRows(document, required(workItems)).map(({ row, after, preview }) => [
				row.id,
				after.id,
				preview.parent.id,
			]),
		).toStrictEqual([
			["a", "a-title", "a-main"],
			["b", "b-title", "b-main"],
		]);
	});

	it("skips hidden rows and stops at a page's worth", () => {
		const hidden = githubRow("hidden", "https://github.com/octo/repo/pull/1").replace(
			'tabindex="0"',
			'style="display: none"',
		);
		const many = Array.from({ length: MAX_LIST_ROWS + 20 }, (_, index) =>
			githubRow(`r${index}`, `https://github.com/octo/repo/pull/${index + 10}`),
		).join("");
		document.body.innerHTML = `<ul data-listview-component="items-list">${hidden}${many}</ul>`;
		const rows = findListRows(document, required(pulls));
		expect(rows).toHaveLength(MAX_LIST_ROWS);
		expect(rows.some(({ work }) => work.number === 1)).toBe(false);
	});

	it("finds nothing on an unknown list shape rather than guessing", () => {
		document.body.innerHTML = `
			<p><a href="https://github.com/octo/repo/pull/12">In prose</a></p>
			<ul><li><a href="https://github.com/octo/repo/pull/13">A list in a comment</a></li></ul>
			<li data-testid="issuable-container"><a href="https://github.com/octo/repo/pull/14">No title hook</a></li>`;
		expect(findListRows(document, required(pulls))).toStrictEqual([]);
	});

	it("ignores its own elements", () => {
		document.body.innerHTML = `<ul data-listview-component="items-list"><li><${INSPECT_TAG}></${INSPECT_TAG}><${REPORT_HOST_TAG}></${REPORT_HOST_TAG}></li></ul>`;
		expect(findListRows(document, required(pulls))).toStrictEqual([]);
	});
});
