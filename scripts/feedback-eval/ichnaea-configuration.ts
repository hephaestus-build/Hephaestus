/**
 * Writes the system configuration of one arm (`ichnaea.configuration/1`): the composer's model and settings, the
 * digests of the prompt files it reads, and the commit. Ichnaea hashes it into the arm's identity, and refuses a code
 * change whose arms run different models.
 * scripts/feedback-eval-configuration.ts writes it for a checkout.
 * It reads HEPHAESTUS_LIVE_LLM_MODEL. The composer settings are the panel settings in the checkout's own `gate.json`,
 * which ichnaea-compose.ts passes to that arm's composer.
 */
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { parseGateConfig } from "./gate.ts";

/** The prompt files the composer reads. A change to any of them is a prompt change. */
export const PROMPT_FILES = [
	"server/application/src/main/resources/agent/review-composer.md",
	"server/application/src/main/resources/agent/feedback-composer.md",
	"server/application/src/main/resources/agent/feedback-style.md",
	"docs/contributor/practice-feedback-language.mdx",
] as const;

/** The eval's composer settings: reasoning effort and output tokens. A change to it changes the parameters below. */
export const GATE_FILE = "scripts/feedback-eval/gate.json";

/** The parameters that the composer settings in `gate.json` give, by their names in the configuration. */
export const GATE_PARAMETERS = {
	reasoningEffort: "composer.reasoningEffort",
	maxOutputTokens: "composer.maxOutputTokens",
} as const;

/**
 * The composer settings of the arm at `root`: the panel settings in that checkout's `gate.json`. Each arm reads its own
 * file, so a change to the settings reaches only the candidate's compositions and recorded configuration.
 */
export function composerSettings(root: string): {
	reasoningEffort: string;
	maxOutputTokens: number;
	/** The composer's bound of model calls for one case. */
	callsPerCase: number;
} {
	const file = path.join(root, GATE_FILE);
	if (!existsSync(file)) {
		throw new Error(`${root} has no ${GATE_FILE}, so its composer settings are unknown`);
	}
	const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
	const gate = parseGateConfig(parsed);
	return {
		reasoningEffort: gate.panel.reasoningEffort,
		maxOutputTokens: gate.panel.maxOutputTokens,
		callsPerCase: gate.panel.callsPerCase,
	};
}

const nonEmpty = (value: string | undefined): string | undefined =>
	value === undefined || value === "" ? undefined : value;

/** The composer's configuration at a checkout. Each prompt's digest is the SHA-256 of the file's bytes. */
export function composerConfiguration(
	root: string,
	env: NodeJS.ProcessEnv,
	commit: string,
): Record<string, unknown> {
	const digest = (file: string): string =>
		`sha256:${createHash("sha256")
			.update(readFileSync(path.join(root, file)))
			.digest("hex")}`;
	const settings = composerSettings(root);
	return {
		schema: "ichnaea.configuration/1",
		description:
			"The Hephaestus feedback composer, run by CompositionEvalLiveTest against the CI model connection.",
		models: [
			{
				role: "composer",
				provider: { value: "ci-model-connection", status: "declared" },
				id: { value: nonEmpty(env.HEPHAESTUS_LIVE_LLM_MODEL) ?? "unset", status: "declared" },
				// The endpoint can name a private host, so the configuration keeps only its digest.
				route: {
					value: `sha256:${createHash("sha256")
						.update((env.HEPHAESTUS_LIVE_LLM_BASE_URL ?? "").replace(/\/+$/u, ""))
						.digest("hex")}`,
					status: "declared",
				},
			},
		],
		parameters: [
			{
				name: GATE_PARAMETERS.reasoningEffort,
				value: settings.reasoningEffort,
				status: "pinned",
				origin: "explicit",
			},
			{
				name: GATE_PARAMETERS.maxOutputTokens,
				value: settings.maxOutputTokens,
				status: "pinned",
				origin: "explicit",
			},
		],
		prompts: PROMPT_FILES.map((file) => ({ name: file, digest: digest(file), status: "pinned" })),
		factors: { "code.commit": { value: commit, status: "pinned", part: "code" } },
	};
}
