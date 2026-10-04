// Precompute HINTS for declares-permissions-truthfully-at-point-of-use: the permission-bearing API calls
// and authorization requests ADDED in Swift files, and the usage-description keys located in the change
// or in the first plist/project files of the checkout, each with its path. A key located in some file
// does not show that the target in question declares it, nor does a key not located show that it does
// not; the review reads the target, the actual API and the description.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { countLabel, sampleNote, scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// capability label -> [the API use that can need permission, usage keys that may declare it]. An import
// or a type name alone is not access: the system photo picker needs no library permission, and an
// image picker reaches the camera only when the camera is its source.
const CAPABILITIES: readonly [string, RegExp, string[]][] = [
	[
		"Location",
		/\bCLLocationManager\b/u,
		["NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription"],
	],
	[
		"Camera",
		/\bAVCaptureDevice\b|\bAVCaptureSession\b|\bsourceType\s*[:=]\s*\.camera\b|\.isSourceTypeAvailable\s*\(\s*\.camera\s*\)/u,
		["NSCameraUsageDescription"],
	],
	[
		"Microphone",
		/\bAVAudioRecorder\b|\bAVAudioSession\b.*record|\.record\(/u,
		["NSMicrophoneUsageDescription"],
	],
	[
		"PhotoLibrary",
		/\bPHPhotoLibrary\b|\bPHAsset\b/u,
		["NSPhotoLibraryUsageDescription", "NSPhotoLibraryAddUsageDescription"],
	],
	["Notifications", /\bUNUserNotificationCenter\b/u, []],
	[
		"Calendar",
		/\bEKEventStore\b/u,
		[
			"NSCalendarsUsageDescription",
			"NSCalendarsFullAccessUsageDescription",
			"NSCalendarsWriteOnlyAccessUsageDescription",
		],
	],
	["Contacts", /\bCNContactStore\b/u, ["NSContactsUsageDescription"]],
	[
		"HealthKit",
		/\bHKHealthStore\b/u,
		["NSHealthShareUsageDescription", "NSHealthUpdateUsageDescription"],
	],
	["Tracking", /\bATTrackingManager\b/u, ["NSUserTrackingUsageDescription"]],
	[
		"Bluetooth",
		/\bCBCentralManager\b|\bCBPeripheralManager\b/u,
		["NSBluetoothAlwaysUsageDescription"],
	],
	["Motion", /\bCMMotionActivityManager\b|\bCMPedometer\b/u, ["NSMotionUsageDescription"]],
	["Speech", /\bSFSpeechRecognizer\b/u, ["NSSpeechRecognitionUsageDescription"]],
	["LocalNetwork", /\bNWBrowser\b|\bNetService\b/u, ["NSLocalNetworkUsageDescription"]],
];

const REQUEST: readonly SourcePattern[] = [
	[
		"authorization request",
		/\brequest(?:WhenInUse|Always)?Authorization\b|\brequestAccess\b|\brequestTrackingAuthorization\b|\bPHPhotoLibrary\.requestAuthorization\b/u,
	],
	...CAPABILITIES.map(([label, re]): SourcePattern => [`capability ${label}`, re]),
];

const USAGE_KEY = /\b(?<key>NS\w+UsageDescription)\b/gu;

/** How many plist/project files of the checkout are read for keys. */
const PROJECT_FILES_READ = 20;

interface CheckoutKeys {
	keys: Map<string, string[]>;
	filesFound: number;
	filesRead: number;
	unreadable: number;
}

async function usageKeysInCheckout(repoPath: string): Promise<CheckoutKeys> {
	const keys = new Map<string, string[]>();
	let files: string[] = [];
	try {
		files = [
			...globFilesSync("**/Info.plist", repoPath),
			...globFilesSync("**/project.yml", repoPath),
			...globFilesSync("**/project.yaml", repoPath),
			...globFilesSync("**/*.xcodeproj/project.pbxproj", repoPath),
		].filter((f) => !/(?:^|\/)(?:Pods|\.build|DerivedData|Carthage)\//u.test(f));
	} catch {
		// no readable checkout: no file read, so no key located
	}
	let unreadable = 0;
	const read = files.slice(0, PROJECT_FILES_READ);
	for (const file of read) {
		let text: string;
		try {
			text = await readFile(path.join(repoPath, file), "utf8");
		} catch {
			unreadable += 1;
			continue;
		}
		for (const match of text.matchAll(USAGE_KEY)) {
			const key = match.groups?.key ?? "";
			keys.set(key, [...new Set([...(keys.get(key) ?? []), file])]);
		}
	}
	return { keys, filesFound: files.length, filesRead: read.length - unreadable, unreadable };
}

export default async function declaresPermissionsTruthfullyAtPointOfUse(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_metadata: PullRequestMetadata,
) {
	const scan = await scanAddedLines(repoPath, diffFiles, {
		languages: ["swift"],
		patterns: REQUEST,
	});
	const hints: Hint[] = [...scan.hints];
	// Usage keys the change itself adds, in any file.
	const keysInChange = new Map<string, string>();
	for (const [file, df] of diffFiles) {
		if (!/Info\.plist$|project\.ya?ml$|project\.pbxproj$/u.test(file)) {
			continue;
		}
		for (const [line, content] of df.addedLines) {
			for (const match of content.matchAll(USAGE_KEY)) {
				const key = match.groups?.key ?? "";
				keysInChange.set(key, file);
				hints.push({
					file,
					line,
					pattern: "usage key added",
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: { key },
				});
			}
		}
	}
	const checkout = await usageKeysInCheckout(repoPath);
	const capabilities = CAPABILITIES.filter(([label]) => scan.counts.has(`capability ${label}`));
	const located = (keys: string[]) =>
		keys.flatMap((key) => {
			const inChange = keysInChange.get(key);
			const where = [
				...new Set([
					...(inChange === undefined ? [] : [inChange]),
					...(checkout.keys.get(key) ?? []),
				]),
			];
			return where.length === 0 ? [] : [`${key} (${where.join(", ")})`];
		});
	const directions: string[] = [...sampleNote(scan)];
	if (capabilities.length > 0) {
		const read = `${String(checkout.filesRead)} of ${String(checkout.filesFound)} plist/project file(s) read${checkout.unreadable > 0 ? `, ${String(checkout.unreadable)} unreadable` : ""}`;
		directions.push(
			`API uses that can need permission: ${capabilities
				.map(([label, , keys]) => {
					if (keys.length === 0) {
						return `${label} (no usage key applies)`;
					}
					const found = located(keys);
					return `${label} — keys located: ${found.length > 0 ? found.join("; ") : "none"}`;
				})
				.join(
					". ",
				)}. Searched the change and ${read}. A key located in a file does not show the target declares it, and a key not located does not show it is missing: read the target's own Info.plist or build settings, and the key the actual API and deployment target require.`,
		);
	}
	const requests = countLabel(scan, "authorization request");
	if (requests > 0) {
		directions.push(
			`${requests} authorization requests were added. For each request, read the enclosing flag and the call chain. Decide whether it runs when the feature is reached or earlier. A root view can be the feature that needs authorization.`,
		);
	}
	return {
		hints: hints.slice(0, 40),
		metrics: {
			capabilitiesAdded: capabilities.length,
			authorizationRequests: requests,
			usageKeysAdded: keysInChange.size,
			usageKeysLocatedInCheckout: checkout.keys.size,
			projectFilesRead: checkout.filesRead,
			filesScanned: scan.filesScanned,
			linesAdded: scan.linesAdded,
		},
		directions,
	};
}
