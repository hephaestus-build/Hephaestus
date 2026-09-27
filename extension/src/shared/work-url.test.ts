import { describe, expect, it } from "vitest";

import { listTarget, parseListPage, parseWorkPage } from "~/shared/work-url";
import { required } from "~/testing/required";

describe("parseWorkPage", () => {
	it.each([
		["https://github.com/octo/repo/pull/12", "GITHUB", "PULL_REQUEST", "octo/repo", 12],
		["https://github.com/octo/repo/pull/12/files", "GITHUB", "PULL_REQUEST", "octo/repo", 12],
		[
			"https://github.com/octo/repo/pull/12/commits/abc1234",
			"GITHUB",
			"PULL_REQUEST",
			"octo/repo",
			12,
		],
		["https://github.com/octo/repo/issues/7?q=1#comment", "GITHUB", "ISSUE", "octo/repo", 7],
		["https://ghe.example.com/octo/repo/pull/3", "GITHUB", "PULL_REQUEST", "octo/repo", 3],
		[
			"https://gitlab.lrz.de/group/sub/project/-/merge_requests/1/reports",
			"GITLAB",
			"MERGE_REQUEST",
			"group/sub/project",
			1,
		],
		[
			"https://gitlab.example.test/a/b/-/merge_requests/5/diffs",
			"GITLAB",
			"MERGE_REQUEST",
			"a/b",
			5,
		],
		["https://gitlab.example.test/a/b/-/issues/5", "GITLAB", "ISSUE", "a/b", 5],
		["https://gitlab.example.test/a/b.c/-/work_items/5", "GITLAB", "ISSUE", "a/b.c", 5],
	])("reads %s", (href, provider, kind, repository, number) => {
		expect(parseWorkPage(href)).toMatchObject({ provider, kind, repository, number });
	});

	it("drops sub-page, query and fragment from the canonical address", () => {
		expect(
			parseWorkPage("https://gitlab.example.test/g/p/-/merge_requests/9/pipelines?tab=x#note_1")
				?.canonicalUrl,
		).toBe("https://gitlab.example.test/g/p/-/merge_requests/9");
		expect(parseWorkPage("https://GitHub.com/octo/repo/pull/12/checks")?.canonicalUrl).toBe(
			"https://github.com/octo/repo/pull/12",
		);
	});

	it("tells a GitLab issue and merge request with the same number apart", () => {
		const issue = parseWorkPage("https://gitlab.example.test/g/p/-/issues/4");
		const mergeRequest = parseWorkPage("https://gitlab.example.test/g/p/-/merge_requests/4");
		expect(issue?.kind).toBe("ISSUE");
		expect(mergeRequest?.kind).toBe("MERGE_REQUEST");
		expect(issue?.canonicalUrl).not.toBe(mergeRequest?.canonicalUrl);
	});

	it.each([
		"https://github.com/octo/repo/pulls",
		"https://github.com/octo/repo/pull/12.diff",
		"https://github.com/octo/repo/pull/12/unknown",
		"https://github.com/octo/repo/issues/7/files",
		"https://github.com/octo/repo/pull/0",
		"https://github.com/octo/repo/pull/99999999999",
		"https://github.com/octo/repo/pull/012",
		"https://github.com/octo%2Frepo/x/pull/1",
		"https://github.com/octo/../repo/pull/1",
		"https://user:pass@github.com/octo/repo/pull/1",
		"https://gitlab.example.test/groups/g/-/work_items/3",
		"https://gitlab.example.test/p/-/issues/3",
		"https://gitlab.example.test/g/p/-/issues/3/diffs",
		"https://gitlab.example.test/g/p/-/epics/3",
		"https://gitlab.example.test/g/p/-/merge_requests/3/diffs/extra",
		// oxlint-disable-next-line no-script-url -- The grammar must refuse one.
		"javascript:alert(1)",
		"chrome://extensions",
		"not a url",
	])("refuses %s", (href) => {
		expect(parseWorkPage(href)).toBeUndefined();
	});
});

describe("the view a work page shows", () => {
	it.each([
		["https://github.com/octo/repo/pull/12", "overview"],
		["https://github.com/octo/repo/pull/12/files", "changes"],
		["https://github.com/octo/repo/pull/12/changes", "changes"],
		["https://github.com/octo/repo/pull/12/commits", "overview"],
		["https://github.com/octo/repo/issues/7", "overview"],
		["https://gitlab.example.test/g/p/-/merge_requests/4/diffs", "changes"],
		["https://gitlab.example.test/g/p/-/merge_requests/4/pipelines", "overview"],
	])("%s is the %s", (href, view) => {
		expect(parseWorkPage(href)?.view).toBe(view);
	});
});

describe("parseListPage", () => {
	it.each([
		["https://github.com/octo/repo/pulls", "GITHUB", "octo/repo", "PULL_REQUEST"],
		["https://github.com/octo/repo/pulls?q=is%3Aopen", "GITHUB", "octo/repo", "PULL_REQUEST"],
		["https://github.com/octo/repo/issues", "GITHUB", "octo/repo", "ISSUE"],
		["https://gitlab.example.test/g/sub/p/-/merge_requests", "GITLAB", "g/sub/p", "MERGE_REQUEST"],
		["https://gitlab.example.test/g/p/-/issues?state=opened", "GITLAB", "g/p", "ISSUE"],
		["https://gitlab.example.test/g/p/-/work_items", "GITLAB", "g/p", "ISSUE"],
	])("reads %s", (href, provider, repository, kind) => {
		expect(parseListPage(href)).toStrictEqual({
			provider,
			origin: new URL(href).origin,
			repository,
			kind,
		});
	});

	it.each([
		"https://github.com/octo/repo",
		"https://github.com/octo/repo/pulls/new",
		"https://github.com/octo/repo/issues/7",
		"https://github.com/pulls",
		"https://github.com/octo/repo/constructor",
		"https://gitlab.example.test/groups/g/-/work_items",
		"https://gitlab.example.test/p/-/merge_requests",
		"https://gitlab.example.test/g/p/-/merge_requests/4",
		"https://gitlab.example.test/g/p/-/boards",
		"not a url",
	])("refuses %s", (href) => {
		expect(parseListPage(href)).toBeUndefined();
	});
});

describe("listTarget", () => {
	const pulls = required(parseListPage("https://github.com/octo/repo/pulls?page=2"), "pulls");
	const workItems = required(
		parseListPage("https://gitlab.example.test/g/p/-/work_items"),
		"work items",
	);

	it("accepts exactly a canonical work address of the same repository and kind", () => {
		expect(listTarget(pulls, "https://github.com/octo/repo/pull/12")?.number).toBe(12);
		expect(listTarget(workItems, "https://gitlab.example.test/g/p/-/work_items/5")?.number).toBe(5);
		expect(listTarget(workItems, "https://gitlab.example.test/g/p/-/issues/5")?.number).toBe(5);
	});

	it.each([
		"https://github.com/octo/other/pull/12",
		"https://github.example.test/octo/repo/pull/12",
		"https://github.com/octo/repo/issues/12",
		"https://github.com/octo/repo/pull/12/files",
		"https://github.com/octo/repo/pull/12?x=1",
		"https://github.com/octo/repo/pull/12#top",
		"http://github.com/octo/repo/pull/12",
	])("refuses %s on the pull request list", (href) => {
		expect(listTarget(pulls, href)).toBeUndefined();
	});
});
