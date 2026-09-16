import { addons } from "storybook/manager-api";

/**
 * The house filename rule writes an acronym as a word (`LlmUsage`, `admin/ai/`), so start case
 * alone would put "Llm" and "Ai" in the sidebar. These read as the product spells them.
 */
const ACRONYMS = new Set(["ai", "cta", "faq", "llm", "scm", "ui", "xp"]);

const startCase = (input: string): string =>
	input
		.replaceAll(/(?<lower>[a-z])(?<upper>[A-Z])/gu, "$<lower> $<upper>")
		.replaceAll(/[_-]+/gu, " ")
		.trim()
		.split(/\s+/u)
		.map((word) =>
			ACRONYMS.has(word.toLowerCase())
				? word.toUpperCase()
				: word.charAt(0).toUpperCase() + word.slice(1),
		)
		.join(" ");

addons.setConfig({
	sidebar: {
		renderLabel: ({ name, type }) => (type === "story" ? name : startCase(name)),
	},
});
