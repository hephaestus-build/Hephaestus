/**
 * The chart half of a release, rehearsed: chart-signing-rehearsal.yml packages the checked-out
 * chart with throwaway image values and pushes it through the release's own publishReleaseChart,
 * then signs and verifies it as the release and an operator do. It pushes only to a scratch
 * repository that no release uses, so it never writes a release chart, a release, a tag or a lock.
 */
import path from "node:path";

import type { ChartImage, ChartValues } from "./kubernetes-chart.ts";
import { type Execute, publishReleaseChart } from "./release-chart.ts";
import type { ReleaseChart } from "./release-image-lock.ts";

/** A GHCR namespace's rehearsal repository; never `<namespace>/charts/hephaestus`. */
const rehearsalRepository = /^ghcr\.io\/[a-z0-9][a-z0-9._-]*\/charts-rehearsal\/hephaestus$/u;
const commitPattern = /^[a-f0-9]{40}$/u;

/** A prerelease that no release can take: a release is never 0.0.0. */
export function rehearsalRelease(runId: string): string {
	if (!/^[1-9]\d*$/u.test(runId)) {
		throw new Error("the run id must be a positive number");
	}
	return `v0.0.0-rehearsal.${runId}`;
}

const image = (name: ChartImage) => `ghcr.io/hephaestus-build/${name}@sha256:${"0".repeat(64)}`;

/** Values that name no real image: the rehearsal proves signing, not an installation. */
export function rehearsalValues(release: string, commit: string): ChartValues {
	if (!commitPattern.test(commit)) {
		throw new Error("malformed source commit");
	}
	return {
		release: { version: release, commit },
		images: {
			"agent-pi": image("agent-pi"),
			"application-server": image("application-server"),
			nats: image("nats"),
			postgres: image("postgres"),
			webapp: image("webapp"),
		},
	};
}

export async function rehearseChart(
	runId: string,
	commit: string,
	repository: string,
	source: string,
	destination: string,
	run?: Execute,
): Promise<ReleaseChart> {
	if (!rehearsalRepository.test(repository)) {
		throw new Error(`${repository} is not a chart rehearsal repository`);
	}
	const release = rehearsalRelease(runId);
	const { chart } = await publishReleaseChart(
		release,
		source,
		rehearsalValues(release, commit),
		destination,
		run,
		repository,
	);
	return chart;
}

if (import.meta.main) {
	const [runId = "", commit = "", repository = "", destination = ""] = process.argv.slice(2);
	if (destination === "") {
		throw new Error("usage: rehearse-chart-signing <run-id> <commit> <repository> <directory>");
	}
	const chart = await rehearseChart(
		runId,
		commit,
		repository,
		path.join(import.meta.dirname, "..", "deploy/chart/hephaestus"),
		destination,
	);
	process.stdout.write(`${JSON.stringify(chart)}\n`);
}
