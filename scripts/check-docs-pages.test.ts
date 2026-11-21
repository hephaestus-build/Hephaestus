import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
	checkPage,
	type Guide,
	headingAnchors,
	migrationNoteProblems,
	screenshotProblems,
	systemDiagramProblems,
} from "./check-docs-pages.ts";

const page = (frontMatter: string, body: string) => `---\n${frontMatter}\n---\n\n${body}\n`;
const feature = (body: string) =>
	page("title: Activity\ndescription: See your work.\npage_type: feature", body);
const check = (source: string, guide: Guide = "user") => checkPage("page.md", guide, source);

await describe("checkPage", async () => {
	await test("accepts a feature page that follows its template", () => {
		const source = feature(
			"Activity lists your work.\n\n## Read your work\n\n1. Open **Activity**.\n\n## Troubleshooting\n\n### The list is empty",
		);
		assert.deepEqual(check(source), []);
	});

	await test("asks for the front matter that every user page needs", () => {
		assert.deepEqual(check(page("title: Activity", "Text.")), [
			"page.md:1: Add a description to the front matter.",
			"page.md:1: Add a page_type to the front matter: overview, tutorial, feature, reference.",
		]);
	});

	await test("rejects a page type that the guide does not have", () => {
		assert.deepEqual(check(page("page_type: feature", "Text."), "contributor"), [
			'page.md:1: page_type "feature" is not a contributor guide type: overview, tutorial, how-to, reference, explanation.',
		]);
	});

	await test("rejects a body heading that repeats the title", () => {
		assert.deepEqual(check(feature("# Activity\n\nText.")), [
			"page.md:7: Remove the # heading. The front matter title is the page heading.",
		]);
	});

	await test("ignores headings and commands inside fenced code", () => {
		assert.deepEqual(check(feature("Text.\n\n```sh\n# a comment\nvp run dev\n```")), []);
		assert.deepEqual(check(feature("````md\n```sh\n# a comment\n```\n````\n\n# Heading")), [
			"page.md:13: Remove the # heading. The front matter title is the page heading.",
		]);
		assert.deepEqual(check(feature("```\n```sh\n# a comment\n```")), []);
	});

	await test("reports front matter that does not parse, or a field that is not text", () => {
		const unparsed = check(page("title: [unclosed", "Text."));
		assert.equal(unparsed.length, 1);
		assert.match(unparsed[0] ?? "", /^page\.md:1: Fix the front matter: /u);
		assert.ok(
			check(page("title: 2026\ndescription: B.\npage_type: feature", "Text.")).includes(
				"page.md:1: Write the title as text.",
			),
		);
	});

	await test("rejects a # heading in every guide", () => {
		assert.deepEqual(check(page("title: A", "# A\n\nText."), "contributor"), [
			"page.md:5: Remove the # heading. The front matter title is the page heading.",
		]);
	});

	await test("names the section a synonym stands for", () => {
		assert.deepEqual(check(feature("Text.\n\n## Prerequisites")), [
			'page.md:9: Name the section "Before you start".',
		]);
	});

	await test("keeps Troubleshooting last", () => {
		assert.deepEqual(check(feature("Text.\n\n## Troubleshooting\n\n## Read your work")), [
			"page.md:9: Move the Troubleshooting section to the end.",
		]);
	});

	await test("asks a tutorial for steps and the links that follow it", () => {
		const source = page("title: Get started\ndescription: Start.\npage_type: tutorial", "Text.");
		assert.deepEqual(check(source), [
			"page.md:6: A tutorial page needs numbered steps.",
			'page.md:6: A tutorial page needs a "Next steps" section.',
		]);
		assert.deepEqual(check(source.replace("Text.", "1. Sign in.\n\n## Next steps")), []);
	});

	await test("holds a declared type to its template in a guide that is not fully checked", () => {
		const source = page("page_type: how-to", "1. Rotate the key.");
		assert.deepEqual(check(source, "contributor"), [
			'page.md:4: A how-to page needs a "Check that it worked" section.',
		]);
		assert.deepEqual(check("1. Rotate the key.", "contributor"), []);
	});

	await test("asks every admin page for its page type", () => {
		assert.deepEqual(
			check(page("title: Back up\ndescription: Back up the database.", "Text."), "admin"),
			[
				"page.md:1: Add a page_type to the front matter: overview, tutorial, how-to, reference, explanation, troubleshooting.",
			],
		);
	});

	await test("accepts a tutorial in the contributor guide", () => {
		const source = page("page_type: tutorial", "1. Clone the repository.\n\n## Next steps");
		assert.deepEqual(check(source, "contributor"), []);
	});

	await test("sends a user page's reader to the user or admin guide, not the contributor guide", () => {
		const link = "See [the pipeline](/contributor/practice-review-pipeline).";
		assert.deepEqual(check(feature(link)), [
			"page.md:7: Link the user or admin guide. Only the overview and reference pages link the contributor guide.",
		]);
		assert.equal(check(feature("[pipeline]: ../contributor/practice-review-pipeline")).length, 1);
		assert.equal(check(feature('<a href="/contributor/ci-cd">CI</a>')).length, 1);
		assert.deepEqual(check(page("title: A\ndescription: B.\npage_type: reference", link)), []);
		assert.deepEqual(check(page("title: A\ndescription: B.\npage_type: overview", link)), []);
	});

	await test("keeps repository commands and source paths out of the user guide", () => {
		assert.deepEqual(check(feature("Run `vp run dev` or open `webapp/src`. Enter `/heph`.")), [
			"page.md:7: Remove the repository command or path `vp run dev` from the user guide.",
			"page.md:7: Remove the repository command or path `webapp/src` from the user guide.",
		]);
	});

	await test("allows only the note, tip and caution callouts in every guide", () => {
		assert.deepEqual(check("Text.\n\n:::warning[Careful]\nText.\n:::", "contributor"), [
			'page.md:3: Use a note, tip or caution callout, not "warning".',
		]);
		const reference = page(
			"title: Sizing\ndescription: The sizes.\npage_type: reference",
			"::::info\nText.\n::::",
		);
		assert.equal(check(reference, "admin").length, 1);
	});

	await test("asks a user page to show the product with a screenshot component", () => {
		assert.deepEqual(check(feature("![Practice profile](/img/profile.png)")), [
			"page.md:7: Show the product with <Screenshot>, which has a light and a dark image.",
		]);
	});
});

