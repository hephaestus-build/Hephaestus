const APP_NAME = "Hephaestus";

/** `"AI usage · Instance admin · Hephaestus"` — page name first, because a narrow tab truncates from the end. */
function pageTitle(...parts: string[]): string {
	return [...parts, APP_NAME].join(" · ");
}

export function instanceAdminHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page, "Instance admin") }] });
}

export function workspaceAdminHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page, "Admin") }] });
}

/**
 * `"Practice profile · Hephaestus"`. A workspace's own page names no scope: the workspace is where
 * the reader already is, and the admin consoles say theirs because they are a step away from it.
 */
export function workspaceHead(page: string) {
	return () => ({ meta: [{ title: pageTitle(page) }] });
}
