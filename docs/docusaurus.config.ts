import type * as Preset from "@docusaurus/preset-classic";
import type { Config, LoadContext, Plugin } from "@docusaurus/types";
import { themes as prismThemes } from "prism-react-renderer";

const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";

const envBaseUrl = process.env.DOCUSAURUS_BASE_URL;

/*
 * Production (docs.hephaestus.build) and PR previews on Surge.sh both serve from the domain root.
 * DOCUSAURUS_BASE_URL stays overridable for builds hosted under a path prefix; unset and empty both
 * mean the root, because an empty base URL would resolve every asset against the current page.
 */
const baseUrl = envBaseUrl === undefined || envBaseUrl === "" ? "/" : envBaseUrl;

const DESCRIPTION =
	"Hephaestus is an open-source AI mentor for software teams. It checks the issues, pull and merge requests, and reviews that your team already writes against the engineering practices that your workspace adopts.";

function rawMermaidSourcePlugin(_context: LoadContext): Plugin {
	return {
		name: "raw-mermaid-source",
		configureWebpack() {
			return {
				module: {
					rules: [{ test: /\.mmd$/iu, type: "asset/source" }],
				},
			};
		},
	};
}

interface MarkdownNode {
	type: string;
	value?: string;
	children?: MarkdownNode[];
	position?: { start: { line: number } };
}

/*
 * MDX 3 reads an admonition title only in brackets (`:::caution[Title]`). The MDX 1 form
 * (`:::caution Title`) does not fail: it renders as a paragraph that starts with `:::`.
 */
function rejectUnparsedAdmonitions() {
	return (tree: MarkdownNode, file: { path: string }) => {
		const visit = (node: MarkdownNode) => {
			// A line of the paragraph starts with `:::`: the first text child's start, or after a line break.
			const unparsed =
				node.type === "paragraph" &&
				node.children?.some(
					(child, index) =>
						child.type === "text" &&
						(index === 0 ? /(?:^|\n):::/u : /\n:::/u).test(child.value ?? ""),
				) === true;
			if (unparsed) {
				const line = node.position?.start.line ?? 0;
				throw new Error(
					`${file.path}: the paragraph at line ${line} has an admonition that MDX did not parse. Write the title in brackets: :::caution[Title].`,
				);
			}
			for (const child of node.children ?? []) {
				visit(child);
			}
		};
		visit(tree);
	};
}

