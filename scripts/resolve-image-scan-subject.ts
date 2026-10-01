import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { reportStem, resolvePlatformDigest } from "./lib/image-scan.ts";

const outputFile = requiredEnv(process.env, "GITHUB_OUTPUT");
const platform = requiredEnv(process.env, "SCAN_PLATFORM");
if (platform !== "linux/amd64" && platform !== "linux/arm64") {
	throw new Error("SCAN_PLATFORM must be linux/amd64 or linux/arm64");
}
const reference = requiredEnv(process.env, "IMAGE_REF");
if (!/^[^\s@]+@sha256:[a-f0-9]{64}$/u.test(reference)) {
	throw new Error("IMAGE_REF must be an immutable image digest reference");
}
const image = requiredEnv(process.env, "INPUT_IMAGE_NAME").split("/").at(-1);
if (image === undefined || !/^[a-z0-9][a-z0-9._-]*$/u.test(image)) {
	throw new Error("INPUT_IMAGE_NAME must end in a valid image name");
}
const digest = await resolvePlatformDigest(reference, platform);
await appendFile(
	outputFile,
	`image=${image}\ndigest=${digest}\nreport-stem=${reportStem(image, platform)}\n`,
);
