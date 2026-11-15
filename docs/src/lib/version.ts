import { SITE_URL } from "@/lib/site";

export const channel = import.meta.env.DOCS_CHANNEL === "latest" ? "latest" : "next";
const release = import.meta.env.DOCS_RELEASE;

export const latestLabel = release === "" ? "Latest release" : `Latest release (${release})`;
export const nextLabel = "Next (unreleased)";
export const versions = [
	{ label: latestLabel, href: `${SITE_URL}/`, current: channel === "latest" },
	{ label: nextLabel, href: `${SITE_URL}/next/`, current: channel === "next" },
] as const;

/** The same path in the other version: a page can exist in the release docs and not in the next ones, or the other way round. */
export function otherVersion(path: string): { label: string; href: string } {
	const rest = path.replace(/^\//u, "").replace(/^404(?:\.html)?$/u, "");
	return channel === "latest"
		? { label: "Look for it in the docs of the next release", href: `${SITE_URL}/next/${rest}` }
		: { label: "Look for it in the docs of the latest release", href: `${SITE_URL}/${rest}` };
}
