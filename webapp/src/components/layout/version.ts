const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";
const SEMVER = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u;

export type EnvironmentTone = "staging" | "local";

export type HeaderBadge =
	| { kind: "release"; label: string; href: string; tooltip: string; ariaLabel: string }
	| { kind: "environment"; label: string; tone: EnvironmentTone; tooltip: string };

function toneFor(environmentName: string): EnvironmentTone {
	const name = environmentName.toLowerCase();
	if (name === "staging") {
		return "staging";
	}
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
			ariaLabel: `View release v${version} (opens in a new tab)`,
		};
	}
	const tone = toneFor(environmentName);
	// The environment name stays in the label rather than being left to the dot's colour, which is
	// the one part of this pill a colour-blind reader cannot use.
	return {
		kind: "environment",
		label: environmentName,
		tone,
		tooltip: `${environmentName} environment`,
	};
}
