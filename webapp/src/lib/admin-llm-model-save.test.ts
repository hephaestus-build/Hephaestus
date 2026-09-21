import { describe, expect, it, vi } from "vitest";

import { saveAdminLlmModelSafely } from "./admin-llm-model-save";

const body = {
	metadata: {
		displayName: "GPT-5",
		upstreamModelId: "gpt-5",
		enabled: true,
	},
	price: { pricingMode: "PRICED" as const, per1mInputUsd: 1, per1mOutputUsd: 2 },
	sharing: { visibility: "PUBLIC" as const },
};

function operations(order: string[]) {
	return {
		create: vi.fn(async () => {
			order.push("create");
			return { id: 42 };
		}),
		updateMetadata: vi.fn(async (_id: number, metadata: { enabled?: boolean }) => {
			order.push(metadata.enabled === true ? "activate" : "metadata");
		}),
		updatePrice: vi.fn(async () => {
			order.push("price");
		}),
		updateSharing: vi.fn(async () => {
			order.push("sharing");
		}),
	};
}

describe("saveAdminLlmModelSafely", () => {
	it("creates inactive, then prices and shares, then activates", async () => {
		const order: string[] = [];
		await saveAdminLlmModelSafely({
			connectionId: 7,
			editing: null,
			body,
			operations: operations(order),
		});
		expect(order).toStrictEqual(["create", "price", "sharing", "activate"]);
	});

	it("revokes an active model before changing its price", async () => {
		const order: string[] = [];
		await saveAdminLlmModelSafely({
			connectionId: 7,
			editing: { id: 42, enabled: true },
			body: { ...body, metadata: { ...body.metadata, enabled: false } },
			operations: operations(order),
		});
		expect(order).toStrictEqual(["metadata", "price"]);
	});

	it("temporarily disables an active model while updating it", async () => {
		const order: string[] = [];
		await saveAdminLlmModelSafely({
			connectionId: 7,
			editing: { id: 42, enabled: true },
			body,
			operations: operations(order),
		});
		expect(order).toStrictEqual(["metadata", "price", "activate"]);
	});

	it("does not activate when pricing fails", async () => {
		const order: string[] = [];
		const ops = operations(order);
		ops.updatePrice.mockRejectedValue(new Error("pricing failed"));
		await expect(
			saveAdminLlmModelSafely({ connectionId: 7, editing: null, body, operations: ops }),
		).rejects.toThrow("pricing failed");
		expect(order).toStrictEqual(["create"]);
	});
});
