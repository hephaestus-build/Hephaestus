const APP_NAME = "Hephaestus";

const SEPARATOR = " · ";

/** `"AI usage · Instance administration · Hephaestus"` — page name first, because a narrow tab truncates from the end. */
export function pageTitle(...parts: string[]): string {
	return [...parts, APP_NAME].join(SEPARATOR);
}

/** A title without the app's name: `"Practice profile · Hephaestus"` becomes `"Practice profile"`. */
export function withoutAppName(title: string): string {
	const suffix = `${SEPARATOR}${APP_NAME}`;
	return title.endsWith(suffix) ? title.slice(0, -suffix.length) : title;
}

export function instanceAdminHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page, "Instance administration") }] });
}

export function workspaceAdminHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page, "Admin") }] });
}

/**
 * `"Practice profile · Hephaestus"`. A page names no scope: the workspace is where the reader already
 * is, and the admin consoles say theirs because they are a step away from it. Every route a reader
 * can land on sets one, because a tab, a history entry and a screen reader's page announcement all
 * read the title and nothing else (WCAG 2.2 SC 2.4.2).
 */
export function pageHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page) }] });
}
