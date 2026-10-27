import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { checkPage, type Guide, screenshotProblems } from "./check-docs-pages.ts";

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
		assert.deepEqual(check(page("page_type: feature", "Text."), "admin"), [
			'page.md:1: page_type "feature" is not a admin guide type: overview, tutorial, how-to, reference, explanation, troubleshooting.',
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

	await test("rejects an id, because the file name is the ID", () => {
		assert.deepEqual(
			check(page("id: activity\ntitle: A\ndescription: B.\npage_type: feature", "Text.")),
			["page.md:1: Remove the id. The file name is the ID."],
		);
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
		assert.deepEqual(check(source, "admin"), [
			'page.md:4: A how-to page needs a "Check that it worked" section.',
		]);
		assert.deepEqual(check("1. Rotate the key.", "admin"), []);
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
		assert.equal(check("::::info\nText.\n::::", "admin").length, 1);
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
		["docs/static/img/user/activity/table.light.webp", 80_000],
		["docs/static/img/user/activity/table.dark.webp", 80_000],
		["docs/static/img/user/activity/large.light.webp", 80_000],
		["docs/static/img/user/activity/large.dark.webp", 200_000],
		["docs/static/img/user/activity/half.light.webp", 80_000],
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
				"page.md:3: docs/static/img/user/activity/half.dark.webp does not exist. Run vp run generate:docs-screenshots.",
			],
		);
	});

	await test("holds each image to the size budget", () => {
		assert.deepEqual(
			screenshotProblems("page.md", shot('src="activity/large"\n  alt="A"'), sizeOf),
			[
				"page.md:3: docs/static/img/user/activity/large.dark.webp is 200000 bytes, over the 150000-byte budget.",
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
