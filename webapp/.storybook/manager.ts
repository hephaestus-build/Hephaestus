import { addons } from "storybook/manager-api";

const startCase = (input: string): string =>
	input
		.replaceAll(/(?<lower>[a-z])(?<upper>[A-Z])/gu, "$<lower> $<upper>")
		.replaceAll(/[_-]+/gu, " ")
		.trim()
		.split(/\s+/u)
		.map((word) => word.charAt(0).toUpperCase() + word.slice(1))
		.join(" ");

addons.setConfig({
	sidebar: {
		renderLabel: ({ name, type }) => (type === "story" ? name : startCase(name)),
	},
});
