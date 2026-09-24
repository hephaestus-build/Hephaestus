/**
 * Exercises house rules, design-system policy and type checking through the pinned Vite+,
 * using invalid fixtures and valid theme usage.
 * The scratch project stays outside the repo so it cannot invalidate webapp task fingerprints.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { parse } from "jsonc-parser";

import { loadLintConfig } from "../webapp/tools/oxlint/load-config.ts";
import { isSet } from "./lib/env.ts";
import { asRecord, isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const WEBAPP = path.join(REPO_ROOT, "webapp");

interface Fixture {
	path: string;
	code: string | null;
	source: string;
}

const story = (name: string) => `src/components/ui/LintContract-${name}.stories.tsx`;
const preferPath = story("prefer-title");
const fixtures: Fixture[] = [
	{
		path: "src/lint-contract-button-padding.tsx",
		code: "shadcn(no-restyle)",
		source:
			'import { Button as Action } from "@/components/ui/button";\n\nexport const bad = <Action className="md:px-4!">Save</Action>;',
	},
	{
		path: "src/lint-contract-button-gap.tsx",
		code: "shadcn(no-restyle)",
		source:
			'import { Button } from "@/components/ui/button"; import { cn } from "cn";\n\nexport const bad = <Button className={cn("gap-4")}>Save</Button>;',
	},
	{
		path: "src/lint-contract-button-size.tsx",
		code: null,
		source:
			'import { Button } from "@/components/ui/button";\n\nexport const good = <Button size="sm" className="w-full mt-2">Save</Button>;',
	},
	{
		path: "src/components/ui/lint-contract-owned-padding.tsx",
		code: null,
		source:
			'import { Button } from "@/components/ui/button";\n\nexport const good = <Button className="gap-2">Calendar day</Button>;',
	},
	{
		path: "src/lint-contract-button-radius.tsx",
		code: "shadcn(no-restyle)",
		source:
			'import { Button } from "@/components/ui/button";\n\nexport const bad = <Button className="rounded-full">Send</Button>;',
	},
	{
		path: "src/lint-contract-button-shape.tsx",
		code: null,
		source:
			'import { Button } from "@/components/ui/button";\n\nexport const good = <Button shape="pill" className="rounded-bl-lg">Send</Button>;',
	},
	// Contracts are read: Card's grants no colour, CardContent's grants spacing.
	{
		path: "src/lint-contract-card-paint.tsx",
		code: "shadcn(no-restyle)",
		source:
			'import { Card } from "@/components/ui/card";\n\nexport const bad = <Card className="bg-muted rounded-none">Plan</Card>;',
	},
	{
		path: "src/lint-contract-card-variant.tsx",
		code: null,
		source:
			'import { Card, CardContent } from "@/components/ui/card";\n\nexport const good = <Card variant="dashed" flush className="mt-4"><CardContent className="p-0">Plan</CardContent></Card>;',
	},
	{
		path: "src/lint-contract-ring.tsx",
		code: "shadcn(no-arbitrary-values)",
		source:
			'export const bad = <button type="button" className="focus-visible:ring-[3px]">Save</button>;',
	},
	// Off-theme colour and size are the two arbitrary values a deny list would be likeliest to forget.
	{
		path: "src/lint-contract-hex.tsx",
		code: "shadcn(no-arbitrary-values)",
		source: 'export const bad = <div className="bg-[#111318]" />;',
	},
	{
		path: "src/lint-contract-font-size.tsx",
		code: "shadcn(no-arbitrary-values)",
		source: 'export const bad = <span className="text-[11px]" />;',
	},
	{
		path: "src/lint-contract-scale-steps.tsx",
		code: null,
		// `text-2xs`, `tracking-display` and `ease-drawer` exist only in `styles.css`; the fixture fails
		// if the plugin lints against Tailwind's defaults alone.
		source:
			'export const good = <span className="text-2xs rounded-xs tracking-display ease-drawer" />;',
	},
	{
		path: "src/components/ui/lint-contract-shadow.tsx",
		code: "shadcn(no-arbitrary-values)",
		source:
			'export const bad = <button type="button" className="shadow-[0_0_0_1px_hsl(var(--sidebar-border))]">Save</button>;',
	},
	{
		path: "src/lint-contract-ring-scale.tsx",
		code: null,
		source:
			'export const good = <button type="button" className="ring-1 ring-sidebar-border focus-visible:ring-3 shadow-sm">Save</button>;',
	},
	{
		path: "src/lint-contract-inline.tsx",
		code: "shadcn(no-inline-styles)",
		source: "export const bad = <div style={{ padding: 13 }} />;",
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
			'import { cva } from "class-variance-authority";\n\nexport const bad = cva("flex", { variants: { space: { bad: "gap-[7px]" } } });',
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
		source: `import type { CSSProperties } from "react";\n\nexport function Good({ width, style }: { width: number; style?: CSSProperties }) { const panelStyle = { ...style, "--panel-width": \`\${width}px\` } satisfies CSSProperties & Record<"--panel-width", string>; return <div className="w-(--panel-width)" style={panelStyle} />; }`,
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
		path: "src/lint-contract-color.tsx",
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
			'import { cva } from "class-variance-authority";\n\nexport const variants = cva("flex", { variants: { tone: { bad: "text-pink-500" } } });',
	},
	{
		path: "src/lint-contract-theme.tsx",
		code: null,
		// Loads the actual theme and its imports, typography plugin and native variants.
		source:
			'import { cn } from "cn";\n\nexport const good = <div className={cn("prose text-primary bg-background text-provider-open-foreground pointer-coarse:w-10", "dark:hover:bg-success/10")} />;',
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
		// A story, where the matcher is registered and the query already proves presence.
		path: story("in-the-document"),
		code: "hephaestus(no-redundant-in-the-document)",
		source:
			'export const Bad: Story = { play: async ({ canvas }) => { await expect(canvas.getByRole("button")).toBeInTheDocument(); } };',
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
		source: `const meta = { title: "Kit/Button", component: Button } satisfies Meta<typeof Button>; export default meta;`,
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
		path: "src/lint-contract-boolean.ts",
		code: "typescript(strict-boolean-expressions)",
		source:
			'export function label(value?: string) { if (value) { return value; } return "empty"; }',
	},
	{
		path: "src/lint-contract-exhaustive.ts",
		code: "typescript(switch-exhaustiveness-check)",
		source:
			'export function label(value: "ready" | "failed") { switch (value) { case "ready": return "Ready"; default: return "Unknown"; } }',
	},
	{
		path: "src/lint-contract-type-error.ts",
		code: "typescript(TS2322)",
		source: 'export const count: number = "not a number";',
	},
	// `no-restricted-imports` options replace rather than merge, so every override that sets the rule
	// restates the `react` entry; one fixture per override scope proves none has dropped it.
	...["src/lib", "src/stores", "src/components/ui", "src/components", "src"].map((scope) => ({
		path: `${scope}/lint-contract-memo.ts`,
		code: "eslint(no-restricted-imports)",
		source: 'export { useMemo } from "react";',
	})),
];

function effectiveLintOptions(scope: string) {
	const result = spawnSync("vp", ["-C", path.join(REPO_ROOT, scope), "lint", "--print-config"], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	return asRecord(JSON.parse(result.stdout), `${scope} effective lint config`).options;
}

function writeScratchProject(project: string) {
	// Inlined the way the webapp's own Vite config inlines it, so the scratch project exercises the
	// same object Vite+ hands oxlint, under the options Vite+ actually resolves for that tree.
	const lint: Record<string, unknown> = {
		...loadLintConfig(pathToFileURL(path.join(WEBAPP, ".oxlintrc.json"))),
		options: effectiveLintOptions("webapp"),
	};
	// Resolved from the webapp, since the scratch project cannot reach `./tools` or the webapp's
	// dependencies by name; the list stays the config's so a plugin added there is exercised here.
	assert.ok(Array.isArray(lint.jsPlugins), "jsPlugins must list the webapp plugins");
	const require = createRequire(path.join(WEBAPP, "package.json"));
	lint.jsPlugins = lint.jsPlugins.map((plugin: unknown) => {
		assert.equal(typeof plugin, "string");
		return require.resolve(String(plugin));
	});
	const components = asRecord(
		JSON.parse(readFileSync(path.join(WEBAPP, "components.json"), "utf8")),
		"components.json",
	);
	const tailwind = asRecord(components.tailwind, "components.tailwind");
	components.tailwind = { ...tailwind, css: path.join(WEBAPP, String(tailwind.css)) };
	writeFileSync(path.join(project, "components.json"), JSON.stringify(components));
	writeFileSync(
		path.join(project, "package.json"),
		`${JSON.stringify({ name: "lint-contract", private: true, type: "module" }, null, "\t")}\n`,
	);
	writeFileSync(path.join(project, "pnpm-workspace.yaml"), "packages:\n  - .\n");
	// The type-aware rules see the webapp's own compiler options.
	const { compilerOptions } = asRecord(
		parse(readFileSync(path.join(WEBAPP, "tsconfig.json"), "utf8")),
		"webapp/tsconfig.json",
	);
	writeFileSync(
		path.join(project, "tsconfig.json"),
		`${JSON.stringify({ compilerOptions: { ...asRecord(compilerOptions, "compilerOptions"), types: [] } }, null, "\t")}\n`,
	);
	writeFileSync(
		path.join(project, "vite.config.ts"),
		`import { defineConfig } from "vite-plus";\nexport default defineConfig({ lint: ${JSON.stringify(lint)} });\n`,
	);
	// oxlint resolves its own package through the link, so the link is to the whole tree.
	symlinkSync(
		path.join(REPO_ROOT, "node_modules"),
		path.join(project, "node_modules"),
		process.platform === "win32" ? "junction" : "dir",
	);
	// Fixture imports resolve as app source, while the config still resolves root-owned Vite+.
	mkdirSync(path.join(project, "src"), { recursive: true });
	symlinkSync(
		path.join(WEBAPP, "node_modules"),
		path.join(project, "src", "node_modules"),
		process.platform === "win32" ? "junction" : "dir",
	);
	// The rules read a component's variants from its source, so the fixtures see the real files.
	mkdirSync(path.join(project, "src/components/ui"), { recursive: true });
	for (const primitive of ["button.tsx", "card.tsx"]) {
		writeFileSync(
			path.join(project, "src/components/ui", primitive),
			readFileSync(path.join(WEBAPP, "src/components/ui", primitive)),
		);
	}
	for (const fixture of fixtures) {
		mkdirSync(path.join(project, path.dirname(fixture.path)), { recursive: true });
		writeFileSync(path.join(project, fixture.path), `${fixture.source}\n`);
	}
}

void test("vp lint preserves house rules, design-system checks and type-aware diagnostics", () => {
	const project = mkdtempSync(path.join(tmpdir(), "lint-contract-"));
	try {
		writeScratchProject(project);
		const result = spawnSync(
			"vp",
			["-C", project, "lint", "--format", "json", ...fixtures.map((fixture) => fixture.path)],
			{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
		);
		assert.equal(result.error, undefined, `vp could not be spawned: ${String(result.error)}`);
		const output = `${result.stdout}${result.stderr}`;
		assert.doesNotMatch(result.stderr, /\[@shadcn\/lint\]/iu, output);
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

void test("webapp and docs use the same lint engine options", () => {
	assert.deepEqual(effectiveLintOptions("docs"), effectiveLintOptions("webapp"));
});

/**
 * A `no-restyle` contract names registry components by regex. A name nothing exports matches nothing
 * and reports nothing, so a renamed or re-vendored primitive would leave its contract behind as dead
 * policy that still looks deliberate.
 */
