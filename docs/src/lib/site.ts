export const SITE_URL = "https://docs.hephaestus.build";
export const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";
export const PRODUCT_URL = "https://hephaestus.build";
export const SITE_TITLE = "Hephaestus documentation";

export const DESCRIPTION =
	"Hephaestus reviews the pull requests, issues, and code reviews your team already writes against the engineering practices you choose. It gives each developer feedback that cites their work and names a next step.";

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
