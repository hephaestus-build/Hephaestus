import { createHash } from "node:crypto";

import type { ExtensionFactory, ModelRuntime } from "@earendil-works/pi-coding-agent";

import type { ProviderConfig } from "./pi-provider.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";

/** Keep review-local cache accounting separate from native conversation identity. */
export function assessmentCacheExtension(
	config: ProviderConfig,
	runtime: Pick<ModelRuntime, "getModel">,
	prefix: string,
	workspaceId: unknown,
	jobId: unknown,
	/** Character offsets from the brief builder, before the practice-specific suffix. */
	boundaries: readonly number[],
): ExtensionFactory {
	const known = runtime.getModel("openai", config.modelId ?? "");
	const compat = known?.compat;
	const supported =
		config.apiProtocol === "openai-responses" &&
		known?.api === "openai-responses" &&
		compat !== undefined &&
		"supportsExplicitPromptCacheMode" in compat &&
		compat.supportsExplicitPromptCacheMode === true;
	const identified =
		typeof workspaceId === "number" &&
		Number.isSafeInteger(workspaceId) &&
		workspaceId > 0 &&
		typeof jobId === "string" &&
		/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/iu.test(jobId);
	const key = identified
		? createHash("sha256")
				.update(JSON.stringify([workspaceId, jobId]))
				.digest("hex")
		: null;
	return (pi) => {
		if (!supported || key === null || prefix.length === 0) {
			return;
		}
		pi.on("before_provider_request", ({ payload }) => {
			if (
				!isRecord(payload) ||
				payload.model !== config.modelId ||
				typeof payload.prompt_cache_key !== "string" ||
				!Array.isArray(payload.input)
			) {
				return;
			}
			const index = payload.input.findIndex(
				(item: unknown) => isRecord(item) && item.role === "user",
			);
			const message: unknown = payload.input[index];
			if (!isRecord(message) || !Array.isArray(message.content) || message.content.length !== 1) {
				return;
			}
			const block: unknown = message.content[0];
			if (
				!isRecord(block) ||
				block.type !== "input_text" ||
				typeof block.text !== "string" ||
				!block.text.startsWith(prefix) ||
				block.text.length <= prefix.length ||
				"prompt_cache_breakpoint" in block
			) {
				return;
			}
			// System, change and core leave the fourth write slot for the implicit session cache.
			const layers = [...new Set(boundaries)]
				.filter((end) => Number.isSafeInteger(end) && end > 0 && end <= prefix.length)
				.toSorted((a, b) => a - b)
				.slice(0, 2);
			const ends = layers.length === 0 ? [prefix.length] : layers;
			let start = 0;
			const content = ends.map((end) => {
				const part = {
					...block,
					text: prefix.slice(start, end),
					prompt_cache_breakpoint: { mode: "explicit" },
				};
				start = end;
				return part;
			});
			const suffix = block.text.slice(start);
			return {
				...payload,
				prompt_cache_key: key,
				input: payload.input.map((item: unknown, at: number) => {
					if (at === index) {
						return { ...message, content: [...content, { ...block, text: suffix }] };
					}
					if (
						at === 0 &&
						isRecord(item) &&
						(item.role === "developer" || item.role === "system") &&
						typeof item.content === "string"
					) {
						return {
							...item,
							content: [
								{
									type: "input_text",
									text: item.content,
									prompt_cache_breakpoint: { mode: "explicit" },
								},
							],
						};
					}
					return item;
				}),
			};
		});
	};
}
