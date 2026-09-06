const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";
const SEMVER = /^\d+\.\d+\.\d+$/;

export type EnvironmentTone = "staging" | "preview" | "local";

export type HeaderBadge =
	| { kind: "release"; label: string; href: string; tooltip: string; ariaLabel: string }
	| { kind: "environment"; label: string; tone: EnvironmentTone };

function toneFor(environmentName: string): EnvironmentTone {
	const name = environmentName.toLowerCase();
	if (name === "staging") return "staging";
	if (name === "preview") return "preview";
	return "local";
}

/**
 * A version that is not semver (a commit SHA, `nightly`) has no release page to link to, so it
 * falls through to the environment pill rather than producing a dead link.
 */
export function resolveHeaderBadge(
	version: string,
	environmentName: string,
	isProduction: boolean,
): HeaderBadge {
	if (isProduction && SEMVER.test(version)) {
		return {
			kind: "release",
			label: `v${version}`,
			href: `${REPO_URL}/releases/tag/v${version}`,
			tooltip: "View release notes",
			ariaLabel: `View release v${version}`,
		};
	}
	return { kind: "environment", label: environmentName, tone: toneFor(environmentName) };
}
