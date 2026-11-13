/**
 * Renders every system diagram source `docs/diagrams/<name>.hyl` with the pinned HyLiMo CLI to
 * `docs/static/img/diagrams/<name>.light.svg` and `<name>.dark.svg`.
 *
 * Each source holds the line `// @shared-style`. This script replaces it with `docs/diagrams/style.hyl`,
 * with the fills of the color scheme, so every diagram has the same fonts, colors and legend classes.
 * HyLiMo has no include, and the fills differ between the schemes.
 *
 * Usage: `node scripts/render-diagrams.ts [--check]`. `--check` renders into a temporary directory and
 * fails when a committed SVG differs from its source, or when an SVG has no source.
 */

import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { roboto } from "@hylimo/fonts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const SOURCES = path.join(REPO_ROOT, "docs/diagrams");
const OUTPUT = path.join(REPO_ROOT, "docs/static/img/diagrams");
/** HyLiMo draws its text in Roboto. The site loads the same faces for Mermaid, so every diagram has one font. */
const FONTS = path.join(REPO_ROOT, "docs/static/fonts");
const face = (dataUrl: string) => Buffer.from(dataUrl.slice(dataUrl.indexOf(",") + 1), "base64");
/** Each face, and the SIL Open Font License text that must travel with it. */
const FONT_FILES = {
	"roboto-regular.ttf": face(roboto.regular),
	"roboto-bold.ttf": face(roboto.bold),
	"roboto-OFL.txt": Buffer.from(`${roboto.license.trim()}\n`),
};
const STYLE_MARKER = "// @shared-style";
// Resolving runs nothing: the CLI starts only in the child process below.
const CLI = fileURLToPath(import.meta.resolve("@hylimo/cli"));

/** The background of each scheme is the docs page color (`docs/src/css/custom.css`), so the SVG sits on the page. */
const SCHEMES = {
	light: {
		dark: false,
		primary: "#1c1e21",
		background: "#ffffff",
		ours: "#ffffff",
		ext: "#f1f5f9",
		admin: "#fef3c7",
	},
	dark: {
		dark: true,
		primary: "#e3e3e3",
		background: "#09090b",
		ours: "#09090b",
		ext: "#2a2d33",
		admin: "#3b2f0b",
	},
} as const;

type Scheme = keyof typeof SCHEMES;
const SCHEME_NAMES = ["light", "dark"] as const satisfies readonly Scheme[];

const styleSource = await readFile(path.join(SOURCES, "style.hyl"), "utf8");

/** The shared style with the fills of one scheme. */
function styleFor(scheme: Scheme): string {
	const colors = SCHEMES[scheme];
	return styleSource
		.replaceAll("$ours", colors.ours)
		.replaceAll("$ext", colors.ext)
		.replaceAll("$admin", colors.admin);
}

async function render(
	source: string,
	scheme: Scheme,
	outFile: string,
	work: string,
): Promise<void> {
	const colors = SCHEMES[scheme];
	const text = await readFile(path.join(SOURCES, source), "utf8");
	if (!text.includes(STYLE_MARKER)) {
		throw new Error(`${source} has no ${STYLE_MARKER} line`);
	}
	const input = path.join(work, `${path.basename(source, ".hyl")}.${scheme}.hyl`);
	await writeFile(
		input,
		text.replace(STYLE_MARKER, () => styleFor(scheme)),
	);
	// Embedded fonts get a random family name on every render, so the drift check could never pass.
	// Text as paths is byte-stable, and a page shows the SVG as an image with its alt text anyway.
	const args = [
		CLI,
		"-f",
		input,
		"-o",
		outFile,
		"--text-as-path",
		"--primary",
		colors.primary,
		"--background",
		colors.background,
		...(colors.dark ? ["--dark"] : []),
	];
	// The CLI prints parse and layout errors and still exits 0 when it drew something.
	const result = spawnSync(process.execPath, args, {
		encoding: "utf8",
		maxBuffer: 16 * 1024 * 1024,
	});
	const output = `${result.stdout}${result.stderr}`.trim();
	if (result.status !== 0 || output.length > 0) {
		throw new Error(`${source} (${scheme}):\n${output}`);
	}
	await writeFile(outFile, withCanvas(await readFile(outFile, "utf8"), colors.background));
}

/**
 * The CLI draws on a transparent canvas, and `--background` only sets the default box fill. The text
 * contrast of an SVG that a reader opens on its own then depends on the viewer, so each SVG gets the
 * page background of its scheme.
 */
function withCanvas(svg: string, background: string): string {
	const open = /<svg [^>]*viewBox="(?<box>[-\d. ]+)"[^>]*>/u.exec(svg);
	const box = open?.groups?.box?.split(" ") ?? [];
	if (open === null || box.length !== 4) {
		throw new Error("the CLI wrote an SVG without a viewBox");
	}
	const attributes = ["x", "y", "width", "height"].map((name, i) => `${name}="${box[i] ?? ""}"`);
	const canvas = `<rect ${attributes.join(" ")} fill="${background}"/>`;
	return svg.replace(open[0], () => `${open[0]}\n  ${canvas}`);
}

/** Renders one source in one scheme. In a check, returns the SVG name when the committed file differs. */
async function renderOne(
	source: string,
	scheme: Scheme,
	check: boolean,
	work: string,
): Promise<string | null> {
	const name = `${path.basename(source, ".hyl")}.${scheme}.svg`;
	const target = path.join(check ? work : OUTPUT, name);
	await render(source, scheme, target, work);
	if (!check) {
		return null;
	}
	const committed = await readFile(path.join(OUTPUT, name), "utf8").catch(() => null);
	const rendered = await readFile(target, "utf8");
	return committed === rendered ? null : name;
}

const check = process.argv.includes("--check");
const files = await readdir(SOURCES);
const sources = files.filter((file) => file.endsWith(".hyl") && file !== "style.hyl").toSorted();
await mkdir(OUTPUT, { recursive: true });
const work = await mkdtemp(path.join(tmpdir(), "hephaestus-diagrams-"));
const stale: string[] = [];
try {
	for (const source of sources) {
		for (const scheme of SCHEME_NAMES) {
			const name = await renderOne(source, scheme, check, work);
			if (name !== null) {
				stale.push(name);
			}
		}
	}
} finally {
	await rm(work, { recursive: true, force: true });
}
await mkdir(FONTS, { recursive: true });
for (const [name, content] of Object.entries(FONT_FILES)) {
	const target = path.join(FONTS, name);
	if (!check) {
		await writeFile(target, content);
	} else if (!content.equals(await readFile(target).catch(() => Buffer.alloc(0)))) {
		stale.push(`fonts/${name}`);
	}
}
const expected = new Set(
	sources.flatMap((source) =>
		SCHEME_NAMES.map((scheme) => `${path.basename(source, ".hyl")}.${scheme}.svg`),
	),
);
const outputs = await readdir(OUTPUT);
const orphans = outputs.filter((file) => !expected.has(file));
if (check) {
	stale.push(...orphans.map((file) => `${file} (no source)`));
} else {
	await Promise.all(
		orphans.map(async (file) => {
			await rm(path.join(OUTPUT, file));
		}),
	);
}

if (stale.length > 0) {
	console.error(
		`render-diagrams: these files do not match their sources. Run \`vp run generate:diagrams\`:\n- ${stale.join("\n- ")}`,
	);
	process.exit(1);
}
console.log(
	`render-diagrams: ${sources.length} diagram(s) ${check ? "match their sources" : "rendered"}.`,
);
