import { fileURLToPath } from "node:url";

import { run } from "./lib/process.ts";

// promtool from the Prometheus release image, pinned by its index digest: one pin for CI, Linux and
// macOS, and no binary to install.
const prometheusImage =
	"prom/prometheus:v3.15.1-distroless@sha256:451a845bae1d51622b41af1780eb2ad911bbb79a9855bc8e9bb67fabbc03f934";
const rules = fileURLToPath(new URL("../deploy/observability", import.meta.url));
const promtool = [
	"run",
	"--rm",
	"--network=none",
	"-v",
	`${rules}:/rules:ro`,
	"--entrypoint",
	"promtool",
	prometheusImage,
];

await run("docker", [...promtool, "check", "rules", "/rules/hephaestus-alerts.yml"]);
await run("docker", [...promtool, "test", "rules", "/rules/hephaestus-alerts.test.yml"]);
