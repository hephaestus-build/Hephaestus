/**
 * The messages with which browsers and Vite fail to load a module or its CSS. After a deploy, a tab
 * that still runs the previous build asks for hashed files that the server no longer has. The first
 * three are TanStack Router's `isModuleNotFoundError` list (Chromium, Firefox, Safari); that helper
 * lives in `@tanstack/router-core`, which this app does not depend on directly.
 */
const STALE_BUILD_MESSAGES = [
	"Failed to fetch dynamically imported module",
	"error loading dynamically imported module",
	"Importing a module script failed",
	"Unable to preload CSS",
];

/** Whether an error says that this tab's build is older than the server's: a reload fixes it. */
export function isStaleBuildError(error: unknown): boolean {
	return (
		error instanceof Error && STALE_BUILD_MESSAGES.some((start) => error.message.startsWith(start))
	);
}