const config: Config = {
	title: "Hephaestus documentation",
	tagline: "Learn from the work you’re already doing",
	favicon: "img/favicon.png",

	future: {
		v4: true,
		faster: {
			swcJsLoader: true,
			swcJsMinimizer: true,
			swcHtmlMinimizer: true,
			lightningCssMinimizer: true,
			rspackBundler: true,
			mdxCrossCompilerCache: true,
		},
	},

	url: "https://docs.hephaestus.build",
	baseUrl,
	organizationName: "hephaestus-build",
	projectName: "Hephaestus",

	onBrokenLinks: "throw",
	onBrokenAnchors: "throw",
	onDuplicateRoutes: "throw",
	trailingSlash: false,

	i18n: {
		defaultLocale: "en",
		locales: ["en"],
	},

	customFields: {
		productUrl: "https://hephaestus.build",
		repoUrl: REPO_URL,
	},

	markdown: {
		mermaid: true,
		hooks: {
			onBrokenMarkdownLinks: "throw",
			onBrokenMarkdownImages: "throw",
		},
	},

	themes: ["@docusaurus/theme-mermaid"],

	presets: [
		[
			"classic",
			{
				docs: false,
				blog: false,
				theme: {
					customCss: "./src/css/custom.css",
				},
				sitemap: {
					lastmod: "datetime",
					changefreq: "weekly",
					priority: 0.5,
					filename: "sitemap.xml",
				},
			} satisfies Preset.Options,
		],
	],

	plugins: [
		rawMermaidSourcePlugin,
		[
			"@docusaurus/plugin-content-docs",
			{
				id: "default",
				path: "./user",
				routeBasePath: "user",
				sidebarPath: "./sidebars.user.ts",
				editUrl: `${REPO_URL}/tree/main/docs/`,
				showLastUpdateAuthor: true,
				showLastUpdateTime: true,
				remarkPlugins: [rejectUnparsedAdmonitions],
			},
		],
		[
			"@docusaurus/plugin-content-docs",
			{
				id: "contributor-docs",
				path: "./contributor",
				routeBasePath: "contributor",
				sidebarPath: "./sidebars.contributor.ts",
				editUrl: `${REPO_URL}/tree/main/docs/`,
				showLastUpdateAuthor: true,
				showLastUpdateTime: true,
				remarkPlugins: [rejectUnparsedAdmonitions],
			},
		],
		[
			"@docusaurus/plugin-content-docs",
			{
				id: "admin-docs",
				path: "./admin",
				routeBasePath: "admin",
				sidebarPath: "./sidebars.admin.ts",
				editUrl: `${REPO_URL}/tree/main/docs/`,
				showLastUpdateAuthor: true,
				showLastUpdateTime: true,
				remarkPlugins: [rejectUnparsedAdmonitions],
			},
		],
		[
			"@easyops-cn/docusaurus-search-local",
			{
				hashed: true,
				language: ["en"],
				indexBlog: false,
				docsRouteBasePath: ["user", "contributor", "admin"],
				docsDir: ["user", "contributor", "admin"],
				searchContextByPaths: [
					{ label: { en: "User guide" }, path: "user" },
					{ label: { en: "Contributor guide" }, path: "contributor" },
					{ label: { en: "Admin guide" }, path: "admin" },
				],
				hideSearchBarWithNoSearchContext: false,
				useAllContextsWithNoSearchContext: true,
				highlightSearchTermsOnTargetPage: true,
				searchResultContextMaxLength: 60,
			},
		],
	],

	themeConfig: {
		image: "img/hephaestus-social-card.png",
		colorMode: {
			respectPrefersColorScheme: true,
			disableSwitch: false,
		},
		metadata: [
			{ name: "description", content: DESCRIPTION },
			{
				name: "keywords",
				content: "Hephaestus, AI mentor, code review feedback, software engineering practices, TUM",
			},
			{ name: "twitter:card", content: "summary_large_image" },
			{ name: "twitter:title", content: "Hephaestus documentation" },
			{ name: "twitter:description", content: DESCRIPTION },
		],
		navbar: {
			logo: {
				alt: "Hephaestus",
				src: "img/brand/hephaestus-lockup-light.png",
				srcDark: "img/brand/hephaestus-lockup-dark.png",
				width: 155,
				height: 32,
			},
			items: [
				{
					type: "docSidebar",
					sidebarId: "userSidebar",
					docsPluginId: "default",
					position: "left",
					label: "User guide",
				},
				{
					type: "docSidebar",
					sidebarId: "contributorSidebar",
					docsPluginId: "contributor-docs",
					position: "left",
					label: "Contributor guide",
				},
				{
					type: "docSidebar",
					sidebarId: "adminSidebar",
					docsPluginId: "admin-docs",
					position: "left",
					label: "Admin guide",
				},

				{
					href: "https://hephaestus.build",
					label: "Open Hephaestus",
					position: "right",
				},
				{
					href: REPO_URL,
					position: "right",
					className: "navbarGithubLink",
					"aria-label": "Hephaestus on GitHub",
				},
			],
		},
		footer: {
			style: "light",
			links: [
				{
					title: "Product",
					items: [
						{
							label: "User guide",
							to: "/user/overview",
						},
						{
							label: "Admin guide",
							to: "/admin/overview",
						},
						{
							label: "Release notes",
							href: `${REPO_URL}/releases`,
						},
						{
							label: "Accessibility",
							to: "/user/accessibility",
						},
						{
							label: "Open Hephaestus",
							href: "https://hephaestus.build",
						},
					],
				},
				{
					title: "Contribute",
					items: [
						{
							label: "Contributor guide",
							to: "/contributor/overview",
						},
						{
							label: "Feature requests",
							href: `${REPO_URL}/discussions/categories/ideas`,
						},
						{
							label: "Bug tracker",
							href: `${REPO_URL}/issues`,
						},
					],
				},
				{
					title: "Project",
					items: [
						{
							label: "Applied Education Technologies",
							href: "https://aet.cit.tum.de/",
						},
						{
							label: "GitHub repository",
							href: REPO_URL,
						},
						{
							label: "Imprint",
							href: "https://hephaestus.build/imprint",
						},
						{
							label: "Privacy",
							href: "https://hephaestus.build/privacy",
						},
					],
				},
			],
			copyright: `Built by <a href="https://aet.cit.tum.de/">Applied Education Technologies</a> at <a href="https://www.tum.de/en/">TUM</a>. Source on <a href="${REPO_URL}">GitHub</a>.`,
		},
		announcementBar: {
			id: "pre-1-0",
			// Docusaurus does not base-URL-prefix a raw HTML href or check it via onBrokenLinks — write it absolute.
			content: `Hephaestus is <strong>pre-1.0</strong>: only the latest release is supported, and a minor release can require action. <a href="${baseUrl}admin/compatibility-policy">Read the compatibility policy</a>.`,
			isCloseable: true,
		},
		docs: {
			sidebar: {
				hideable: true,
				autoCollapseCategories: true,
			},
		},
		tableOfContents: {
			minHeadingLevel: 2,
			maxHeadingLevel: 4,
		},
		prism: {
			theme: prismThemes.vsLight,
			darkTheme: prismThemes.vsDark,
			additionalLanguages: ["bash", "json", "yaml", "java"],
		},
	} satisfies Preset.ThemeConfig,
};

export default config;
