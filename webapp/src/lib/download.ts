/**
 * Hands text to the browser as a file download. An anchor with `download` is the only way to name
 * the file without a server round-trip; it has to be in the document for Firefox to honour the
 * click.
 */
export function saveTextFile(text: string, filename: string, mimeType: string): void {
	const url = URL.createObjectURL(new Blob([text], { type: mimeType }));
	const anchor = document.createElement("a");
	anchor.href = url;
	anchor.download = filename;
	document.body.appendChild(anchor);
	anchor.click();
	anchor.remove();
	URL.revokeObjectURL(url);
}
