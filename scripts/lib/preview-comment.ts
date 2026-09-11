/** GitHub issue comments are limited to 65,536 characters, including the publisher's marker. */
export const PREVIEW_COMMENT_LIMIT = 60_000;

export interface PreviewLink {
	group: string;
	title: string;
	url: string;
}

export function markdown(value: string): string {
	return value
		.replaceAll(/\s/g, " ")
		.replaceAll("&", "&amp;")
		.replaceAll(/[\\`*_[\]~|#!]/g, "\\$&")
		.replaceAll("<", "&lt;")
		.replaceAll(">", "&gt;");
}

export function compareLinks(left: PreviewLink, right: PreviewLink): number {
	// Code-point order is stable across runner locales and metadata traversal order.
	for (const key of ["group", "title", "url"] as const) {
		if (left[key] < right[key]) return -1;
		if (left[key] > right[key]) return 1;
	}
	return 0;
}

/** Pack whole links, repeating the group heading when a group spans comments. Never truncate. */
export function renderPreviewComments(
	introduction: string,
	links: readonly PreviewLink[],
	emptyMessage: string,
	footer: string,
): string[] {
	const pages: string[] = [];
	// Reserve room for the part indicator before packing; the footer appears on every part.
	const budget = PREVIEW_COMMENT_LIMIT - introduction.length - footer.length - 100;
	let page = "";
	for (const [group, entries] of Map.groupBy(links.toSorted(compareLinks), (link) => link.group)) {
		const heading = `#### ${markdown(group)} (${entries.length})\n\n`;
		let block = heading;
		for (const entry of entries) {
			const link = `[${markdown(entry.title)}](<${entry.url.replaceAll("&", "&amp;")}>)`;
			const separator = block === heading ? "" : " ·\n";
			if (heading.length + link.length > budget) {
				throw new Error(`A preview link in ${group} exceeds GitHub's comment limit.`);
			}
			if (page.length + block.length + separator.length + link.length > budget) {
				if (block !== heading) page += block;
				if (page) pages.push(page.trimEnd());
				page = "";
				block = heading + link;
			} else {
				block += separator + link;
			}
		}
		page += `${block}\n\n`;
	}
	if (page || pages.length === 0) pages.push(page.trimEnd() || emptyMessage);
	return pages.map((body, index) => {
		const part =
			pages.length > 1
				? `\n\n**Part ${index + 1} of ${pages.length}** — all parts are listed in this pull request.`
				: "";
		const rendered = `${introduction}${part}\n\n${body}${footer ? `\n\n${footer}` : ""}\n`;
		if (rendered.length > PREVIEW_COMMENT_LIMIT) {
			throw new Error("Preview comment exceeds GitHub's comment limit.");
		}
		return rendered;
	});
}
