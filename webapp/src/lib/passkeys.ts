import { z } from "zod";

const descriptor = z.object({
	id: z.string(),
	type: z.literal("public-key"),
	transports: z.array(z.string()).optional(),
});
const creationSchema = z.object({
	challenge: z.string(),
	rp: z.object({ name: z.string(), id: z.string().optional() }),
	user: z.object({ id: z.string(), name: z.string(), displayName: z.string() }),
	pubKeyCredParams: z.array(z.object({ type: z.literal("public-key"), alg: z.number().int() })),
	timeout: z.number().optional(),
	excludeCredentials: z.array(descriptor).optional(),
	attestation: z.string().optional(),
	authenticatorSelection: z.object({
		residentKey: z.literal("required"),
		userVerification: z.literal("required"),
	}),
});
const requestSchema = z.object({
	challenge: z.string(),
	rpId: z.string(),
	timeout: z.number().optional(),
	allowCredentials: z.array(descriptor),
	userVerification: z.literal("required"),
});

export function passkeysSupported(): boolean {
	return (
		typeof PublicKeyCredential !== "undefined" &&
		typeof PublicKeyCredential.parseCreationOptionsFromJSON === "function" &&
		typeof PublicKeyCredential.parseRequestOptionsFromJSON === "function" &&
		typeof PublicKeyCredential.prototype.toJSON === "function"
	);
}
export async function createPasskey(optionsJson: string): Promise<string> {
	const options = creationSchema.parse(JSON.parse(optionsJson));
	const credential = await navigator.credentials.create({
		publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options),
	});
	if (!(credential instanceof PublicKeyCredential)) {
		throw new Error("Passkey creation did not complete");
	}
	return JSON.stringify(credential.toJSON());
}
export async function assertPasskey(optionsJson: string): Promise<string> {
	const options = requestSchema.parse(JSON.parse(optionsJson));
	const credential = await navigator.credentials.get({
		publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options),
	});
	if (!(credential instanceof PublicKeyCredential)) {
		throw new Error("Passkey verification did not complete");
	}
	return JSON.stringify(credential.toJSON());
}
