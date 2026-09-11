import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";

const cacheAction = await readFile(".github/actions/setup-caches/action.yml", "utf8");
const browserAction = await readFile(".github/actions/setup-browsers/action.yml", "utf8");

function actionStep(name: string): string {
	const marker = `    - name: ${name}\n`;
	const start = cacheAction.indexOf(marker);
	assert.ok(start >= 0);
	const end = cacheAction.indexOf("\n    - name:", start + marker.length);
	return cacheAction.slice(start, end < 0 ? undefined : end);
}

await describe("CI cache policy", async () => {
	await test("only trusted default-branch runs publish Gradle caches", async () => {
		const setup = actionStep("Set up Gradle");
		assert.match(setup, /gradle\/actions\/setup-gradle@[a-f0-9]{40}/);
		assert.match(setup, /cache-read-only:.*github.ref != format/);
		assert.match(setup, /github.event.repository.default_branch/);
		assert.match(
			setup,
			/!contains\(fromJSON\('\["push","schedule","workflow_dispatch"\]'\), github.event_name\)/,
		);
		assert.match(setup, /validate-wrappers: true/);
		assert.match(setup, /cache-provider: enhanced/);
		assert.doesNotMatch(cacheAction, /~\/\.m2|target\//);
		const build = await readFile(".github/workflows/ci-build.yml", "utf8");
		const e2e = build.slice(build.indexOf("\n  webapp-e2e:"));
		assert.match(e2e, /uses: actions\/setup-java@/);
		assert.doesNotMatch(e2e, /setup-caches/);
	});

	await test("browser consumers share one cache-and-install action", () => {
		assert.match(
			browserAction,
			/key: \${{ runner\.os }}-playwright-\${{ steps\.playwright\.outputs\.version }}/,
		);
		assert.doesNotMatch(browserAction, /restore-keys:/);
		assert.match(browserAction, /playwright install chromium/);
		assert.match(browserAction, /node scripts\/install-browser-deps\.ts/);
		assert.doesNotMatch(browserAction, /--with-deps/);
	});
});
