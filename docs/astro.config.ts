import { cp, readdir, readFile } from "node:fs/promises";
import path from "node:path";

import { satteri } from "@astrojs/markdown-satteri";
import fumadocs from "@fumadocs/astro";
import { remarkMdxMermaid } from "@fumadocs/satteri/remark-mdx-mermaid";
import tailwindcss from "@tailwindcss/vite";
import type { AstroIntegration, ViteUserConfig } from "astro";
import { defineConfig, passthroughImageService } from "astro/config";
import { loader, type VirtualFile } from "fumadocs-core/source";
import { metaSchema } from "fumadocs-core/source/schema";
import { parse } from "yaml";

import { migrationEntries } from "./src/lib/migration-entries.ts";
import { linksWithBasePath } from "./src/lib/page-tree.ts";
import { callouts, rejectUnparsedAdmonitions, restoreUnusedDirectives } from "./src/lib/plugins.ts";
import { GUIDES, REPO_URL, SITE_URL } from "./src/lib/site.ts";

/*
 * The Pages workflow builds the latest release tag at the site root with DOCS_CHANNEL=latest, and `main` at `/next/`
 * (docs/README.md § Versions). Any other build, such as a local build or a PR preview, shows `main`, so it is `next`.
 * DOCS_RELEASE names the release in the version switcher. An empty DOCS_BASE_URL means the root.
 */
const base =
	process.env.DOCS_BASE_URL === undefined || process.env.DOCS_BASE_URL === ""
		? "/"
		: process.env.DOCS_BASE_URL;
const channel = process.env.DOCS_CHANNEL === "latest" ? "latest" : "next";
const release = process.env.DOCS_RELEASE?.trim() ?? "";
const root = import.meta.dirname;
const PAGE_SOURCES = new Set([".md", ".mdx", ".mmd", ".json"]);
const PAGE_TREE = "virtual:docs-page-tree";
const MIGRATION_ENTRIES = "virtual:migration-entries";

async function filesOf(directory: string): Promise<string[]> {
	const entries = await readdir(directory, { withFileTypes: true });
	const nested = await Promise.all(
		entries
			.filter((entry) => !entry.name.startsWith("_"))
			.map(async (entry) => {
				const full = path.join(directory, entry.name);
				return entry.isDirectory() ? filesOf(full) : [full];
			}),
	);
	return nested.flat();
}

/**
 * A file that a page links to, such as `admin/slack-app-manifest-template.yml`, ships next to the page. Its URL is its
 * path, as a page's URL is.
 */
const linkedFiles = (): AstroIntegration => ({
	name: "hephaestus-linked-files",
	hooks: {
		"astro:build:done": async ({ dir }) => {
			for (const guide of GUIDES) {
				for (const file of await filesOf(path.join(root, guide.path))) {
					if (!PAGE_SOURCES.has(path.extname(file))) {
						await cp(file, path.join(dir.pathname, path.relative(root, file)));
					}
				}
			}
		},
	},
});

/**
 * The sidebar's page tree is the same on every page, so the islands import it from one module that the browser caches,
 * instead of reading it from the props of each page. It comes from the same files as the site: the front matter of
 * each page and the `meta.json` of each folder, through the same Fumadocs loader.
 */
const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null;
type VitePlugin = NonNullable<ViteUserConfig["plugins"]>[number];

const pageTree = (): VitePlugin => ({
	name: "hephaestus-page-tree",
	resolveId: (id: string) => (id === PAGE_TREE ? `\0${PAGE_TREE}` : undefined),
	async load(id: string) {
		if (id !== `\0${PAGE_TREE}`) {
			return null;
		}
		const files: VirtualFile[] = [];
		const read = async (file: string): Promise<string> => {
			this.addWatchFile(file);
			return readFile(file, "utf8");
		};
		const meta = async (relative: string): Promise<void> => {
			const data = metaSchema.parse(JSON.parse(await read(path.join(root, relative))));
			files.push({ type: "meta", path: relative, data });
		};
		await meta("meta.json");
		for (const guide of GUIDES) {
			for (const file of await filesOf(path.join(root, guide.path))) {
				const relative = path.relative(root, file).split(path.sep).join("/");
				if (file.endsWith(".mdx")) {
					const match = /^---\r?\n(?<frontMatter>[\s\S]*?)\r?\n---/u.exec(await read(file));
					const frontMatter: unknown = parse(match?.groups?.frontMatter ?? "");
					const page = isRecord(frontMatter) ? frontMatter : {};
					if (typeof page.title !== "string") {
						throw new Error(`${relative} has no title in its front matter.`);
					}
					const description = typeof page.description === "string" ? page.description : undefined;
					files.push({ type: "page", path: relative, data: { title: page.title, description } });
				} else if (path.basename(file) === "meta.json") {
					await meta(relative);
				}
			}
		}
		const baseUrl = base.endsWith("/") ? base : `${base}/`;
		const source = loader({ baseUrl, source: { files }, plugins: [linksWithBasePath(baseUrl)] });
		return `export default ${JSON.stringify(await source.serializePageTree(source.getPageTree()))};`;
	},
});

