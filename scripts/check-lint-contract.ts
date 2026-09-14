/**
 * Exercise the configured rules through the pinned Vite+ with known-good and known-bad fixtures.
 * The scratch project stays outside the repo so it cannot invalidate webapp task fingerprints.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
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
	code: string | null;
	source: string;
}

const story = (name: string) => `src/components/ui/LintContract-${name}.stories.tsx`;
const preferPath = story("prefer-title");
const fixtures: Fixture[] = [
	{
		path: "src/lint-contract-inline.tsx",
		code: "shadcn(no-inline-styles)",
		source: "export const bad = <div style={{ padding: 13 }} />;",
	},
	{
		path: "src/components/ui/lint-contract-inline.tsx",
		code: "shadcn(no-inline-styles)",
		source: 'export const bad = <div style={{ color: "var(--color-primary)" }} />;',
	},
	{
		path: "src/lint-contract-style-element.tsx",
		code: "shadcn(no-inline-styles)",
		source: 'export const bad = <style>{".escape { padding: 13px }"}</style>;',
	},
	{
		path: "src/lint-contract-spacing.tsx",
		code: "shadcn(no-arbitrary-values)",
		source: 'export const bad = <div className="md:p-[13px]" />;',
	},
	{
		path: "src/components/ui/lint-contract-spacing.tsx",
		code: "shadcn(no-arbitrary-values)",
		source:
			'import { cva } from "class-variance-authority"; export const bad = cva("flex", { variants: { space: { bad: "gap-[7px]" } } });',
	},
	{
		path: "src/lib/lint-contract-palette.ts",
		code: "shadcn(no-raw-colors)",
		source: 'export const states = { merged: { className: "bg-violet-600" } };',
	},
	{
		path: "src/lib/lint-contract-theme-map.ts",
		code: null,
		source:
			'export const states = { merged: { className: "bg-provider-done text-provider-done-foreground" } };',
	},
	{
		path: "src/lint-contract-layout.tsx",
		code: null,
		source:
			'export const good = <div className="p-0.75 gap-2 w-[calc(100vw-2rem)] [&>svg]:size-4" />;',
	},
	{
		path: "src/lint-contract-custom-property.tsx",
		code: null,
		source: `import type { CSSProperties } from "react"; export function Good({ width, style }: { width: number; style?: CSSProperties }) { const panelStyle = { ...style, "--panel-width": \`\${width}px\` } satisfies CSSProperties & Record<"--panel-width", string>; return <div className="w-(--panel-width)" style={panelStyle} />; }`,
	},
	{
		path: "src/lint-contract-unused-disable.ts",
		code: "Unused oxlint-disable directive (no problems were reported).",
		source: "// oxlint-disable-next-line no-debugger\nexport const value = true;",
	},
	{
		path: "src/lint-contract-tailwind.tsx",
		code: "shadcn(no-unknown-classes)",
		source: 'export const bad = <div className="hovr:flex" />;',
	},
	{
		path: "src/components/ui/lint-contract-tailwind.tsx",
		code: "shadcn(no-unknown-classes)",
		source: 'export const bad = <div className="rounded-huge" />;',
	},
	{
		path: "src/lint-contract-color.tsx",
		code: "shadcn(no-raw-colors)",
		source: 'export const bad = <div className="bg-primry" />;',
	},
	{
		path: "src/components/ui/lint-contract-color.tsx",
		code: "shadcn(no-raw-colors)",
		source: 'export const bad = <div className="dark:bg-pink-500/50" />;',
	},
	{
		path: "src/lint-contract-svg-color.tsx",
		code: "shadcn(no-raw-colors)",
		source: 'export const bad = <svg aria-hidden="true"><path fill="#ec4899" /></svg>;',
	},
	{
		path: "src/lint-contract-cva.tsx",
		code: "shadcn(no-raw-colors)",
		source:
			'import { cva } from "class-variance-authority"; export const variants = cva("flex", { variants: { tone: { bad: "text-pink-500" } } });',
	},
	{
		path: "src/lint-contract-theme.tsx",
		code: null,
		// Loads the actual theme and its imports, typography plugin and native variants.
		source:
			'import { cn } from "cn"; export const good = <div className={cn("prose text-primary bg-background text-provider-open-foreground pointer-coarse:w-10", "dark:hover:bg-success/10")} />;',
	},
	{
		path: "src/components/ui/lint-contract-theme.tsx",
		code: null,
		source:
			'export const good = <div className="text-muted-foreground bg-warning/10 data-[state=open]:flex" />;',
	},
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

function effectiveLintOptions(scope: string) {
	const result = spawnSync("vp", ["-C", join(REPO_ROOT, scope), "lint", "--print-config"], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	return asRecord(JSON.parse(result.stdout), `${scope} effective lint config`).options;
}

function writeScratchProject(project: string) {
	const lint = asRecord(
		parse(readFileSync(join(WEBAPP, ".oxlintrc.json"), "utf8")),
		"webapp/.oxlintrc.json",
	);
	// Test the options Vite+ actually uses, not a reconstruction of the root policy.
	lint.options = effectiveLintOptions("webapp");
	// Preserve every configured plugin; replacing this list would silently drop third-party checks.
	assert.ok(Array.isArray(lint.jsPlugins), "jsPlugins must list the webapp plugins");
	const require = createRequire(join(WEBAPP, "package.json"));
	lint.jsPlugins = lint.jsPlugins.map((plugin: unknown) => {
		assert.equal(typeof plugin, "string");
		return require.resolve(String(plugin));
	});
	const components = asRecord(
		JSON.parse(readFileSync(join(WEBAPP, "components.json"), "utf8")),
		"components.json",
	);
	const tailwind = asRecord(components.tailwind, "components.tailwind");
	components.tailwind = { ...tailwind, css: join(WEBAPP, String(tailwind.css)) };
	writeFileSync(join(project, "components.json"), JSON.stringify(components));
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
	// Fixture imports resolve as app source, while the config still resolves root-owned Vite+.
	mkdirSync(join(project, "src"), { recursive: true });
	symlinkSync(
		join(WEBAPP, "node_modules"),
		join(project, "src", "node_modules"),
		process.platform === "win32" ? "junction" : "dir",
	);
	for (const fixture of fixtures) {
		mkdirSync(join(project, dirname(fixture.path)), { recursive: true });
		writeFileSync(join(project, fixture.path), `${fixture.source}\n`);
	}
}

void test("vp lint preserves house rules, design-system checks and type-aware diagnostics", () => {
	const project = mkdtempSync(join(tmpdir(), "lint-contract-"));
	try {
		writeScratchProject(project);
		const result = spawnSync(
			"vp",
			["-C", project, "lint", "--format", "json", ...fixtures.map((fixture) => fixture.path)],
			{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
		);
		assert.equal(result.error, undefined, `vp could not be spawned: ${String(result.error)}`);
		const output = `${result.stdout}${result.stderr}`;
		assert.doesNotMatch(result.stderr, /\[@shadcn\/lint\]/i, output);
		assert.equal(result.status, 1, output);
		const report = asRecord(JSON.parse(result.stdout), "vp lint --format json");
		assert.ok(Array.isArray(report.diagnostics), output);
		const reported = new Set(
			report.diagnostics
				.filter(isRecord)
				.map(
					(diagnostic) =>
						`${String(diagnostic.filename).replaceAll("\\", "/")} ${String(diagnostic.code ?? diagnostic.message)} ${String(diagnostic.severity)}`,
				),
		);
		for (const fixture of fixtures) {
			if (fixture.code === null) {
				assert.ok(
					![...reported].some((entry) => entry.startsWith(`${fixture.path} `)),
					`Valid theme usage was rejected: ${fixture.path}\n${output}`,
				);
				continue;
			}
			assert.ok(
				reported.has(`${fixture.path} ${fixture.code} error`),
				`${fixture.code} did not fire:\n${output}`,
			);
		}
	} finally {
		rmSync(project, { recursive: true, force: true });
	}
});

void test("webapp and docs use the same global lint policy", () => {
	assert.deepEqual(effectiveLintOptions("docs"), effectiveLintOptions("webapp"));
});
