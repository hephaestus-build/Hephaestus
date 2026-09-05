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
	"Hephaestus is an open-source AI mentor for software teams. It reads the work developers already do against the practices their project cares about, and writes back feedback they can act on.";

function rawMermaidSourcePlugin(_context: LoadContext): Plugin {
	return {
		name: "raw-mermaid-source",
		configureWebpack() {
			return {
				module: {
					rules: [{ test: /\.mmd$/i, type: "asset/source" }],
				},
			};
		},
	};
}

const config: Config = {
	title: "Hephaestus Documentation",
	tagline: "Learn from the work you're already doing",
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
					{ label: { en: "User Guide" }, path: "user" },
					{ label: { en: "Contributor Guide" }, path: "contributor" },
					{ label: { en: "Admin Guide" }, path: "admin" },
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
			{ name: "twitter:title", content: "Hephaestus Documentation" },
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
					label: "User Guide",
				},
				{
					type: "docSidebar",
					sidebarId: "contributorSidebar",
					docsPluginId: "contributor-docs",
					position: "left",
					label: "Contributor Guide",
				},
				{
					type: "docSidebar",
					sidebarId: "adminSidebar",
					docsPluginId: "admin-docs",
					position: "left",
					label: "Admin Guide",
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
							label: "User Guide",
							to: "/user/overview",
						},
						{
							label: "Admin Guide",
							to: "/admin/overview",
						},
						{
							label: "Release Notes",
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
							label: "Contributor Guide",
							to: "/contributor/overview",
						},
						{
							label: "Feature Requests",
							href: `${REPO_URL}/discussions/categories/ideas`,
						},
						{
							label: "Bug Tracker",
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
							label: "GitHub Repository",
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
			copyright: `Built by <a href="https://github.com/ls1intum">AET Team</a> at <a href="https://www.tum.de/en/">TUM</a>. Source on <a href="${REPO_URL}">GitHub</a>.`,
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
