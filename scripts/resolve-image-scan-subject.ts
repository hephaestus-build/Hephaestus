import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { PLATFORM, resolvePlatformDigest } from "./lib/image-scan.ts";

const outputFile = requiredEnv(process.env, "GITHUB_OUTPUT");
const reference = requiredEnv(process.env, "IMAGE_REF");
if (!/^[^\s@]+@sha256:[a-f0-9]{64}$/.test(reference))
	throw new Error("IMAGE_REF must be an immutable image digest reference");
const image = requiredEnv(process.env, "INPUT_IMAGE_NAME").split("/").at(-1);
if (!image || !/^[a-z0-9][a-z0-9._-]*$/.test(image))
	throw new Error("INPUT_IMAGE_NAME must end in a valid image name");
const digest = await resolvePlatformDigest(reference, PLATFORM);
await appendFile(outputFile, `image=${image}\ndigest=${digest}\n`);
