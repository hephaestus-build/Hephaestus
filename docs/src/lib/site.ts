export const SITE_URL = "https://docs.hephaestus.build";
export const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";
export const PRODUCT_URL = "https://hephaestus.build";
export const SITE_TITLE = "Hephaestus documentation";

export const DESCRIPTION =
	"Hephaestus is the open-source AI mentor for software engineering practices. It reads your real work and can suggest what to improve and why. You can talk it through in a conversation.";

export const GUIDES = [
	{
		path: "user",
		label: "User guide",
		description: "Feedback on your work, and how to talk it through.",
	},
	{ path: "admin", label: "Admin guide", description: "Install and run a deployment." },
	{ path: "contributor", label: "Contributor guide", description: "Change the code and ship it." },
] as const;

/** The base path with a trailing slash: `/` or `/next/`. Astro drops the slash when `trailingSlash` is `"never"`. */
export const BASE = import.meta.env.BASE_URL.endsWith("/")
	? import.meta.env.BASE_URL
	: `${import.meta.env.BASE_URL}/`;
