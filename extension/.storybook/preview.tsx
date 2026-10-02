import { withThemeByClassName } from "@storybook/addon-themes";
import type { Decorator, Preview } from "@storybook/react-vite";

import "~/ui/styles.css";

/**
 * `parameters.reflow` renders the story in a 320px box — the reflow width — because the story
 * runner's browser viewport is fixed and a viewport global does not resize it
 * (`src/stories/reflow.ts` asserts against the box).
 */
const withSurface: Decorator = (Story, { parameters }) => (
	<div className="bg-background p-4 text-foreground">
		{parameters.reflow === true ? (
			<div className="w-80" data-reflow>
				<Story />
			</div>
		) : (
			<Story />
		)}
	</div>
);

/**
 * `parameters.provider` renders the story as the inline frame does inside that provider's page: the
 * same `data-surface` and `data-provider` on `<html>` that `entrypoints/inline/main.tsx` sets, so
 * the provider's neutrals from `styles.css` apply. Stories without it get the extension's own look.
 * `parameters.palette` adds the `data-palette` of a GitHub variant (dimmed, high contrast); pair a
 * dark one with the dark theme global, as `ui/theme.ts` pairs it with the `dark` class.
 */
const withProvider: Decorator = (Story, { parameters }) => {
	const root = document.documentElement;
	const provider: unknown = parameters.provider;
	const palette: unknown = parameters.palette;
	if (provider === "github" || provider === "gitlab") {
		root.dataset.surface = "inline";
		root.dataset.provider = provider;
	} else {
		delete root.dataset.surface;
		delete root.dataset.provider;
	}
	if (typeof palette === "string") {
		root.dataset.palette = palette;
	} else {
		delete root.dataset.palette;
	}
	return <Story />;
};

const preview: Preview = {
	parameters: {
		a11y: { test: "error" },
	},
	decorators: [
		withSurface,
		withProvider,
		// The same `dark` class on `<html>` that `src/ui/theme.ts` sets in the extension's pages, as the
		// webapp's Storybook does.
		withThemeByClassName({
			themes: { light: "light", dark: "dark" },
			defaultTheme: "light",
			parentSelector: "html",
		}),
	],
};

export default preview;
