/**
 * Provider pages reduced to the structure the report anchors on, as signed-in live pages render it
 * (`docs/contributor/browser-extension.mdx` § Where the report goes): a pull request's conversation
 * partials and its changes list, an issue's description container, a merge request's reports area
 * and its Rapid Diffs list, and a work item's activity area. Nothing else of either site.
 */
export const GITLAB_ORIGIN = "https://gitlab.example.test";
export const GITHUB_ORIGIN = "https://github.com";

export const GITLAB_MR = `${GITLAB_ORIGIN}/ext/demo/-/merge_requests/4`;
export const GITLAB_ISSUE = `${GITLAB_ORIGIN}/ext/demo/-/issues/4`;
export const GITLAB_MR5 = `${GITLAB_ORIGIN}/ext/demo/-/merge_requests/5`;
export const GITLAB_MR6 = `${GITLAB_ORIGIN}/ext/demo/-/merge_requests/6`;
export const GITHUB_PR = `${GITHUB_ORIGIN}/hephaestus-build/demo/pull/7`;
export const GITHUB_ISSUE = `${GITHUB_ORIGIN}/hephaestus-build/demo/issues/8`;

function page(body: string, dataPage?: string): string {
	return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fixture</title>
<style>body { margin: 0; font: 14px/1.5 sans-serif; } main { max-width: 960px; margin: 0 auto; padding: 16px; }</style>
</head><body${dataPage === undefined ? "" : ` data-page="${dataPage}"`}>${body}
<script>
	// The provider's own client-side navigation, which the content script must follow.
	window.navigateTo = (path) => { history.pushState({}, "", path); document.title = path; };
</script></body></html>`;
}

/**
 * A merge request with both of its tab panes, as GitLab keeps them: the overview's reports area and
 * the changes' Rapid Diffs list. `showing` picks the visible pane; `showTab` switches, as GitLab's
 * tabs do, with the URL.
 */
export function gitLabMergeRequest(title: string, showing: "overview" | "changes" = "overview") {
	return page(
		`<main><div class="detail-page-header is-merge-request">
			<h1 data-testid="title-content">${title}</h1>
		</div>
		<div id="overview-pane" class="tab-pane"${showing === "overview" ? "" : " hidden"}>
			<div class="merge-request-overview"><section><div class="issuable-discussion">
				<div class="detail-page-description"><p>Adds the login screen.</p></div>
				<div class="emoji-block">👍 2</div>
				<h2 id="merge-request-widgets-heading">Merge request reports</h2>
				<div id="widget-state" class="mr-state-widget"><p>Ready to merge</p></div>
				<div id="notes"><p id="first-note">Activity</p></div>
			</div></section></div>
		</div>
		<div id="diffs" class="tab-pane diffs"${showing === "changes" ? "" : " hidden"}>
			<article class="rd-app" aria-label="Changes view"><div class="rd-app-body">
				<section class="rd-app-content" aria-label="Diff files">
					<div class="rd-app-diffs-list"><diff-file data-testid="rd-diff-file">src/login.ts</diff-file></div>
				</section>
			</div></article>
		</div></main>
		<script>
			// GitLab's tabs: a click changes the address and which pane shows. The suite switches with a
			// "fixture:tab" event, so it needs no page globals.
			window.addEventListener("fixture:tab", (event) => showTab(event.detail));
			window.showTab = (tab) => {
				const base = location.pathname.replace(/\\/diffs$/, "");
				history.pushState({}, "", tab === "changes" ? base + "/diffs" : base);
				document.getElementById("overview-pane").hidden = tab !== "overview";
				document.getElementById("diffs").hidden = tab !== "changes";
			};
		</script>`,
		"projects:merge_requests:show",
	);
}

/** A project work item: named grid areas, with the activity area holding the notes. */
export function gitLabWorkItem(title: string): string {
	return page(
		`<main id="content-body"><div data-testid="work-item-detail" class="work-item-detail">
			<h1 data-testid="work-item-title">${title}</h1>
			<div data-testid="detail-layout-container" style="display: grid; grid-template-areas: 'content sidebar' 'activity sidebar';">
				<div data-testid="detail-layout-content" style="grid-area: content"><section class="work-item-view"><div class="work-item-description-wrapper">Description</div></section></div>
				<div data-testid="detail-layout-sidebar" style="grid-area: sidebar">Assignees</div>
				<div data-testid="detail-layout-widgets"></div>
				<div data-testid="detail-layout-activity" style="grid-area: activity"><div class="work-item-notes"><p id="first-note">Activity</p></div></div>
			</div>
		</div></main>`,
		"projects:work_items:show",
	);
}

/** A pull request's conversation: the description partial, then the timeline partial. */
export function gitHubPullRequest(title: string): string {
	return page(`<main><div data-component="PageHeader"><h1 data-component="PH_Title">${title}</h1></div>
		<div class="js-pull-discussion-timeline"><div class="js-discussion">
			<div data-partial-name="pullRequestsConversationsRoute.Body"><p>Fixes the flaky test.</p></div>
			<div data-partial-name="pullRequestsConversationsRoute.Timeline"><div id="js-timeline-progressive-loader"></div><p id="first-note">Activity</p></div>
		</div></div></main>`);
}

/** A pull request's changes: the diff area, then its list of file diffs. */
export function gitHubPullRequestChanges(title: string): string {
	return page(`<main><div data-component="PageHeader"><h1 data-component="PH_Title">${title}</h1></div>
		<div data-testid="diff-content"><div data-testid="progressive-diffs-list">
			<div role="region" id="diff-abc">src/flaky.test.ts</div>
		</div></div></main>`);
}

/** The server-rendered files view GitHub serves a signed-out visitor for `/files`. */
export function gitHubPullRequestFiles(title: string): string {
	return page(`<main><div data-component="PageHeader"><h1 data-component="PH_Title">${title}</h1></div>
		<div class="Layout-main"><div id="files" class="diff-view js-diff-container">
			<div class="js-file-filter-blankslate" hidden>No files match</div>
			<div class="js-diff-progressive-container"><copilot-diff-entry>src/flaky.test.ts</copilot-diff-entry></div>
		</div></div></main>`);
}

/** An issue's description container, then its activity. */
export function gitHubIssue(title: string): string {
	return page(`<main><div data-component="PageHeader"><h1 data-component="PH_Title">${title}</h1></div>
		<div id="issue-column">
			<div data-testid="issue-viewer-issue-container"><div data-testid="issue-body">Description</div></div>
			<div class="react-comments-container"><p id="first-note">Activity</p></div>
		</div></main>`);
}

/** A page with no report slot: a repository home, a pull request's commits. */
export function gitHubRepositoryHome(): string {
	return page("<main><h1>hephaestus-build/demo</h1></main>");
}

/**
 * GitHub's current React list, as the real page lays a row out: one grid per row, with the selection
 * checkbox and the state icon in their own columns, the title and the description line sharing one
 * column, and metadata at the end — so the title container's parent is the whole row. Each row has
 * the tinted background GitHub gives a hovered row, which a preview must let show through. The issue list
 * wraps each row in two virtualization `div`s and names its title link differently.
 */
export function gitHubWorkList(kind: "pull" | "issues"): string {
	const areas = `'selection leading title meta' 'selection leading description meta'`;
	return page(`<main><h1>${kind === "pull" ? "Pull requests" : "Issues"}</h1>
		<ul role="list" data-listview-component="items-list" data-density="default">
			${[7, 8]
				.map(
					(
						number,
					) => `${kind === "issues" ? "<div><div>" : ""}<li tabindex="0" aria-label="Work ${number}. More information available below." style="display:grid;grid-template-columns:32px 24px 1fr auto;grid-template-areas:${areas};padding:8px 16px;background:#f6f8fa">
				<div style="grid-area:selection"><input type="checkbox" aria-label="Select work ${number}" /></div>
				<div style="grid-area:leading" aria-hidden="true">●</div>
				<div data-listview-item-title-container="true" style="grid-area:title"><h3 style="margin:0"><a data-testid="${kind === "issues" ? "issue-pr-title-link" : "listitem-title-link"}" href="/hephaestus-build/demo/${kind}/${number}">Work ${number}</a></h3></div>
				<div style="grid-area:description">#${number} · opened today</div>
				<div style="grid-area:meta">Native status</div>
			</li>${kind === "issues" ? "</div></div>" : ""}`,
				)
				.join("")}
		</ul></main>`);
}

/** GitLab's issuable list: the preview must not become a third column beside native metadata. */
export function gitLabWorkList(kind: "merge_requests" | "issues" | "work_items"): string {
	return page(
		`<main id="content-body"><h1>${kind === "merge_requests" ? "Merge requests" : "Issues"}</h1>
		<div class="issuable-list-container"><ul class="content-list issuable-list issues-list">
			${[4, 5]
				.map(
					(
						number,
					) => `<li data-testid="issuable-container" style="display:flex;gap:16px;position:relative">
				${kind === "merge_requests" ? "" : `<a data-testid="issuable-card-link-overlay" aria-hidden="true" tabindex="-1" href="/ext/demo/-/${kind}/${number}" style="position:absolute;inset:0;z-index:1"></a>`}
				<div class="issuable-main-info" style="flex:1;min-width:0"><div data-testid="issuable-title" style="font-size:0"><a data-testid="issuable-title-link" style="font-size:14px" href="/ext/demo/-/${kind}/${number}">Work ${number}</a></div><div class="issuable-info">Updated today</div></div>
				<div class="issuable-meta">Native status</div>
			</li>`,
				)
				.join("")}
		</ul></div></main>`,
		`projects:${kind}:index`,
	);
}
