/**
 * `vp lint` must keep firing every house rule and the type-aware diagnostics after a Vite+ or
 * oxlint bump; a rule that stops firing fails nothing. This gate lints one known-bad fixture per
 * rule through the pinned `vite-plus` and expects each diagnostic.
 *
 * The fixtures live in a scratch project outside the repository, so no gate that fingerprints the
 * webapp tree ever sees them. `vp lint` reads its options from the `lint` field of the project's
 * `vite.config.ts`, so the webapp's rules go there, with the house-rule plugin addressed by its
 * absolute path; the `src/components/ui` layout is what the story rules derive their titles from.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { test } from "node:test";

import { parse } from "jsonc-parser";

import { asRecord, isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const REPO_ROOT = resolve(import.meta.dirname, "..");
const WEBAPP = join(REPO_ROOT, "webapp");

interface Fixture {
	path: string;
	code: string;
	source: string;
}

const story = (name: string) => `src/components/ui/LintContract-${name}.stories.tsx`;
const preferPath = story("prefer-title");
const fixtures: Fixture[] = [
	{
		path: "src/lint-contract-query.ts",
		code: "hephaestus(no-manual-query-key)",
		source: 'const opts = { queryKey: ["manual"] }; void opts;',
	},
	{
		path: "src/lint-contract-nön-ascii.ts",
		code: "hephaestus(no-non-ascii-filename)",
		source: "export const value = true;",
	},
	{
		path: "src/lint-contract-clock.tsx",
		code: "hephaestus(no-nondeterministic-render)",
		source: "const timestamp = Date.now(); void timestamp;",
	},
	{
		path: "src/lint-contract-query.test.tsx",
		code: "hephaestus(no-redundant-in-the-document)",
		source: 'test("query", () => expect(canvas.getByRole("button")).toBeInTheDocument());',
	},
	{
		path: story("a11y"),
		code: "hephaestus(no-story-a11y-override)",
		source: 'export const Bad: Story = { parameters: { a11y: { test: "off" } } };',
	},
	{
		path: story("within"),
		code: "hephaestus(no-within-canvas-element)",
		source:
			'export const Bad: Story = { play: ({ canvas, canvasElement }) => { within(canvasElement); canvas.getByRole("button"); } };',
	},
	{
		path: story("play"),
		code: "hephaestus(play-must-assert)",
		source: "export const Bad: Story = { play: ({ userEvent }) => userEvent.click(trigger) };",
	},
	{
		path: preferPath,
		code: "hephaestus(prefer-auto-story-title)",
		source: `const meta = { title: "components/ui/${basename(preferPath, ".stories.tsx")}", component: Button } satisfies Meta<typeof Button>; export default meta;`,
	},
	{
		path: "src/lint-contract-svg.tsx",
		code: "hephaestus(svg-needs-accessible-name)",
		source: 'const icon = <svg><path d="M0 0" /></svg>; void icon;',
	},
	{
		path: story("meta"),
		code: "hephaestus(typed-story-meta)",
		source: "const meta = { component: Button } satisfies Meta; export default meta;",
	},
	{
		path: "src/lint-contract-unsafe.ts",
		code: "typescript(no-unsafe-assignment)",
		source: 'const unsafe: string = JSON.parse("null"); void unsafe;',
	},
	{
		path: "src/lib/lint-contract-class-join.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { clsx } from "clsx";',
	},
	{
		path: "src/lib/lint-contract-classes.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { twMerge } from "tailwind-merge";',
	},
	{
		path: "src/stores/lint-contract-classes.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { clsx } from "clsx";',
	},
	{
		path: "src/components/ui/lint-contract-classes.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { twMerge } from "tailwind-merge";',
	},
	{
		path: "src/components/lint-contract-classes.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { clsx } from "clsx";',
	},
	{
		path: "src/lint-contract-classes.ts",
		code: "eslint(no-restricted-imports)",
		source: 'export { twMerge } from "tailwind-merge";',
	},
];

function scratchProject(): string {
	const project = mkdtempSync(join(tmpdir(), "lint-contract-"));
	const lint = asRecord(
		parse(readFileSync(join(WEBAPP, ".oxlintrc.json"), "utf8")),
		"webapp/.oxlintrc.json",
	);
	lint.jsPlugins = [join(WEBAPP, "tools", "oxlint", "index.ts")];
	writeFileSync(
		join(project, "package.json"),
		`${JSON.stringify({ name: "lint-contract", private: true, type: "module" }, null, "\t")}\n`,
	);
	writeFileSync(join(project, "pnpm-workspace.yaml"), "packages:\n  - .\n");
	// The type-aware rules see the webapp's own compiler options.
	const compilerOptions = asRecord(
		parse(readFileSync(join(WEBAPP, "tsconfig.json"), "utf8")),
		"webapp/tsconfig.json",
	).compilerOptions;
	writeFileSync(
		join(project, "tsconfig.json"),
		`${JSON.stringify({ compilerOptions: { ...asRecord(compilerOptions, "compilerOptions"), types: [] } }, null, "\t")}\n`,
	);
	writeFileSync(
		join(project, "vite.config.ts"),
		`import { defineConfig } from "vite-plus";\nexport default defineConfig({ lint: ${JSON.stringify(lint)} });\n`,
	);
	// oxlint resolves its own package through the link, so the link is to the whole tree.
	symlinkSync(
		join(REPO_ROOT, "node_modules"),
		join(project, "node_modules"),
		process.platform === "win32" ? "junction" : "dir",
	);
	for (const fixture of fixtures) {
		mkdirSync(join(project, dirname(fixture.path)), { recursive: true });
		writeFileSync(join(project, fixture.path), `${fixture.source}\n`);
	}
	return project;
}

void test("vp lint preserves house rules and type-aware diagnostics", () => {
	const project = scratchProject();
	try {
		const result = spawnSync(
			"vp",
			[
				"-C",
				project,
				"lint",
				"--type-aware",
				"--format",
				"json",
				...fixtures.map((fixture) => fixture.path),
			],
			{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
		);
		assert.equal(result.error, undefined, `vp could not be spawned: ${String(result.error)}`);
		const output = `${result.stdout}${result.stderr}`;
		assert.equal(result.status, 1, output);
		const report = asRecord(JSON.parse(result.stdout), "vp lint --format json");
		assert.ok(Array.isArray(report.diagnostics), output);
		const reported = new Set(
			report.diagnostics
				.filter(isRecord)
				.map(
					(diagnostic) =>
						`${String(diagnostic.filename).replaceAll("\\", "/")} ${String(diagnostic.code)}`,
				),
		);
		for (const fixture of fixtures)
			assert.ok(
				reported.has(`${fixture.path} ${fixture.code}`),
				`${fixture.code} did not fire:\n${output}`,
			);
	} finally {
		rmSync(project, { recursive: true, force: true });
	}
});
