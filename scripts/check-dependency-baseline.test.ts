import assert from "node:assert/strict";
import { test } from "node:test";

import { compare, countLockfile, render } from "./check-dependency-baseline.ts";

const LOCKFILE = `---
lockfileVersion: '9.0'

importers:

  .:
    packageManagerDependencies:
      pnpm:
        specifier: 12.3.4
        version: 12.3.4

packages:

  pnpm@12.3.4:
    resolution: {integrity: sha512-x}

snapshots:

  pnpm@12.3.4: {}

---
lockfileVersion: '9.0'

importers:

  .:
    devDependencies:
      vite:
        specifier: npm:@voidzero-dev/vite-plus-core@1.3.0
        version: '@voidzero-dev/vite-plus-core@1.3.0'

  app:
    dependencies:
      react-dom:
        specifier: 19.0.0
        version: 19.0.0(react@19.0.0)
      shared:
        specifier: workspace:*
        version: link:../shared
    optionalDependencies:
      fsevents:
        specifier: 2.3.3
        version: 2.3.3
    devDependencies:
      tool:
        specifier: 1.0.0
        version: 1.0.0

  shared:
    dependencies:
      left-pad:
        specifier: 1.3.0
        version: 1.3.0
    devDependencies:
      tool:
        specifier: 1.0.0
        version: 1.0.0

packages:

  '@voidzero-dev/vite-plus-core@1.3.0':
    resolution: {integrity: sha512-a}
  fsevents@2.3.3:
    resolution: {integrity: sha512-b}
  left-pad@1.3.0:
    resolution: {integrity: sha512-g}
  react@19.0.0:
    resolution: {integrity: sha512-c}
  react-dom@19.0.0:
    resolution: {integrity: sha512-d}
  scheduler@0.25.0:
    resolution: {integrity: sha512-e}
  tool@1.0.0:
    resolution: {integrity: sha512-f}

snapshots:

  '@voidzero-dev/vite-plus-core@1.3.0': {}

  fsevents@2.3.3:
    optional: true

  left-pad@1.3.0: {}

  react@19.0.0: {}

  react-dom@19.0.0(react@19.0.0):
    dependencies:
      react: 19.0.0
      scheduler: 0.25.0

  scheduler@0.25.0: {}

  tool@1.0.0:
    dependencies:
      react-dom: 19.0.0(react@19.0.0)
`;

await test("counts direct and reachable packages per importer from the project resolution", () => {
	assert.deepEqual(countLockfile(LOCKFILE, "fixture"), {
		// The env document's pnpm counts on its own, so a new config dependency is visible.
		envPackages: 1,
		packages: 7,
		importers: {
			".": { direct: { prod: 0, dev: 1 }, transitive: { prod: 0, all: 1 } },
			// The link to `shared` reaches its production dependency, not its development one.
			app: { direct: { prod: 3, dev: 1 }, transitive: { prod: 5, all: 6 } },
			shared: { direct: { prod: 1, dev: 1 }, transitive: { prod: 1, all: 5 } },
		},
	});
});

await test("a lockfile with one document has no env packages", () => {
	const single = LOCKFILE.slice(LOCKFILE.indexOf("---", 3) + 4);
	assert.equal(countLockfile(single, "fixture").envPackages, 0);
	assert.equal(countLockfile(single, "fixture").packages, 7);
});

await test("a reference without a snapshot or a link without an importer fails rather than undercounting", () => {
	const broken = LOCKFILE.replace("  scheduler@0.25.0: {}\n", "");
	assert.throws(() => countLockfile(broken, "fixture"), /scheduler@0\.25\.0 is referenced/u);
	const unlinked = LOCKFILE.replace("version: link:../shared", "version: link:../missing");
	assert.throws(
		() => countLockfile(unlinked, "fixture"),
		/app links missing, which is not an importer/u,
	);
});

await test("a count above the baseline is a rise, below it a fall, and equal is neither", () => {
	const measured = { "a.yaml": countLockfile(LOCKFILE, "fixture") };
	assert.deepEqual(compare(JSON.parse(render(measured)), measured), { rises: [], falls: [] });

	const lower = structuredClone(measured);
	const { app: lowerApp } = lower["a.yaml"].importers;
	assert.ok(lowerApp);
	lowerApp.transitive.all = 3;
	assert.deepEqual(compare(lower, measured), {
		rises: ["a.yaml › importers › app › transitive › all: 3 → 6 (+3)"],
		falls: [],
	});

	const higher = structuredClone(measured);
	const { app: higherApp } = higher["a.yaml"].importers;
	assert.ok(higherApp);
	higherApp.transitive.all = 9;
	higher["a.yaml"].packages = 10;
	assert.deepEqual(compare(higher, measured), {
		rises: [],
		falls: [
			"a.yaml › importers › app › transitive › all: 9 → 6 (-3)",
			"a.yaml › packages: 10 → 7 (-3)",
		],
	});
});

await test("a lockfile the baseline does not name rises; one the lockfiles no longer have falls", () => {
	assert.deepEqual(compare({}, { "a.yaml": { envPackages: 0, packages: 1, importers: {} } }), {
		rises: ["a.yaml › envPackages: undefined → 0", "a.yaml › packages: undefined → 1"],
		falls: [],
	});
	assert.deepEqual(compare({ "a.yaml": { envPackages: 0, packages: 1, importers: {} } }, {}), {
		rises: [],
		falls: ["a.yaml › envPackages: 0 → undefined", "a.yaml › packages: 1 → undefined"],
	});
});
