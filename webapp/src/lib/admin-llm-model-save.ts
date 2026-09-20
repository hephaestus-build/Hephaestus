import type {
	CreateLlmModelRequest,
	UpdateLlmModelPriceRequest,
	UpdateLlmModelRequest,
	UpdateLlmModelSharingRequest,
} from "@/api/types.gen";

export interface AdminLlmModelSaveBody {
	metadata: CreateLlmModelRequest | UpdateLlmModelRequest;
	price: UpdateLlmModelPriceRequest;
	/** Creation only; an existing model's access is changed through the dedicated access flow. */
	sharing?: UpdateLlmModelSharingRequest;
}

interface ModelSaveOperations {
	create: (connectionId: number, body: CreateLlmModelRequest) => Promise<{ id: number }>;
	updateMetadata: (id: number, body: UpdateLlmModelRequest) => Promise<unknown>;
	updatePrice: (id: number, body: UpdateLlmModelPriceRequest) => Promise<unknown>;
	updateSharing: (id: number, body: UpdateLlmModelSharingRequest) => Promise<unknown>;
}

interface SaveAdminLlmModelOptions {
	connectionId: number;
	editing: { id: number; enabled: boolean } | null;
	body: AdminLlmModelSaveBody;
	operations: ModelSaveOperations;
}

export class AdminLlmModelSaveError extends Error {
	readonly modelId?: number;

	constructor(error: unknown, modelId?: number) {
		super(error instanceof Error ? error.message : "Could not save the model", { cause: error });
		this.name = "AdminLlmModelSaveError";
		this.modelId = modelId;
	}
}

/**
 * Sequences the catalog's dedicated endpoints so a model is never live while half-configured: a new
 * one is created inactive, and an active one is disabled before anything else about it changes.
 */
export async function saveAdminLlmModelSafely({
	connectionId,
	editing,
	body,
	operations,
}: SaveAdminLlmModelOptions): Promise<void> {
	let modelId: number | undefined;
	const { metadata } = body;
	const shouldEnable = metadata.enabled === true;

	try {
		if (editing) {
			modelId = editing.id;
			if (editing.enabled) {
				await operations.updateMetadata(editing.id, shouldEnable ? { enabled: false } : metadata);
			}
		} else {
			if (!body.sharing) {
				throw new Error("Workspace access is required when creating a model");
			}
			// Only the create shape names an upstream model, and the catalog cannot create without one.
			if (!("upstreamModelId" in metadata)) {
				throw new Error("The upstream model id is required when creating a model");
			}
			const created = await operations.create(connectionId, { ...metadata, enabled: false });
			modelId = created.id;
		}

		await operations.updatePrice(modelId, body.price);
		if (!editing && body.sharing) {
			await operations.updateSharing(modelId, body.sharing);
		}

		if (!editing && shouldEnable) {
			await operations.updateMetadata(modelId, { enabled: true });
		} else if (editing && (!editing.enabled || shouldEnable)) {
			await operations.updateMetadata(editing.id, metadata);
		}
	} catch (error) {
		if (modelId == null) {
			throw error;
		}
		throw new AdminLlmModelSaveError(error, modelId);
	}
}
