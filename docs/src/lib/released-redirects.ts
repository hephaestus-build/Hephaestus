/**
 * A released address is a contract with readers. Every page path in the sitemap of a published release keeps working,
 * so a page that moves or goes away after a release redirects to the page that now owns its content, never to the home
 * page. `docs/released-paths.txt` lists the released addresses, and `scripts/check-docs-addresses.ts` fails the build
 * when one has neither a page nor an entry here. Astro writes each entry as a page that redirects at once.
 */
export const RELEASED_REDIRECTS = {
	// 1.0 keeps nothing for 0.x (ADR 0096): the 1.0 cutover owns the moves from a release before 1.0.0-rc.1.
	"/admin/compose-to-kubernetes": "/admin/upgrade-to-1-0",
	"/admin/liquibase-baseline-runbook": "/admin/upgrade-to-1-0",
	// The release-specific procedures of the upgrade page.
	"/admin/native-hibernate-json-cutover": "/admin/upgrade",
	"/admin/practice-model-cutover": "/admin/upgrade",
	// Workspace-admin tasks moved to the user guide.
	"/admin/integrations": "/user/integrations",
	"/admin/practice-review": "/user/practice-reviews",
	"/admin/practice-scans": "/user/scans",
	"/admin/writing-practices": "/user/practices",
	// Redirect pages that rc.8 published; released files still link to them.
	"/admin/install": "/admin/kubernetes",
	"/user/ai-mentor": "/user/ask-hephaestus",
	"/contributor/practice-catalogue": "/contributor/practice-catalog",
	// The integration settings are part of the configuration reference.
	"/admin/production-setup": "/admin/configuration-reference",
} satisfies Record<string, string>;
