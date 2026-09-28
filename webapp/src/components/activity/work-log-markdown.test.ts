import { describe, expect, it } from "vitest";

import type { ActivityWork, UserInfo, WorkItem } from "@/api/types.gen";

import { workLogMarkdown, workLogTitle } from "./work-log-markdown";

const person = (login: string, name: string): UserInfo => ({
	id: login.length,
	login,
	name,
	avatarUrl: "",
	htmlUrl: `https://github.com/${login}`,
});

const repository = {
	id: 1,
	name: "Hephaestus",
	nameWithOwner: "hephaestus-build/Hephaestus",
	htmlUrl: "https://github.com/hephaestus-build/Hephaestus",
	hiddenFromContributions: false,
};

const pullRequest: WorkItem = {
	id: 1,
	type: "PULL_REQUEST",
	number: 123,
	title: "Group the timeline by work",
	state: "MERGED",
	isDraft: false,
	htmlUrl: "https://github.com/hephaestus-build/Hephaestus/pull/123",
	repository,
};

const reviewed: ActivityWork = {
	id: "work:1",
	work: pullRequest,
	actions: [
		{ kind: "REVIEW_APPROVED", count: 1 },
		{ kind: "CODE_COMMENTED", count: 3 },
	],
	lastOccurredAt: new Date(2026, 8, 27, 10),
	people: [person("ada", "Ada Lovelace"), person("bob", "Bob Brenner")],
};

const gone: ActivityWork = {
	id: "event:5f0c",
	actions: [{ kind: "PULL_REQUEST_CLOSED", count: 1 }],
	lastOccurredAt: new Date(2026, 8, 26, 10),
	people: [person("ada", "Ada Lovelace")],
};

describe("workLogMarkdown", () => {
	it("writes one linked line per piece of work, with what happened on it in words", () => {
		const { text, count } = workLogMarkdown([reviewed, gone], {
			title: "Reviews, 21–27 September 2026",
			provider: "GITHUB",
			people: false,
		});
		expect(text).toBe(
			[
				"## Reviews, 21–27 September 2026",
				"",
				"- [Group the timeline by work](https://github.com/hephaestus-build/Hephaestus/pull/123) · hephaestus-build/Hephaestus#123 · approved, 3 comments on code",
				"- A pull request that is no longer available · closed",
			].join("\n"),
		);
		expect(count).toBe(2);
	});

	it("names whose work it was on several people's timeline, and GitLab's own reference", () => {
		const { text } = workLogMarkdown([reviewed], {
			title: "Activity, 27 September 2026",
			provider: "GITLAB",
			people: true,
		});
		expect(text.split("\n").at(-1)).toBe(
			"- [Group the timeline by work](https://github.com/hephaestus-build/Hephaestus/pull/123) · hephaestus-build/Hephaestus!123 · approved, 3 comments on code · Ada Lovelace, Bob Brenner",
		);
	});

	it("escapes someone else's words, and leaves the reference plain", () => {
		const { text } = workLogMarkdown(
			[
				{
					...reviewed,
					work: {
						...pullRequest,
						title: "Fix [WIP] *all* ~~old~~ <script> links &amp; `code`",
						repository: { ...repository, nameWithOwner: "my_org/my_repo" },
					},
				},
			],
			{ title: "Reviews · Ada_Lovelace", provider: "GITHUB", people: false },
		);
		const [heading, , line] = text.split("\n");
		expect(line).toBe(
			String.raw`- [Fix \[WIP\] \*all\* \~\~old\~\~ \<script\> links \&amp; \`code\`](https://github.com/hephaestus-build/Hephaestus/pull/123) · my_org/my_repo#123 · approved, 3 comments on code`,
		);
		// Someone else's words are escaped wherever they appear; the reference stays plain.
		expect(heading).toBe(String.raw`## Reviews · Ada\_Lovelace`);
	});

	it("escapes a name and a title with no link as well", () => {
		const { text } = workLogMarkdown(
			[
				{ ...reviewed, people: [person("sam", "Sam *Star* O_Neil")] },
				{
					...reviewed,
					id: "work:2",
					work: { ...pullRequest, htmlUrl: undefined, title: "Fix *all*" },
				},
			],
			{ title: "Activity", provider: "GITHUB", people: true },
		);
		const [linked, unlinked] = text.split("\n").slice(2);
		expect(linked).toContain(String.raw`· Sam \*Star\* O\_Neil`);
		expect(unlinked).toMatch(/^- Fix \\\*all\\\* · /u);
	});

	it("leaves an ampersand that starts no entity alone", () => {
		const { text } = workLogMarkdown(
			[{ ...reviewed, work: { ...pullRequest, title: "Search & replace" } }],
			{ title: "Activity", provider: "GITHUB", people: false },
		);
		expect(text).toContain("[Search & replace](");
	});

	it("escapes everything a title or an address could inject into the HTML", () => {
		const { html } = workLogMarkdown(
			[
				{
					...reviewed,
					work: {
						...pullRequest,
						title: `<img src=x onerror="alert(1)"> & 'more'`,
						htmlUrl: `https://example.com/"><script>alert(1)</script>`,
					},
				},
			],
			{ title: "<b>Team</b>", provider: "GITHUB", people: false },
		);
		expect(html).toBe(
			"<h2>&lt;b&gt;Team&lt;/b&gt;</h2><ul><li>" +
				'<a href="https://example.com/&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;">' +
				"&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; &#39;more&#39;</a>" +
				" · hephaestus-build/Hephaestus#123 · approved, 3 comments on code</li></ul>",
		);
	});

	it("writes the same list as HTML for a rich paste", () => {
		const { html } = workLogMarkdown([gone], {
			title: "Activity",
			provider: "GITHUB",
			people: false,
		});
		expect(html).toBe(
			"<h2>Activity</h2><ul><li>A pull request that is no longer available · closed</li></ul>",
		);
	});

	it("counts a repeated lifecycle event in words", () => {
		const { text } = workLogMarkdown(
			[{ ...reviewed, actions: [{ kind: "REVIEW_COMMENTED", count: 2 }] }],
			{ title: "Activity", provider: "GITHUB", people: false },
		);
		expect(text).toContain("· commented 2 times");
	});
});

describe("workLogTitle", () => {
	it("names the category and whose work it is, leaving out what is not there", () => {
		expect(workLogTitle("Reviews", "Ada Lovelace")).toBe("Reviews · Ada Lovelace");
		expect(workLogTitle(undefined, "Platform / Payments")).toBe("Platform / Payments");
		expect(workLogTitle("Activity")).toBe("Activity");
	});
});
