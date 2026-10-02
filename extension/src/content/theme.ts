import type { FramePalette } from "~/shared/frame-messages";
import type { WorkPageProvider } from "~/shared/work-url";

/** The attributes on `<html>` that {@link providerTheme} reads; observed while the page is open. */
export const THEME_ATTRIBUTES = ["class", "data-color-mode", "data-light-theme", "data-dark-theme"];

/** The system preference GitHub's `auto` and GitLab's `gl-system` follow. */
export const DARK_SCHEME_QUERY = "(prefers-color-scheme: dark)";

/**
 * A GitHub theme name, reduced to the frame's palettes by its family and contrast: `dark_dimmed`,
 * `light_high_contrast`, `dark_high_contrast` and `dark_dimmed_high_contrast` are their own; a
 * colour-vision variant (`dark_colorblind`, `light_tritanopia_high_contrast`, …) takes its family's
 * palette at its contrast; a name with no family takes the slot's.
 */
export function githubPalette(name: string | undefined, slot: "light" | "dark"): FramePalette {
	let family = slot;
	if (name?.startsWith("dark") === true) {
		family = "dark";
	} else if (name?.startsWith("light") === true) {
		family = "light";
	}
	const high = name?.includes("high_contrast") === true;
	if (family === "light") {
		return high ? "light_high_contrast" : "light";
	}
	if (name?.includes("dimmed") === true) {
		return high ? "dark_dimmed_high_contrast" : "dark_dimmed";
	}
	return high ? "dark_high_contrast" : "dark";
}

/**
 * The palette the provider page shows now. GitHub keeps a selected theme per slot
 * (`data-light-theme`, `data-dark-theme`) and `data-color-mode` picks the slot, `auto` by the system;
 * either slot may hold a dark theme. GitLab puts `gl-light`, `gl-dark` or `gl-system` on `<html>`, and
 * its own script adds `gl-dark` under a dark system; a GitLab old enough to have none is light.
 */
export function providerTheme(
	root: HTMLElement,
	provider: WorkPageProvider,
	systemDark: boolean,
): FramePalette {
	if (provider === "GITLAB") {
		if (root.classList.contains("gl-dark")) {
			return "dark";
		}
		return root.classList.contains("gl-system") && systemDark ? "dark" : "light";
	}
	const mode = root.dataset.colorMode;
	let slot: "light" | "dark" = systemDark ? "dark" : "light";
	if (mode === "light" || mode === "dark") {
		slot = mode;
	}
	return githubPalette(slot === "dark" ? root.dataset.darkTheme : root.dataset.lightTheme, slot);
}
