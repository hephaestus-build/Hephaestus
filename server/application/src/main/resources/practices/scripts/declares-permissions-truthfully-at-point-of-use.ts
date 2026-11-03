import { definePracticeScript, listWithin, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm, type FileTree } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

const PERMISSION_REFERENCES: readonly [string, RegExp, string[]][] = [
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

const REQUEST: readonly [string, RegExp][] = [
	[
		"authorization request",
		/\brequest(?:WhenInUse|Always)?Authorization\b|\brequestAccess\b|\brequestTrackingAuthorization\b|\bPHPhotoLibrary\.requestAuthorization\b/u,
	],
	...PERMISSION_REFERENCES.map(([kind, re]): [string, RegExp] => [
		`permission-related reference ${kind}`,
		re,
	]),
];

function referenceKind(kind: string): string {
	return `permission-reference-${kind.replaceAll(/(?<=[a-z])(?=[A-Z])/gu, "-").toLowerCase()}`;
}

const KIND_OF_LABEL = new Map<string, string>([
	["authorization request", "authorization-request"],
	...PERMISSION_REFERENCES.map(([kind]): [string, string] => [
		`permission-related reference ${kind}`,
		referenceKind(kind),
	]),
]);

export const LABELS = {
	"authorization-request": "An added call that requests an authorization from the user.",
	"usage-key-added": "An added line of a plist or project file that holds a usage-description key.",
	...Object.fromEntries(
		PERMISSION_REFERENCES.map(([kind]) => [
			referenceKind(kind),
			`An added reference to a ${kind} type or call, to inspect for the permission that it needs.`,
		]),
	),
};

const LABEL_OF = new Map<string, string>(Object.entries(LABELS));

const USAGE_KEY = /\b(?<key>NS\w+UsageDescription)\b/gu;

// Reserve four of the ten direction slots for other context.
const REFERENCE_LINES = 5;

const PROJECT_FILES_READ = 20;

interface CheckoutKeys {
	keys: Map<string, string[]>;
	filesFound: number;
	filesRead: number;
	unreadable: number;
	listingCut: boolean;
}

const PROJECT_FILES = [
	"**/Info.plist",
	"**/project.yml",
	"**/project.yaml",
	"**/*.xcodeproj/project.pbxproj",
];

async function usageKeysInCheckout(tree: FileTree | null): Promise<CheckoutKeys | null> {
	if (tree === null) {
		return null;
	}
	const keys = new Map<string, string[]>();
	let files: string[] = [];
	let listingCut = false;
	for (const glob of PROJECT_FILES) {
		const listed = await tree.list(glob);
		files.push(...listed.paths);
		listingCut ||= listed.truncated;
	}
	files = files.filter((f) => !/(?:^|\/)(?:Pods|\.build|DerivedData|Carthage)\//u.test(f));
	let unreadable = 0;
	const read = files.slice(0, PROJECT_FILES_READ);
	for (const file of read) {
		const found = await tree.read(file);
		if (found.status !== "FOUND") {
			unreadable += 1;
			continue;
		}
		for (const match of found.text.matchAll(USAGE_KEY)) {
			const key = match.groups?.key ?? "";
			keys.set(key, [...new Set([...(keys.get(key) ?? []), file])]);
		}
	}
	return {
		keys,
		filesFound: files.length,
		filesRead: read.length - unreadable,
		unreadable,
		listingCut,
	};
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const tree = work.head;
		const found = await work.sites(
			{ swift: REQUEST.map(([kind, test]) => ({ kind, label: kind, test })) },
			{ overlap: "first", enclosing: {} },
		);
		const leads: ReviewLead[] = found.listed.flatMap((site) => {
			const kind = KIND_OF_LABEL.get(site.kind);
			const label = kind === undefined ? undefined : LABEL_OF.get(kind);
			return label === undefined
				? []
				: [{ at: { change: site.path, line: site.line }, label, facts: site.facts }];
		});
		const keysInChange = new Map<string, string>();
		for (const [file, df] of change) {
			if (!/Info\.plist$|project\.ya?ml$|project\.pbxproj$/u.test(file)) {
				continue;
			}
			for (const [line, content] of df.addedLines) {
				for (const match of content.matchAll(USAGE_KEY)) {
					const key = match.groups?.key ?? "";
					keysInChange.set(key, file);
					leads.push({
						at: { change: file, line },
						label: LABELS["usage-key-added"],
						facts: { key },
					});
				}
			}
		}
		const checkout = await usageKeysInCheckout(tree);
		const references = PERMISSION_REFERENCES.filter(([kind]) =>
			found.counts.has(`permission-related reference ${kind}`),
		);
		const located = (keys: string[]) =>
			keys.flatMap((key) => {
				const inChange = keysInChange.get(key);
				const where = [
					...new Set([
						...(inChange === undefined ? [] : [inChange]),
						...(checkout?.keys.get(key) ?? []),
					]),
				];
				return where.length === 0 ? [] : [`${key} (${where.join(", ")})`];
			});
		const directions: string[] = [...found.listedNote];
		if (references.length > 0) {
			const read =
				checkout === null
					? "not the checkout, which was not captured"
					: `${String(checkout.filesRead)} of ${String(checkout.filesFound)}${checkout.listingCut ? " or more" : ""} plist/project file(s) read${checkout.unreadable > 0 ? `, ${String(checkout.unreadable)} unreadable` : ""}`;
			const lines = references.map(([kind, , keys]) => {
				if (keys.length === 0) {
					return `${kind} (no usage key applies).`;
				}
				const locatedKeys = located(keys);
				return `${kind} — keys located: ${locatedKeys.length > 0 ? locatedKeys.join("; ") : "none"}.`;
			});
			const others = references.slice(REFERENCE_LINES - 1).map(([kind]) => kind);
			directions.push(
				`Permission-related references to inspect, with the usage keys located. Searched the change and ${read}.`,
				...(lines.length > REFERENCE_LINES
					? [
							...lines.slice(0, REFERENCE_LINES - 1),
							`Also referenced: ${listWithin(others, DIRECTION_CHARS - 40)}.`,
						]
					: lines),
				"A key located in a file does not show the target declares it, and a key not located does not show it is missing: read the target's own Info.plist or build settings, and the key the actual API and deployment target require.",
			);
		}
		const requests = found.counts.get("authorization request") ?? 0;
		if (requests > 0) {
			directions.push(
				`${requests} authorization requests were added. For each request, read the enclosing flag and the call chain. Decide whether it runs when the feature is reached or earlier. A root view can be the feature that needs authorization.`,
			);
		}
		if (leads.length === 0) {
			directions.push(...found.noMatch);
		}
		return ctx.review({
			leads: leads.slice(0, 40),
			facts: {
				permissionReferenceKinds: references.length,
				authorizationRequests: requests,
				usageKeysAdded: keysInChange.size,
				...(checkout === null
					? {}
					: {
							usageKeysLocatedInCheckout: checkout.keys.size,
							projectFilesRead: checkout.filesRead,
						}),
				filesScanned: found.filesScanned,
				linesAdded: found.linesSelected,
			},
			directions,
		});
	},
});
