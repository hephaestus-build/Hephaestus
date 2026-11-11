import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";
import { isMap, isSeq, parseDocument } from "yaml";

const cacheAction = await readFile(".github/actions/setup-caches/action.yml", "utf8");
const browserAction = await readFile(".github/actions/setup-browsers/action.yml", "utf8");

function actionStep(name: string): string {
	const marker = `    - name: ${name}\n`;
	const start = cacheAction.indexOf(marker);
	assert.ok(start !== -1);
	const end = cacheAction.indexOf("\n    - name:", start + marker.length);
	return cacheAction.slice(start, end === -1 ? undefined : end);
}

await describe("CI cache policy", async () => {
	await test("only trusted default-branch runs publish Gradle caches", async () => {
		const setup = actionStep("Set up Gradle");
		assert.match(setup, /gradle\/actions\/setup-gradle@[a-f0-9]{40}/u);
		assert.match(setup, /cache-read-only:.*github.ref != format/u);
		assert.match(setup, /github.event.repository.default_branch/u);
		assert.match(
			setup,
			/!contains\(fromJSON\('\["push","schedule","workflow_dispatch"\]'\), github.event_name\)/u,
		);
		assert.match(setup, /validate-wrappers: true/u);
		assert.match(setup, /cache-provider: enhanced/u);
		assert.doesNotMatch(cacheAction, /~\/\.m2|target\//u);
		const browser = parseDocument(await readFile(".github/workflows/ci-browser-tests.yml", "utf8"));
		const steps = browser.getIn(["jobs", "webapp-e2e", "steps"]);
		assert.ok(isSeq(steps));
		const actions = steps.items.filter(isMap).map((step) => String(step.get("uses")));
		assert.ok(actions.some((action) => action.startsWith("actions/setup-java@")));
		assert.ok(!actions.includes("./.github/actions/setup-caches"));
	});

	await test("browser consumers use the pinned image without runtime package installation", () => {
		assert.match(browserAction, /node scripts\/ci-browser\.ts pull/u);
		assert.doesNotMatch(browserAction, /actions\/cache|playwright install|install-deps|apt-get/u);
	});
});