const shot = (attributes: string) => `Text.\n\n<Screenshot\n  ${attributes}\n/>`;

await describe("screenshotProblems", async () => {
	const sizes = new Map([
		["docs/public/img/user/activity/table.light.webp", 80_000],
		["docs/public/img/user/activity/table.dark.webp", 80_000],
		["docs/public/img/user/activity/large.light.webp", 80_000],
		["docs/public/img/user/activity/large.dark.webp", 200_000],
		["docs/public/img/user/activity/half.light.webp", 80_000],
	]);
	const sizeOf = (image: string) => sizes.get(image);

	await test("accepts a screenshot with alt text and both images within the budget", () => {
		assert.deepEqual(
			screenshotProblems("page.md", shot('src="activity/table"\n  alt="Activity table."'), sizeOf),
			[],
		);
	});

	await test("asks for the image in both color schemes", () => {
		assert.deepEqual(
			screenshotProblems("page.md", shot('src="activity/half"\n  alt="A"'), sizeOf),
			[
				"page.md:3: docs/public/img/user/activity/half.dark.webp does not exist. Run vp run generate:docs-screenshots.",
			],
		);
	});

	await test("holds each image to the size budget", () => {
		assert.deepEqual(
			screenshotProblems("page.md", shot('src="activity/large"\n  alt="A"'), sizeOf),
			[
				"page.md:3: docs/public/img/user/activity/large.dark.webp is 200000 bytes, over the 150000-byte budget.",
			],
		);
	});

	await test("asks for alt text, because MDX does not check the component's props", () => {
		assert.deepEqual(screenshotProblems("page.md", shot('src="activity/table"'), sizeOf), [
			"page.md:3: Give the <Screenshot> a literal alt text that says what the image shows.",
		]);
		assert.deepEqual(
			screenshotProblems(
				"page.md",
				shot(`src="activity/table"\n  alt="${"a".repeat(156)}"`),
				sizeOf,
			),
			["page.md:3: Shorten the alt text to 155 characters (156)."],
		);
		assert.equal(
			screenshotProblems("page.md", shot('src="activity/table"\n  alt={description}'), sizeOf)
				.length,
			1,
		);
	});

	await test("asks for a literal src", () => {
		assert.deepEqual(screenshotProblems("page.md", '<Screenshot src={name} alt="A" />', sizeOf), [
			'page.md:1: Give the <Screenshot> a literal src, such as src="practice-profile/overview".',
		]);
	});
});

