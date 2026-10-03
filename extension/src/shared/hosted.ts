/**
 * The Hephaestus service the extension offers first, and the public documentation it links to. A
 * self-hosted instance stays one disclosure away; nothing is contacted until the user chooses.
 */
export const HOSTED_INSTANCE_ORIGIN = "https://hephaestus.build";

export const DOCS_ORIGIN = "https://docs.hephaestus.build";
export const EXTENSION_HELP_URL = `${DOCS_ORIGIN}/user/browser-extension`;
export const EXTENSION_PRIVACY_URL = `${DOCS_ORIGIN}/user/browser-extension-privacy`;

export function isHostedInstance(origin: string): boolean {
	return origin === HOSTED_INSTANCE_ORIGIN;
}
