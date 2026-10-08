/**
 * Records a promotion as a GitHub deployment of the channel it signs.
 *
 * A job's own environment deployment names the workflow's ref and head commit, which is `main` for
 * every promotion, so `promote.yml` turns that record off and this script records the release or
 * commit the channel names. It runs before the channel is published, so a promotion that fails
 * still leaves its record.
 */
import { appendFile } from "node:fs/promises";

import { requiredEnv, requiredPositiveInteger } from "./lib/env.ts";
import { githubPost } from "./lib/github.ts";
import { asRecord, readJsonFile } from "./lib/json.ts";
import { parseChannel, RELEASE_TAG, type Channel } from "./reconcile-deployment.ts";
import { promotionQualifiers } from "./resolve-promotion.ts";

/** The deployment to record for a channel, or nothing for a hold, which moves no host. */
export function deploymentRequest(channel: Channel, environment: string, requestedBy: string) {
	if (channel.freeze === true) {
		return;
	}
	const { release } = channel;
	const isRelease = RELEASE_TAG.test(release);
	return {
		ref: release,
		task: "deploy",
		// Record the ref as it is; the default first merges the default branch into it.
		auto_merge: false,
		// The default requires every commit status on the ref to pass. CI gated the source already.
		required_contexts: [],
		environment,
		description: [
			isRelease ? release : `commit ${release.slice(0, 12)}`,
			...promotionQualifiers(channel),
		].join(" · "),
		payload: {
			...(isRelease ? { release } : { commit: release }),
			allow_rollback: channel.allowRollback === true,
			refresh_database_image: channel.refreshDatabaseImage === true,
			requested_by: requestedBy,
		},
		// GitHub infers this only for an environment named exactly `production`.
		production_environment: environment === "Production",
	};
}

/** The final status claims only what the workflow observed: the public webapp's version. */
export function deploymentOutcome(published: string, observed: string, version: string) {
	if (published !== "success") {
		return {
			state: "error",
			description: "The workflow did not confirm that it published the signed channel.",
		};
	}
	if (observed !== "success") {
		return {
			state: "failure",
			description: `The public webapp did not report ${version} (check outcome: ${observed}).`,
		};
	}
	return {
		state: "success",
		description: `The public webapp reports ${version}. Host metrics report the other services.`,
	};
}

if (import.meta.main) {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const environmentUrl = requiredEnv(process.env, "ENVIRONMENT_URL");
	const logUrl = requiredEnv(process.env, "RUN_URL");
	const status = async (id: number, state: string, description: string) =>
		githubPost(`repos/${repository}/deployments/${id}/statuses`, {
			state,
			description,
			environment_url: environmentUrl,
			log_url: logUrl,
		});

	if (process.argv[2] === "start") {
		const channel = parseChannel(await readJsonFile(requiredEnv(process.env, "CHANNEL_FILE")));
		const request = deploymentRequest(
			channel,
			requiredEnv(process.env, "ENVIRONMENT"),
			requiredEnv(process.env, "REQUESTED_BY"),
		);
		if (request !== undefined) {
			const { id } = asRecord(
				await githubPost(`repos/${repository}/deployments`, request),
				"deployment",
			);
			if (typeof id !== "number") {
				throw new TypeError("GitHub returned a deployment without an id");
			}
			await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `id=${id}\n`);
			await status(id, "in_progress", "Publishing the signed channel.");
		}
	} else if (process.argv[2] === "finish") {
		const { state, description } = deploymentOutcome(
			requiredEnv(process.env, "PUBLISHED"),
			requiredEnv(process.env, "OBSERVED"),
			requiredEnv(process.env, "VERSION"),
		);
		await status(requiredPositiveInteger(process.env, "DEPLOYMENT_ID"), state, description);
	} else {
		throw new Error("Usage: record-deployment.ts start|finish");
	}
}
