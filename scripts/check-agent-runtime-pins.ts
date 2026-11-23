import { readFileSync } from "node:fs";

import { parse } from "yaml";

import { asRecord, isRecord, parseJson } from "./lib/json.ts";

const dockerfile = readFileSync("docker/agents/pi/Dockerfile", "utf8");
const packageJson = asRecord(parseJson(readFileSync("package.json", "utf8")), "package.json");
const imagePackageJson = asRecord(
	parseJson(readFileSync("docker/agents/pi/package.json", "utf8")),
	"docker/agents/pi/package.json",
);
const devDependencies = isRecord(packageJson.devDependencies) ? packageJson.devDependencies : {};
const problems: string[] = [];

function dockerArg(name: string): string {
	const value = new RegExp(`^ARG ${name}=(?<value>\\S+)$`, "mu").exec(dockerfile)?.groups?.value;
	if (value === undefined) {
		problems.push(`docker/agents/pi/Dockerfile: missing ARG ${name}.`);
		return "";
	}
	return value;
}

const nodeVersion = dockerArg("NODE_VERSION");
const piVersion = dockerArg("PI_VERSION");
const devEngines = isRecord(packageJson.devEngines) ? packageJson.devEngines : {};
const devEnginesRuntime = isRecord(devEngines.runtime) ? devEngines.runtime : {};
const repoNodeVersion = devEnginesRuntime.version;
if (repoNodeVersion !== nodeVersion) {
	problems.push(
		`package.json#devEngines.runtime pins Node ${String(repoNodeVersion)} but the agent image pins ${nodeVersion}.`,
	);
}
const imageDependencies = isRecord(imagePackageJson.dependencies)
	? imagePackageJson.dependencies
	: {};
const imageLock = asRecord(
	parse(readFileSync("docker/agents/pi/pnpm-lock.yaml", "utf8")),
	"docker/agents/pi/pnpm-lock.yaml",
);
const lockImporters = isRecord(imageLock.importers) ? imageLock.importers : {};
const lockRoot = isRecord(lockImporters["."]) ? lockImporters["."] : {};
const lockDependencies = isRecord(lockRoot.dependencies) ? lockRoot.dependencies : {};
// The image's Pi version names both SDK packages: the coding agent, and the provider layer that the owned patch changes.
for (const name of ["@earendil-works/pi-coding-agent", "@earendil-works/pi-ai"]) {
	if (devDependencies[name] !== piVersion) {
		problems.push(
			`package.json pins ${name} ${String(devDependencies[name])} but the agent image pins Pi ${piVersion}.`,
		);
	}
	if (imageDependencies[name] !== piVersion) {
		problems.push(
			`docker/agents/pi/package.json pins ${name} ${String(imageDependencies[name])} but the agent image pins Pi ${piVersion}.`,
		);
	}
	const locked = isRecord(lockDependencies[name]) ? lockDependencies[name] : {};
	const lockedVersion = String(locked.version);
	if (
		locked.specifier !== piVersion ||
		(lockedVersion !== piVersion && !lockedVersion.startsWith(`${piVersion}(`))
	) {
		problems.push(
			`docker/agents/pi/pnpm-lock.yaml does not resolve the image's ${name} ${piVersion} dependency.`,
		);
	}
}

const nodeBase =
	/^FROM node:\$\{NODE_VERSION\}-trixie-slim@(?<digest>sha256:[a-f0-9]{64})(?: AS [\w-]+)?$/mu.exec(
		dockerfile,
	);
if (!nodeBase) {
	problems.push("docker/agents/pi/Dockerfile: Node base must be pinned by a 64-character digest.");
}

for (const file of ["scripts/capacity-fixture.ts", "scripts/capacity-bench/post-render.ts"]) {
	const image = /"node:(?<version>[\d.]+)-trixie-slim@(?<digest>sha256:[a-f0-9]{64})"/u.exec(
		readFileSync(file, "utf8"),
	)?.groups;
	if (image?.version !== nodeVersion || image.digest !== nodeBase?.groups?.digest) {
		problems.push(`${file}: Node version and base digest must match docker/agents/pi/Dockerfile.`);
	}
}

for (const marker of [
	"datasource=node-version depName=node",
	"datasource=npm depName=@earendil-works/pi-coding-agent",
]) {
	if (!dockerfile.includes(`# renovate: ${marker}`)) {
		problems.push(`docker/agents/pi/Dockerfile: missing Renovate marker '${marker}'.`);
	}
}

if (problems.length > 0) {
	for (const problem of problems) {
		console.error(`error: ${problem}`);
	}
	process.exit(1);
}

console.log(
	`Agent runtime pins agree: Node ${nodeVersion} (base and capacity pins agree); Pi ${piVersion} (image, TypeScript, and live tests).`,
);
