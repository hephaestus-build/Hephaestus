import { readFileSync } from "node:fs";
import path from "node:path";

import { asRecord, parseJson } from "./json.ts";

/**
 * The seconds each integration test class took, which Gradle partitions the numbered integration
 * shards by (docs/contributor/ci-cd.mdx § Integration shards). `shards` is the number of numbered
 * shards. The file is committed, so a run's partition changes only through a pull request.
 */
export interface Durations {
	shards: number;
	recordedAt: string;
	seconds: Record<string, number>;
}

export const COMMITTED = path.resolve(
	import.meta.dirname,
	"../../server/application/integration-test-durations.json",
);

export function parseDurations(value: unknown, label: string): Durations {
	const document = asRecord(value, label);
	const { shards, recordedAt } = document;
	if (typeof shards !== "number" || !Number.isSafeInteger(shards) || shards < 1) {
		throw new Error(`${label}: shards must be a positive integer`);
	}
	if (typeof recordedAt !== "string" || !Number.isFinite(Date.parse(recordedAt))) {
		throw new Error(`${label}: recordedAt must be a timestamp`);
	}
	const seconds: Record<string, number> = {};
	for (const [name, time] of Object.entries(asRecord(document.seconds, `${label} seconds`))) {
		if (typeof time !== "number" || !Number.isFinite(time) || time < 0) {
			throw new Error(`${label}: invalid seconds for ${name}`);
		}
		seconds[name] = time;
	}
	if (Object.keys(seconds).length === 0) {
		throw new Error(`${label}: no classes`);
	}
	return { shards, recordedAt, seconds };
}

const round = (seconds: number) => Math.round(seconds * 10) / 10;

/**
 * New durations from one run's measured seconds. A class that ran keeps the mean of its previous and
 * its measured seconds, so one slow run moves the partition only half way. A class that did not run
 * is gone.
 */
export function record(
	measured: ReadonlyMap<string, number>,
	previous: Durations | undefined,
	shards: number,
	now: Date,
): Durations {
	const seconds = Object.fromEntries(
		[...measured]
			.map(([name, value]): [string, number] => {
				const before = previous?.seconds[name];
				return [name, round(before === undefined ? value : (before + value) / 2)];
			})
			.toSorted(([a], [b]) => a.localeCompare(b)),
	);
	return { shards, recordedAt: now.toISOString(), seconds };
}

/** The number of numbered integration shards, which the CI matrix lists. */
export const committedShards = (): number =>
	parseDurations(parseJson(readFileSync(COMMITTED, "utf8")), COMMITTED).shards;
