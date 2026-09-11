const REPO_URL = "https://github.com/hephaestus-build/Hephaestus";
const SEMVER = /^\d+\.\d+\.\d+$/;

export type EnvironmentTone = "staging" | "preview" | "local";

export type HeaderBadge =
	| { kind: "release"; label: string; href: string; tooltip: string; ariaLabel: string }
	| {
			kind: "environment";
			label: string;
			tone: EnvironmentTone;
			tooltip: string;
			/**
			 * Present only on a preview, whose pull request the pill both names and links to. The
			 * link takes its accessible name from that visible label, so it carries no `aria-label`
			 * of its own: a name that does not contain the visible text is one a speech-input user
			 * cannot say (WCAG 2.5.3).
			 */
			href?: string;
	  };

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
	pullRequest?: number,
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
	const tone = toneFor(environmentName);
	// The environment name stays in the label rather than being left to the dot's colour, which is
	// the one part of this pill a colour-blind reader cannot use.
	if (pullRequest !== undefined) {
		return {
			kind: "environment",
			label: `${environmentName} · PR #${pullRequest}`,
			tone,
			tooltip: `${environmentName} of pull request #${pullRequest}`,
			href: `${REPO_URL}/pull/${pullRequest}`,
		};
	}
	return {
		kind: "environment",
		label: environmentName,
		tone,
		tooltip: `${environmentName} environment`,
	};
}
