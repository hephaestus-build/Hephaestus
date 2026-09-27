import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");

await test("component boundaries reject extension IPC, aliased queries and network stories", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "component-boundaries-"));
	const component = path.join(root, "extension/src/components/Example.tsx");
	const story = path.join(root, "extension/src/components/Example.stories.tsx");
	const run = () =>
		spawnSync(process.execPath, ["scripts/check-presentational-components.ts"], {
			cwd: root,
			encoding: "utf8",
		});
	try {
		await mkdir(path.join(root, "scripts/lib"), { recursive: true });
		for (const file of ["check-presentational-components.ts", "lib/env.ts"]) {
			await copyFile(path.join(REPO_ROOT, "scripts", file), path.join(root, "scripts", file));
		}
		for (const tree of ["webapp", "extension"]) {
			const dir = path.join(root, tree, "src/components");
			await mkdir(dir, { recursive: true });
			await writeFile(
				path.join(dir, "Example.tsx"),
				"export function Example() { return null; }\n",
			);
			await writeFile(path.join(dir, "Example.stories.tsx"), "export const Default = {};\n");
		}
		assert.equal(run().status, 0, "presentational components need no network or browser");
		for (const source of [
			'import { ask } from "~/ui/rpc-client";\n',
			'import { browser } from "@wxt-dev/browser";\n',
			'import { getOwnDeliveredWorkFeedback } from "~/api/sdk.gen";\n',
			'import { useQuery as load } from "@tanstack/react-query";\n',
		]) {
			await writeFile(component, source);
			const result = run();
			assert.equal(result.status, 1, source);
			assert.match(result.stderr, /extension\/src\/components\/Example\.tsx/u);
		}
		await writeFile(component, 'import type { WorkFeedback } from "~/shared/review-context";\n');
		assert.equal(run().status, 0, "types are props, not effects");
		await writeFile(story, 'import { http } from "msw";\n');
		const mocked = run();
		assert.equal(mocked.status, 1);
		assert.match(mocked.stderr, /mocks the network in a story/u);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
