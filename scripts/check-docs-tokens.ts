/**
 * The docs site is Infima and the web app is Tailwind, so the shared palette can only be copied.
 * This pins each copy to the app token it came from: recolour the app and the docs fail until they
 * follow, instead of drifting a shade apart where nobody looks at both at once.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";
import { isSet } from "./lib/env.ts";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const DOCS_CSS = "docs/src/css/custom.css";
const APP_CSS = "webapp/src/styles.css";

type Theme = "light" | "dark";

interface CopiedToken {
	infima: string;
	app: string;
	docsTheme: Theme;
	/** Defaults to `docsTheme`; set it where the docs deliberately borrow the other mode's value. */
	appTheme?: Theme;
}

const COPIED_TOKENS: CopiedToken[] = [
	{ infima: "--ifm-global-radius", app: "--radius", docsTheme: "light" },
	{ infima: "--ifm-color-primary", app: "--mentor", docsTheme: "light" },
	{ infima: "--ifm-color-primary", app: "--mentor", docsTheme: "dark" },
	{ infima: "--ifm-color-emphasis-100", app: "--muted", docsTheme: "light" },
	{ infima: "--ifm-color-emphasis-200", app: "--border", docsTheme: "light" },
	{ infima: "--ifm-color-emphasis-300", app: "--ring", docsTheme: "light" },
	{ infima: "--ifm-color-emphasis-700", app: "--muted-foreground", docsTheme: "light" },
	{ infima: "--ifm-color-emphasis-100", app: "--sidebar", docsTheme: "dark" },
	{ infima: "--ifm-color-emphasis-200", app: "--border", docsTheme: "dark" },
	{ infima: "--ifm-color-emphasis-400", app: "--ring", docsTheme: "dark" },
	{ infima: "--ifm-color-emphasis-700", app: "--muted-foreground", docsTheme: "dark" },
	{ infima: "--ifm-toc-border-color", app: "--border", docsTheme: "dark" },
	// White on the dark-mode mentor blue is ~2.6:1, so the dark search hit keeps the light one.
	{
		infima: "--search-local-highlight-color",
		app: "--mentor",
		docsTheme: "dark",
		appTheme: "light",
	},
];

/**
 * Both files declare their light values in `:root` and their dark values under a selector naming
 * `dark`, so the theme a declaration belongs to is the last such block opened above it. Each file
 * re-opens `:root` several times, so a later declaration **overwrites** an earlier one — matching
 * the cascade the browser applies. Keeping the first instead would let an appended re-declaration
 * change the rendered site while this gate stayed green.
 */
export function readTokens(css: string): Record<Theme, Map<string, string>> {
	const tokens: Record<Theme, Map<string, string>> = { light: new Map(), dark: new Map() };
	let theme: Theme = "light";
	for (const line of css.split("\n")) {
		if (line.includes("{") && !line.trimStart().startsWith("--")) {
			theme = line.includes("dark") ? "dark" : "light";
			continue;
		}
		const declaration = /^\s*(?<name>--[\w-]+):\s*(?<value>.+?);/u.exec(line)?.groups;
		if (declaration?.name !== undefined && declaration.value !== undefined) {
			tokens[theme].set(declaration.name, declaration.value.trim());
		}
	}
	return tokens;
}

export function findDrift(docsCss: string, appCss: string): string[] {
	const docs = readTokens(docsCss);
	const app = readTokens(appCss);
	const problems: string[] = [];
	for (const { infima, app: appToken, docsTheme, appTheme = docsTheme } of COPIED_TOKENS) {
		const docsValue = docs[docsTheme].get(infima);
		const appValue = app[appTheme].get(appToken);
		if (!isSet(docsValue)) {
			problems.push(`${DOCS_CSS} no longer declares ${infima} for the ${docsTheme} theme.`);
		} else if (!isSet(appValue)) {
			problems.push(`${APP_CSS} no longer declares ${appToken} for the ${appTheme} theme.`);
		} else if (docsValue !== appValue) {
			problems.push(
				`${docsTheme} ${infima} is ${docsValue}, but the ${appTheme} ${appToken} it copies is ${appValue}.`,
			);
		}
	}
	return problems;
}

if (import.meta.main) {
	const [docsCss, appCss] = await Promise.all([
		readFile(path.resolve(repositoryRoot, DOCS_CSS), "utf8"),
		readFile(path.resolve(repositoryRoot, APP_CSS), "utf8"),
	]);
	const problems = findDrift(docsCss, appCss);
	if (problems.length > 0) {
		process.stderr.write(`${problems.join("\n")}\n`);
		process.exit(1);
	}
	process.stdout.write(`${COPIED_TOKENS.length} docs tokens match their web app source.\n`);
}
