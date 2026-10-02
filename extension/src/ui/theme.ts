import { type FramePalette, type FrameTheme, isDarkPalette } from "~/shared/frame-messages";

const DARK_QUERY = "(prefers-color-scheme: dark)";

/**
 * The one owner of an extension page's theme: what the provider page said last. Only `system`
 * follows the system's preference; a palette the page chose stays when the system changes.
 */
let current: FrameTheme = "system";

function systemPalette(): FramePalette {
	return window.matchMedia(DARK_QUERY).matches ? "dark" : "light";
}

function render(): void {
	const palette = current === "system" ? systemPalette() : current;
	document.documentElement.classList.toggle("dark", isDarkPalette(palette));
	document.documentElement.dataset.palette = palette;
}

/**
 * The provider page's palette when the inline frame is told one, otherwise the system's light or
 * dark. Applied as the `dark` class the tokens in `styles.css` key on, and as `data-palette` for the
 * provider variants (dimmed, high contrast) that `styles.css` reviews.
 */
export function applyTheme(theme: FrameTheme): void {
	current = theme;
	render();
}

/**
 * Starts with `initial` and keeps following the system's preference for as long as the theme is
 * `system`, including after a provider page switches back to it.
 */
export function followSystemTheme(initial: FrameTheme = "system"): () => void {
	const query = window.matchMedia(DARK_QUERY);
	const listener = () => {
		render();
	};
	applyTheme(initial);
	query.addEventListener("change", listener);
	return () => {
		query.removeEventListener("change", listener);
	};
}
