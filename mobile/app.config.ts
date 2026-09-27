import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import type { ConfigContext, ExpoConfig } from "expo/config";

/**
 * One app, three installable variants that live side by side on a device. The identifier doubles as
 * the private-use URI scheme (RFC 8252 §7.1), so a server's redirect allowlist names exactly the
 * builds it serves. These are local defaults, not registered store identifiers.
 */
const VARIANTS = {
	development: { id: "build.hephaestus.app.dev", name: "Hephaestus Dev" },
	preview: { id: "build.hephaestus.app.preview", name: "Hephaestus Preview" },
	production: { id: "build.hephaestus.app", name: "Hephaestus" },
} as const;

type Variant = keyof typeof VARIANTS;

function variant(): Variant {
	const value = process.env.APP_VARIANT ?? "development";
	if (value === "development" || value === "preview" || value === "production") {
		return value;
	}
	throw new Error(`APP_VARIANT must be development, preview or production, not "${value}"`);
}

/** Provisioned by the maintainers in EAS; absent until then, and nothing is invented in its place. */
function optional(name: string): string | undefined {
	const value = process.env[name]?.trim();
	return value === undefined || value === "" ? undefined : value;
}

/**
 * The port this worktree's server listens on: `SERVER_PORT` in `server/.env`, which the server itself
 * reads (each worktree has its own), or the server's default.
 */
export function serverPort(envFile: string | undefined): number {
	const match =
		envFile === undefined ? null : /^\s*SERVER_PORT\s*=\s*(?<port>\d+)\s*$/mu.exec(envFile);
	return match?.groups?.port === undefined ? 8080 : Number(match.groups.port);
}

/**
 * Which Hephaestus the welcome screen offers first. Production offers hephaestus.build. A development
 * build offers the server of the worktree it was built in, reached at a host the app works out for the
 * device it runs on. A preview build is for a staging server, which must be named: it neither falls
 * back to production nor to a machine it cannot reach.
 */
export function defaultInstance(
	current: Variant,
	env: Record<string, string | undefined>,
	envFile: string | undefined,
): { defaultInstance: string } | { devServerPort: number } {
	if (current === "production") {
		return { defaultInstance: "hephaestus.build" };
	}
	if (current === "development") {
		return { devServerPort: serverPort(envFile) };
	}
	const staging = env.PREVIEW_INSTANCE?.trim();
	if (staging === undefined || staging === "") {
		throw new Error("PREVIEW_INSTANCE must name the staging Hephaestus preview builds sign in to");
	}
	return { defaultInstance: staging };
}

/**
 * Over-the-air updates bypass store review, so a store or preview binary that accepts them must verify
 * their signature. A build with an EAS project but no certificate is refused rather than shipped
 * accepting unsigned updates; without a project, updates are off.
 */
export function updatesConfig(
	current: Variant,
	projectId: string | undefined,
	codeSigningCertificate: string | undefined,
): ExpoConfig["updates"] {
	if (projectId === undefined) {
		return { enabled: false };
	}
	if (codeSigningCertificate === undefined && current !== "development") {
		throw new Error(
			`A ${current} build with EAS_PROJECT_ID needs EXPO_UPDATES_CODE_SIGNING_CERTIFICATE: it would otherwise accept unsigned updates`,
		);
	}
	return {
		enabled: true,
		url: `https://u.expo.dev/${projectId}`,
		checkAutomatically: "ON_LOAD",
		fallbackToCacheTimeout: 0,
		...(codeSigningCertificate === undefined
			? {}
			: {
					codeSigningCertificate,
					codeSigningMetadata: { keyid: "main", alg: "rsa-v1_5-sha256" },
				}),
	};
}

function readServerEnv(projectRoot: string): string | undefined {
	const file = path.join(projectRoot, "..", "server", ".env");
	return existsSync(file) ? readFileSync(file, "utf8") : undefined;
}

export default function appConfig({ config, projectRoot }: ConfigContext): ExpoConfig {
	const current = variant();
	const { id, name } = VARIANTS[current];
	// Only the development build may reach a plain-HTTP server on this machine.
	const local = current === "development";
	const projectId = optional("EAS_PROJECT_ID");
	const codeSigningCertificate = optional("EXPO_UPDATES_CODE_SIGNING_CERTIFICATE");
	const googleServicesFile = optional("GOOGLE_SERVICES_JSON");

	return {
		...config,
		name,
		slug: "hephaestus",
		owner: optional("EAS_OWNER"),
		version: "0.1.0",
		scheme: id,
		orientation: "default",
		userInterfaceStyle: "automatic",
		icon: "./assets/icon.png",
		ios: {
			bundleIdentifier: id,
			supportsTablet: true,
			config: { usesNonExemptEncryption: false },
			// The React Native template allows local networking in every build; only the development build
			// talks to a server on this machine.
			infoPlist: {
				NSAppTransportSecurity: { NSAllowsArbitraryLoads: false, NSAllowsLocalNetworking: local },
				// A chosen self-hosted instance may be on the local network, including in a store build.
				NSLocalNetworkUsageDescription:
					"Connect to the Hephaestus instance you choose on your local network.",
			},
		},
		android: {
			package: id,
			adaptiveIcon: {
				foregroundImage: "./assets/adaptive-icon.png",
				backgroundColor: "#315FDC",
			},
			// The app keeps its data in its own sandbox and the keystore, and records nothing. The
			// storage permissions come from the React Native template; drawing over other apps is the
			// development menu's, needed by the development build alone.
			blockedPermissions: [
				"android.permission.READ_EXTERNAL_STORAGE",
				"android.permission.WRITE_EXTERNAL_STORAGE",
				"android.permission.RECORD_AUDIO",
				// Secure storage asks for these in case a value requires the fingerprint; none does.
				"android.permission.USE_BIOMETRIC",
				"android.permission.USE_FINGERPRINT",
				...(local ? [] : ["android.permission.SYSTEM_ALERT_WINDOW"]),
			],
			// Push needs Firebase; the file is an EAS file variable, never committed.
			...(googleServicesFile === undefined ? {} : { googleServicesFile }),
		},
		// Fingerprint runtime: an update only reaches binaries whose native code it was built against.
		// It says nothing about the server API, which the contract gate owns.
		runtimeVersion: { policy: "fingerprint" },
		updates: updatesConfig(current, projectId, codeSigningCertificate),
		extra: {
			...defaultInstance(
				current,
				process.env,
				current === "development" ? readServerEnv(projectRoot) : undefined,
			),
			...(projectId === undefined ? {} : { eas: { projectId } }),
		},
		experiments: { typedRoutes: true },
		plugins: [
			"expo-router",
			[
				"expo-build-properties",
				{
					// The iOS 27 SDK requires the UIKit scene life cycle (Expo ≥ 57.0.23).
					ios: { enableSceneSupport: true },
					android: { usesCleartextTraffic: local },
				},
			],
			// Keeps the keychain-backed session out of Android Auto Backup. No value requires Face ID, so the
			// app does not ask for it.
			["expo-secure-store", { configureAndroidBackup: true, faceIDPermission: false }],
			[
				"expo-notifications",
				{
					icon: "./assets/notification-icon.png",
					color: "#315FDC",
				},
			],
			[
				"expo-splash-screen",
				{
					image: "./assets/splash-icon.png",
					imageWidth: 120,
					backgroundColor: "#FFFFFF",
					dark: { image: "./assets/splash-icon.png", backgroundColor: "#0B0D12" },
				},
			],
			"expo-web-browser",
		],
	};
}
