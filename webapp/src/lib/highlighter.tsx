// ⚠️ Diverges from the shadcn registry — `shadcn add use-highlighted-code` drops the following;
// re-apply it.
//
// 1. Java is in the language list, because the reviewed work quotes it.
// 2. The themes are GitHub's light high-contrast and dark default, where upstream uses `github-light`
//    and `github-dark`. Those put comments and some tokens under 4.5:1 on the block's ground and on
//    a marked line (WCAG 2.2 SC 1.4.3), and the accessibility suite fails on them.
// 3. Shiki's core and its regex engine are imported when the first block asks for a highlighter,
//    as the languages and themes already are. Upstream imports them with this module, so every
//    route that shows a code block loaded about 150 kB of highlighter before its first render.
// 4. Lint conformance only, with no change in behaviour: no React import, `async` loaders,
//    a type guard instead of a cast, a suppression for the token style, and `loaded` instead of a
//    local `highlighter` that shadowed the module's.
// 5. Language names match only own registry entries; an inherited object key is not a grammar.
import type { GrammarState, HighlighterCore } from "shiki/core";

// Add the languages you need. Each one loads the first time it is used.
const languages = {
	bash: async () => import("shiki/langs/shellscript.mjs"),
	css: async () => import("shiki/langs/css.mjs"),
	diff: async () => import("shiki/langs/diff.mjs"),
	html: async () => import("shiki/langs/html.mjs"),
	java: async () => import("shiki/langs/java.mjs"),
	javascript: async () => import("shiki/langs/javascript.mjs"),
	json: async () => import("shiki/langs/json.mjs"),
	jsx: async () => import("shiki/langs/jsx.mjs"),
	markdown: async () => import("shiki/langs/markdown.mjs"),
	python: async () => import("shiki/langs/python.mjs"),
	sql: async () => import("shiki/langs/sql.mjs"),
	tsx: async () => import("shiki/langs/tsx.mjs"),
	typescript: async () => import("shiki/langs/typescript.mjs"),
	yaml: async () => import("shiki/langs/yaml.mjs"),
};

const aliases: Record<string, keyof typeof languages> = {
	js: "javascript",
	md: "markdown",
	py: "python",
	sh: "bash",
	shell: "bash",
	shellscript: "bash",
	ts: "typescript",
	yml: "yaml",
	zsh: "bash",
};

let highlighter: Promise<HighlighterCore> | undefined;

async function createHighlighter() {
	const [{ createHighlighterCore }, { createJavaScriptRegexEngine }] = await Promise.all([
		import("shiki/core"),
		import("shiki/engine/javascript"),
	]);

	return createHighlighterCore({
		themes: [
			import("shiki/themes/github-light-high-contrast.mjs"),
			import("shiki/themes/github-dark-default.mjs"),
		],
		langs: [],
		engine: createJavaScriptRegexEngine({ forgiving: true }),
	});
}

async function getHighlighter() {
	highlighter ??= createHighlighter();

	return highlighter;
}

function isListed(name: string): name is keyof typeof languages {
	return Object.hasOwn(languages, name);
}

function getLanguage(language: string) {
	const name = language.toLowerCase();

	if (name === "ansi") {
		return name;
	}

	if (isListed(name)) {
		return name;
	}

	return Object.hasOwn(aliases, name) ? aliases[name] : null;
}

// Loads a language and returns a highlighter for it, or null for a language
// that is not listed.
export async function loadHighlighter(language: string) {
	const lang = getLanguage(language);

	if (!lang) {
		return null;
	}

	const shiki = await getHighlighter();

	if (lang !== "ansi" && !shiki.getLoadedLanguages().includes(lang)) {
		await shiki.loadLanguage(languages[lang]);
	}

	return {
		// Returns one React node per line, and the grammar state after the last
		// line. Pass that state back to continue where the code left off.
		highlight(code: string, state?: GrammarState) {
			const tokenize = (text: string) =>
				shiki.codeToTokens(text, {
					lang,
					themes: { light: "github-light-high-contrast", dark: "github-dark-default" },
					defaultColor: false,
					grammarState: state,
				}).tokens;

			// ANSI has no grammar state, so each line is tokenized on its own and
			// gets the same colors whether it arrives in one piece or streams.
			const tokens =
				lang === "ansi" ? code.split("\n").flatMap((line) => tokenize(line)) : tokenize(code);

			return {
				lines: tokens.map((line) =>
					line.map((token, index) => (
						<span
							key={index}
							// oxlint-disable-next-line shadcn/no-inline-styles -- With `defaultColor: false`, Shiki puts each token's colours in the `--shiki-light` and `--shiki-dark` custom properties that the class reads.
							style={token.htmlStyle}
							className="text-(--shiki-light) dark:text-(--shiki-dark)"
						>
							{token.content}
						</span>
					)),
				),
				state: shiki.getLastGrammarState(tokens),
			};
		},
	};
}

// Returns one React node per line, or null for a language that is not listed.
export async function highlightCode(code: string, language: string) {
	const loaded = await loadHighlighter(language);

	return loaded ? loaded.highlight(code).lines : null;
}
