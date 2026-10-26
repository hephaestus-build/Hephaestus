import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { checkPage, type Guide } from "./check-docs-pages.ts";

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

	await test("asks a how-to page for the check of its result", () => {
		const source = page("title: Rotate\ndescription: Rotate.\npage_type: how-to", "1. Rotate.");
		assert.ok(
			check(source).includes('page.md:6: A how-to page needs a "Check that it worked" section.'),
		);
	});

	await test("sends a user page's reader to the user or admin guide, not the contributor guide", () => {
		const link = "See [the pipeline](/contributor/practice-review-pipeline).";
		assert.equal(check(feature(link)).length, 1);
		assert.deepEqual(check(page("title: A\ndescription: B.\npage_type: reference", link)), []);
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
	});
});