/**
 * The entries of the migration guide and the pending `.migration` notes, for the `MigrationEntries` component of an
 * upgrade page. A change writes its operator action once, in its note, and the page lists it. A note that breaks the
 * rules of `.migration/README.md` fails the build.
 */
const migrationGuide = (): VitePlugin => ({
	name: "hephaestus-migration-entries",
	resolveId: (id: string) => (id === MIGRATION_ENTRIES ? `\0${MIGRATION_ENTRIES}` : undefined),
	async load(id: string) {
		if (id !== `\0${MIGRATION_ENTRIES}`) {
			return null;
		}
		const repository = path.resolve(root, "..");
		const notes = path.join(repository, ".migration");
		const read = async (file: string): Promise<string> => {
			this.addWatchFile(file);
			return readFile(file, "utf8");
		};
		const entries = await readdir(notes);
		const names = entries.filter((name) => name.endsWith(".md") && name !== "README.md").toSorted();
		const guide = migrationEntries(
			await read(path.join(repository, "MIGRATION.md")),
			await Promise.all(
				names.map(async (name) => ({
					file: `.migration/${name}`,
					source: await read(path.join(notes, name)),
				})),
			),
			`${REPO_URL}/blob/main`,
		);
		return `export default ${JSON.stringify(guide)};`;
	},
});

export default defineConfig({
	site: SITE_URL,
	base,
	// GitHub Pages serves `admin/upgrade.html` at `/admin/upgrade`, the URL that every published link uses.
	build: { format: "file" },
	trailingSlash: "never",
	outDir: "./build",
	// The site ships its images as committed. Without a transform, the build needs no `sharp`, which pnpm leaves out.
	image: { service: passthroughImageService() },
	// Directives carry the callouts and `::include`. Quotes stay as the author typed them, as the old site showed them.
	markdown: { processor: satteri({ features: { directive: true, smartPunctuation: false } }) },
	// The user-guide screenshots, the system diagrams and the practice visual templates keep their URLs.
	publicDir: "./static",
	integrations: [
		fumadocs({
			collections: Object.fromEntries(GUIDES.map((guide) => [guide.path, { dir: guide.path }])),
			lastModified: true,
			includeProcessedMarkdown: { output: "function" },
			mdastPlugins: (defaults) => [
				rejectUnparsedAdmonitions,
				callouts,
				restoreUnusedDirectives,
				...defaults,
				remarkMdxMermaid(),
			],
			rehypeCodeOptions: {
				themes: { light: "github-light", dark: "github-dark" },
				// The lazy loader does not resolve an alias such as `md` or `sh`, so the grammars of the fences load up front.
				langs: [
					"dotenv",
					"http",
					"java",
					"javascript",
					"json",
					"markdown",
					"mdx",
					"shellscript",
					"sql",
					"typescript",
					"xml",
					"yaml",
				],
				langAlias: { svg: "xml" },
				// A fence in a language that Shiki has no grammar for, such as `promql`, renders as plain text, as it did with Prism.
				fallbackLanguage: "plaintext",
			},
		}),
		linkedFiles(),
	],
	vite: {
		plugins: [tailwindcss(), pageTree(), migrationGuide()],
		define: {
			"import.meta.env.DOCS_CHANNEL": JSON.stringify(channel),
			"import.meta.env.DOCS_RELEASE": JSON.stringify(release),
		},
	},
});
