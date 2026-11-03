/**
 * The oldest server this extension supports. Chrome updates the extension on its own, ahead of a
 * self-hosted server, so a 1.y extension calls only what this release offers, or handles the 404.
 * `background/older-server.test.ts` holds it to that. Raise this only in a major release.
 */
export const OLDEST_SERVER_VERSION = "1.0.0";

const RELEASE = /^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:[-+].*)?$/u;

function parts(version: string): [number, number, number] | undefined {
	const groups = RELEASE.exec(version)?.groups;
	return groups === undefined
		? undefined
		: [Number(groups.major), Number(groups.minor), Number(groups.patch)];
}

/**
 * Whether a version is a release before the oldest supported one. A release candidate that names its
 * version counts as its release. Something that is no release, such as "DEV", says nothing about age.
 */
export function isOlderThanSupported(version: string): boolean {
	const found = parts(version);
	const oldest = parts(OLDEST_SERVER_VERSION);
	if (found === undefined || oldest === undefined) {
		return false;
	}
	const difference = found.map((part, index) => part - (oldest[index] ?? 0)).find(Boolean);
	return difference !== undefined && difference < 0;
}

/** Says what is true about a server the extension does not support, and who can change it. */
export function serverTooOldText(facts: {
	instanceHost: string;
	serverVersion?: string;
	oldestServer: string;
}): string {
	const age =
		facts.serverVersion === undefined
			? `runs a version older than ${facts.oldestServer}`
			: `runs version ${facts.serverVersion}`;
	return `Hephaestus on ${facts.instanceHost} ${age}. This extension needs version ${facts.oldestServer} or newer. Chrome updates the extension on its own, so ask your instance operator to upgrade the server.`;
}