void test("every no-restyle contract names a component the registry exports", () => {
	const config = asRecord(
		parse(readFileSync(path.join(WEBAPP, ".oxlintrc.json"), "utf8")),
		"webapp lint",
	);
	const rule = asRecord(config.rules, "webapp lint rules")["shadcn/no-restyle"];
	assert.ok(Array.isArray(rule), "no-restyle is configured with options");
	const { contracts } = asRecord(rule[1], "no-restyle options");
	assert.ok(Array.isArray(contracts) && contracts.length > 0, "no-restyle declares contracts");

	const ui = path.join(WEBAPP, "src/components/ui");
	const exported = new Set<string>();
	for (const file of readdirSync(ui)) {
		if (!file.endsWith(".tsx") || file.includes(".stories.")) {
			continue;
		}
		const source = readFileSync(path.join(ui, file), "utf8");
		for (const { groups } of source.matchAll(/export \{(?<names>[^}]*)\}/gu)) {
			for (const entry of (groups?.names ?? "").split(",")) {
				const exportedName = entry
					.trim()
					.split(/\s+as\s+/u)
					.at(-1);
				if (isSet(exportedName)) {
					exported.add(exportedName);
				}
			}
		}
		for (const { groups } of source.matchAll(/export (?:function|const) (?<name>\w+)/gu)) {
			if (isSet(groups?.name)) {
				exported.add(groups.name);
			}
		}
	}

	for (const contract of contracts) {
		const pattern = String(asRecord(contract, "no-restyle contract").pattern);
		for (const name of pattern.replaceAll(/^\^\(?|\)?\$$/gu, "").split("|")) {
			assert.ok(/^[A-Z]\w*$/u.test(name), `contract pattern is a plain name list: ${pattern}`);
			assert.ok(
				exported.has(name),
				`no-restyle contract names ${name}, which no registry file exports`,
			);
		}
	}
});