const anchors = (docsPage: string) =>
	docsPage === "admin/upgrade"
		? headingAnchors(
				"## Stopped schema upgrades\n\n```md\n## Not a heading\n```\n## Drain [#drain-queues]\n",
			)
		: undefined;
const linking = (link: string) => [
	{ file: "n.md", source: `#### 🔴 Drain the queues\n\nSee [it](${link}).\n` },
];

await describe("migration note stops and links", async () => {
	await test("a note that stops servers starts with the stop heading", () => {
		assert.match(
			migrationNoteProblems(
				[{ file: "n.md", source: "#### 🔴 Drain the queues\n\nStop the old servers first.\n" }],
				anchors,
			).join("\n"),
			/start its heading with "#### 🔴 Stop every role"/u,
		);
		assert.deepEqual(
			migrationNoteProblems(
				[
					{
						file: "n.md",
						source: "#### 🔴 Stop every role to drain\n\nStop the old servers first.\n",
					},
				],
				anchors,
			),
			[],
		);
	});
	await test("rollback and recovery text, and other wording, do not count as a stop", () => {
		for (const [source, stops] of [
			["Stop every runtime role first.", true],
			["Stop all application replicas, then migrate.", true],
			["To roll back, stop every role and restore the backup.", false],
			["Keep PostgreSQL running and stop nothing.", false],
			["Do not stop the servers.", false],
		] as const) {
			const problems = migrationNoteProblems(
				[{ file: "n.md", source: `#### 🔴 Drain the queues\n\n${source}\n` }],
				anchors,
			);
			assert.equal(problems.length > 0, stops, source);
		}
	});
	await test("each docs link and anchor in a note resolves", () => {
		assert.deepEqual(
			migrationNoteProblems(
				linking("https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades"),
				anchors,
			),
			[],
		);
		assert.deepEqual(
			migrationNoteProblems(
				linking("https://docs.hephaestus.build/admin/upgrade#drain-queues"),
				anchors,
			),
			[],
		);
		assert.deepEqual(
			migrationNoteProblems(
				linking("https://docs.hephaestus.build/admin/upgrade#not-a-heading"),
				anchors,
			),
			["n.md:3: docs/admin/upgrade has no heading with the anchor #not-a-heading."],
		);
		assert.deepEqual(
			migrationNoteProblems(linking("https://docs.hephaestus.build/admin/kubernetes#x"), anchors),
			["n.md:3: No docs page serves https://docs.hephaestus.build/admin/kubernetes#x."],
		);
	});
});

const table = "| From | To |\n| --- | --- |\n| Server | PostgreSQL |";
const diagram = (attributes: string, children = table) =>
	`Text.\n\n<SystemDiagram ${attributes}>\n\n${children}\n\n</SystemDiagram>\n`;
