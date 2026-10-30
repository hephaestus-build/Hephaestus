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
	if (config.apiProtocol === "anthropic-messages") {
		return (pi) => {
			pi.on("before_provider_request", ({ payload }) => {
				if (
					!isRecord(payload) ||
					payload.model !== config.modelId ||
					!Array.isArray(payload.messages)
				) {
					return;
				}
				return anthropicCache(payload, prefix, boundaries);
			});
		};
	}
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

/** The system covers tools, leaving two stable work boundaries and the SDK's moving session slot. */
function anthropicCache(
	payload: Record<string, unknown>,
	prefix: string,
	boundaries: readonly number[],
) {
	const cache = { type: "ephemeral", ttl: "5m" };
	const tools = Array.isArray(payload.tools) ? payload.tools.map(withoutCache) : payload.tools;
	const systemBlocks = payload.system;
	const system = Array.isArray(systemBlocks)
		? systemBlocks.map((block: unknown, at: number) => {
				const clean = withoutCache(block);
				return isRecord(clean) && at === systemBlocks.length - 1
					? { ...clean, cache_control: cache }
					: clean;
			})
		: payload.system;
	const messages = Array.isArray(payload.messages)
		? payload.messages.map((message: unknown) => {
				if (!isRecord(message) || !Array.isArray(message.content)) {
					return message;
				}
				const content = message.content.map((block: unknown) =>
					isRecord(block) && "cache_control" in block ? { ...block, cache_control: cache } : block,
				);
				const block: unknown = content[0];
				if (
					message.role !== "user" ||
					content.length !== 1 ||
					!isRecord(block) ||
					block.type !== "text" ||
					typeof block.text !== "string" ||
					prefix.length === 0 ||
					!block.text.startsWith(prefix) ||
					block.text.length <= prefix.length
				) {
					return { ...message, content };
				}
				const ends = [...new Set(boundaries)]
					.filter((end) => Number.isSafeInteger(end) && end > 0 && end <= prefix.length)
					.toSorted((a, b) => a - b)
					.slice(0, 2);
				if (ends.length === 0) {
					ends.push(prefix.length);
				}
				let start = 0;
				const { text } = block;
				const layers = ends.map((end) => {
					const part = { type: "text", text: text.slice(start, end), cache_control: cache };
					start = end;
					return part;
				});
				return { ...message, content: [...layers, { ...block, text: block.text.slice(start) }] };
			})
		: payload.messages;
	return { ...payload, tools, system, messages };
}

function withoutCache(value: unknown) {
	if (!isRecord(value)) {
		return value;
	}
	const copy = { ...value };
	delete copy.cache_control;
	return copy;
}
