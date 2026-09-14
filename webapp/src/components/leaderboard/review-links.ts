export function reviewLinkUrl(value: string | undefined) {
	const url = URL.parse(value ?? "");
	return url && ["https:", "http:"].includes(url.protocol) && !url.username && !url.password
		? url.href
		: undefined;
}

export async function copyReviewLinks(links: readonly { label: string; url: string }[]) {
	if (links.length === 0) throw new Error("No review links to copy");
	const clipboard = navigator.clipboard;
	const plainText = links.map(({ url }) => url).join("\n");

	if (typeof ClipboardItem !== "undefined") {
		const list = document.createElement("ul");
		for (const { label, url } of links) {
			const item = document.createElement("li");
			const anchor = document.createElement("a");
			anchor.href = url;
			anchor.textContent = label;
			item.append(anchor);
			list.append(item);
		}
		try {
			await clipboard.write([
				new ClipboardItem({
					"text/html": new Blob([list.outerHTML], { type: "text/html" }),
					"text/plain": new Blob([plainText], { type: "text/plain" }),
				}),
			]);
			return;
		} catch {
			// Rich clipboard formats may be unavailable even when plain text is permitted.
		}
	}
	await clipboard.writeText(plainText);
}