const valid = 'name="containers" title="Container view: Hephaestus" alt="Container diagram."';
const checkDiagram = (source: string) =>
	systemDiagramProblems("page.mdx", source, (name) => name === "containers");

await describe("systemDiagramProblems", async () => {
	await test("accepts a view with its source, title, alt text and connections table", () => {
		assert.deepEqual(checkDiagram(diagram(valid)), []);
	});

	await test("asks for a source that exists, because the SVG has none of its own", () => {
		assert.deepEqual(checkDiagram(diagram(valid.replace("containers", "context"))), [
			"page.mdx:3: docs/diagrams/context.hyl does not exist. Name the source of the view.",
		]);
		assert.deepEqual(checkDiagram(diagram(valid.replace('name="containers"', "name={view}"))), [
			'page.mdx:3: Give the <SystemDiagram> a literal name, such as name="containers".',
		]);
	});

	await test("asks for a title that states the view and its scope", () => {
		assert.deepEqual(
			checkDiagram(diagram(valid.replace("Container view: Hephaestus", "Containers"))),
			[
				'page.mdx:3: Give the <SystemDiagram> a literal title "<view>: <scope>", such as "Container view: Hephaestus".',
			],
		);
	});

	await test("asks for alt text, because MDX does not check the component's props", () => {
		assert.deepEqual(checkDiagram(diagram(valid.replace(' alt="Container diagram."', ""))), [
			"page.mdx:3: Give the <SystemDiagram> a literal alt text that says what the diagram shows.",
		]);
		assert.deepEqual(checkDiagram(diagram(valid.replace("Container diagram.", "a".repeat(156)))), [
			"page.mdx:3: Shorten the alt text to 155 characters (156).",
		]);
	});

	await test("asks for the connections as a table, because the SVG text is paths", () => {
		const missing =
			"page.mdx:3: Put a table of every connection of the view between <SystemDiagram> and </SystemDiagram>.";
		assert.deepEqual(checkDiagram(diagram(valid, "The server reaches PostgreSQL.")), [missing]);
		assert.deepEqual(checkDiagram(`<SystemDiagram ${valid} />\n`), [missing.replace(":3:", ":1:")]);
		assert.match(
			checkDiagram(`<SystemDiagram ${valid}>\n\n${table}\n`).join("\n"),
			/^page\.mdx:1: The page is not valid MDX: Expected a closing tag for `<SystemDiagram>`/u,
		);
		// GFM needs as many delimiter cells as header cells, or the lines are a paragraph.
		assert.deepEqual(
			checkDiagram(diagram(valid, "| From | To |\n| --- |\n| Server | PostgreSQL |")),
			[missing],
		);
	});

	await test("reads quoted values that hold a > or a code span, across lines", () => {
		const attributes =
			'name="containers"\n  title="Container view: `server`"\n  alt="Server -> PostgreSQL."';
		assert.deepEqual(checkDiagram(diagram(attributes)), []);
		assert.deepEqual(checkDiagram(diagram(valid, "From | To\n:-- | --:\nServer | PostgreSQL")), []);
	});

	await test("reads single-quoted values as literals, but not expressions", () => {
		assert.deepEqual(checkDiagram(diagram(valid.replaceAll('"', "'"))), []);
	});

	await test("reports each view on its own line", () => {
		assert.deepEqual(
			checkDiagram(diagram(valid) + diagram(valid.replace(' alt="Container diagram."', ""))),
			[
				"page.mdx:12: Give the <SystemDiagram> a literal alt text that says what the diagram shows.",
			],
		);
	});

	await test("ignores the component in code, where a page only shows it", () => {
		assert.deepEqual(
			checkDiagram(
				'Use `<SystemDiagram name="<name>">` or ``<SystemDiagram title="`a`">``.\n\n```mdx\n<SystemDiagram name="x">\n```\n',
			),
			[],
		);
	});
});
