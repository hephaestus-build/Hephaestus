/**
 * The export needs the bearer token, so it cannot be a plain link; the response is handed to the
 * browser as a named file instead. The anchor has to be in the document for Firefox to honour the
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
