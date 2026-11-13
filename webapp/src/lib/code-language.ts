/**
 * The highlighter's name for the language of a file, read from its extension. A file with no
 * extension, or one the highlighter does not list, renders as plain text.
 */
export function codeLanguage(path: string | null | undefined): string {
	const fileName = path?.slice(path.lastIndexOf("/") + 1) ?? "";
	const dot = fileName.lastIndexOf(".");
	return dot === -1 ? "" : fileName.slice(dot + 1);
}
