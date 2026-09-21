// Precompute HINTS for declares-permissions-truthfully-at-point-of-use: the permission-bearing
// capabilities ADDED in Swift files, the authorization requests, and the usage-description keys present
// in the change or the checkout's Info.plist / project.yml. Pairing a capability with its key is a fact;
// whether the description is truthful and the request sits at the point of use is the review's.
import { readFile } from "node:fs/promises";
import path from "node:path";

import { globFilesSync } from "../lib/files.ts";
import { scanAddedLines, type SourcePattern } from "../lib/source-scan.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// capability label -> [pattern in Swift, usage keys that declare it]
const CAPABILITIES: readonly [string, RegExp, string[]][] = [
	[
		"CoreLocation",
		/\bimport CoreLocation\b|\bCLLocationManager\b/u,
		["NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription"],
	],
	[
		"Camera",
		/\bAVCaptureDevice\b|\bAVCaptureSession\b|\bUIImagePickerController\b|\.camera\b/u,
		["NSCameraUsageDescription"],
	],
	[
		"Microphone",
		/\bAVAudioRecorder\b|\bAVAudioSession\b.*record|\.record\(/u,
		["NSMicrophoneUsageDescription"],
	],
	[
		"PhotoLibrary",
		/\bPHPhotoLibrary\b|\bPHPickerViewController\b|\bPHAsset\b/u,
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

async function usageKeysInCheckout(repoPath: string): Promise<Map<string, string>> {
	const found = new Map<string, string>();
	const files = [
		...globFilesSync("**/Info.plist", repoPath),
		...globFilesSync("**/project.yml", repoPath),
		...globFilesSync("**/project.yaml", repoPath),
		...globFilesSync("**/*.xcodeproj/project.pbxproj", repoPath),
	].filter((f) => !/(?:^|\/)(?:Pods|\.build|DerivedData|Carthage)\//u.test(f));
	for (const file of files.slice(0, 20)) {
		let text: string;
		try {
			text = await readFile(path.join(repoPath, file), "utf8");
		} catch {
			continue;
		}
		for (const match of text.matchAll(USAGE_KEY)) {
			const key = match.groups?.key ?? "";
			if (!found.has(key)) {
				found.set(key, file);
			}
		}
	}
	return found;
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
	const keysInChange = new Map<string, { file: string; line: number }>();
	for (const [file, df] of diffFiles) {
		if (!/Info\.plist$|project\.ya?ml$|project\.pbxproj$/u.test(file)) {
			continue;
		}
		for (const [line, content] of df.addedLines) {
			for (const match of content.matchAll(USAGE_KEY)) {
				const key = match.groups?.key ?? "";
				keysInChange.set(key, { file, line });
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
	const keysInCheckout = await usageKeysInCheckout(repoPath);
	const capabilities = [
		...new Set(
			scan.hints
				.filter((h) => h.pattern.startsWith("capability "))
				.map((h) => h.pattern.slice("capability ".length)),
		),
	];
	const undeclared: string[] = [];
	for (const label of capabilities) {
		const keys = CAPABILITIES.find(([l]) => l === label)?.[2] ?? [];
		if (keys.length === 0) {
			continue;
		}
		if (!keys.some((k) => keysInChange.has(k) || keysInCheckout.has(k))) {
			undeclared.push(label);
		}
	}
	const directions: string[] = [];
	if (capabilities.length > 0) {
		directions.push(
			`Capabilities added: ${capabilities.join(", ")}. Usage keys in the change: ${[...keysInChange.keys()].join(", ") || "none"}; in the checkout: ${[...keysInCheckout.keys()].join(", ") || "none"}.`,
		);
	}
	if (undeclared.length > 0) {
		directions.push(
			`No usage key found for ${undeclared.join(", ")} in the change or the checkout's plist/project files — confirm by reading the target's build settings before deciding.`,
		);
	}
	const requests = scan.hints.filter((h) => h.pattern === "authorization request");
	if (requests.length > 0) {
		directions.push(
			`${requests.length} authorization request(s) added — for each, read the enclosing flag and the call chain to see whether it runs from the feature or from launch.`,
		);
	}
	return {
		hints: hints.slice(0, 40),
		metrics: {
			capabilitiesAdded: capabilities.length,
			capabilitiesWithoutUsageKey: undeclared.length,
			authorizationRequests: requests.length,
			usageKeysAdded: keysInChange.size,
			usageKeysInCheckout: keysInCheckout.size,
			filesScanned: scan.filesScanned,
			linesAdded: scan.linesAdded,
		},
		directions,
	};
}
