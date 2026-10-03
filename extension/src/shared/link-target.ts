/**
 * Whether an address may become a live link: a web URL on the one origin the link is allowed to
 * reach, so a `javascript:` URL or another site never can.
 */
export function linksWithin(href: string, allowedOrigin: string): boolean {
	const url = URL.parse(href);
	return (
		url !== null &&
		url.origin === allowedOrigin &&
		(url.protocol === "https:" || url.protocol === "http:")
	);
}
