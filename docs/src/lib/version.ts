import { SITE_URL } from "@/lib/site";

export const channel = import.meta.env.DOCS_CHANNEL === "latest" ? "latest" : "next";
const release = import.meta.env.DOCS_RELEASE;

export const latestLabel = release === "" ? "Latest release" : `Latest release (${release})`;
export const nextLabel = "Next (unreleased)";
export const versions = [
	{ label: latestLabel, href: `${SITE_URL}/`, current: channel === "latest" },
	{ label: nextLabel, href: `${SITE_URL}/next/`, current: channel === "next" },
] as const;
