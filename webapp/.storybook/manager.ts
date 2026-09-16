import { addons } from "storybook/manager-api";

/**
 * The house filename rule writes an acronym as a word (`LlmUsage`, `admin/workspace-llm/`) and a
 * brand with an interior capital as two (`ConnectGitLabStep`), so start case alone would put "Llm"
 * and "Git Lab" in the sidebar. These read as the product spells them.
 */
const SPELLINGS: Record<string, string> = {
	cta: "CTA",
	faq: "FAQ",
	git: "Git",
	github: "GitHub",
	gitlab: "GitLab",
	llm: "LLM",
	scm: "SCM",
	ui: "UI",
	xp: "XP",
};

const startCase = (input: string): string =>
	input
		.replaceAll(/(?<lower>[a-z])(?<upper>[A-Z])/gu, "$<lower> $<upper>")
		.replaceAll(/[_-]+/gu, " ")
		.replaceAll(/Git (?<brand>Hub|Lab)/gu, "Git$<brand>")
		.trim()
		.split(/\s+/u)
		.map((word) => SPELLINGS[word.toLowerCase()] ?? word.charAt(0).toUpperCase() + word.slice(1))
		.join(" ");

addons.setConfig({
	sidebar: {
		renderLabel: ({ name, type }) => (type === "story" ? name : startCase(name)),
	},
});
