# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.22.1
FROM ghcr.io/pnpm/pnpm:12.3.4@sha256:b81d53184f670fe19d1a33f9d5041907d314b31d596838e8133cbd83d45be043 AS dependencies
ARG NODE_VERSION
WORKDIR /opt/pi-sdk
RUN pnpm runtime set node ${NODE_VERSION} -g
COPY pi/package.json pi/pnpm-lock.yaml pi/pnpm-workspace.yaml ./
COPY pi/patches/ ./patches/
RUN pnpm install --prod --frozen-lockfile --ignore-scripts

WORKDIR /opt/practice-scripts
COPY practice-scripts/package.json practice-scripts/pnpm-lock.yaml practice-scripts/pnpm-workspace.yaml ./
RUN pnpm install --prod --frozen-lockfile --ignore-scripts

# The gateway runs as JavaScript, so no sandbox process loads Node's TypeScript loader for it.
FROM dependencies AS gateway
WORKDIR /opt/pi-sdk
RUN pnpm install --frozen-lockfile --ignore-scripts
COPY pi/tsconfig.build.json pi/gateway-capabilities.ts pi/gateway-init.ts pi/gateway-run.ts ./
RUN pnpm exec tsc -p tsconfig.build.json

# The conversation loop runs as JavaScript in its own Deployment (deploy/chart/hephaestus/templates/conversation-loop.yaml).
# The bake target passes its sources as the agent-sources context: the loop's files and every module they import,
# transitively. Only the practice toolkit's JSON reader comes from docker/agents.
FROM gateway AS conversation-loop
WORKDIR /opt/conversation-loop-src
COPY --from=agent-sources \
    conversation-loop.ts conversation-loop-http.ts conversation-loop-protocol.ts conversation-loop-pull.ts \
    conversation-loop-tools.ts conversation-loop-tools-http.ts \
    pi-agent-sandbox.ts pi-diagnostics.ts pi-diagnostics.json pi-error-text.ts pi-provider.ts pi-text.ts \
    tool-client.ts tool-protocol.ts ./
COPY practice-scripts/lib/core/json.ts ./work/practice-scripts/lib/core/json.ts
# Emitted as ES modules like the gateway (tsconfig.build.json): CI type-checks these sources (tsconfig.node.json).
COPY <<'EOF' package.json
{ "type": "module" }
EOF
COPY <<'EOF' tsconfig.json
{
	"extends": "/opt/pi-sdk/tsconfig.build.json",
	"compilerOptions": { "sourceRoot": "/opt/conversation-loop", "outDir": "/opt/conversation-loop" },
	"files": [
		"conversation-loop.ts", "conversation-loop-http.ts", "conversation-loop-protocol.ts", "conversation-loop-pull.ts",
		"conversation-loop-tools.ts", "conversation-loop-tools-http.ts"
	]
}
EOF
RUN /opt/pi-sdk/node_modules/.bin/tsc -p tsconfig.json && cp package.json pi-diagnostics.json /opt/conversation-loop/

# Debian 13: its fd (>= 8.7) takes the `--no-require-git` Pi's find tool passes outside a repository.
FROM node:${NODE_VERSION}-trixie-slim@sha256:89c5244bc22a9bc3287d89ebc7e49b9cb5bb84cd40e6175ac3421d5a9f68b95a AS os-packages
RUN apt-get update -o APT::Update::Error-Mode=any -qq && apt-get upgrade -y -qq && \
    apt-get install -y --no-install-recommends bash git ripgrep fd-find findutils tree jq ca-certificates grep coreutils util-linux tar && \
    rm -rf /var/lib/apt/lists/*

FROM os-packages AS runtime
ARG NODE_VERSION

RUN groupmod --new-name agent node && \
    usermod --login agent --home /home/agent --move-home --shell /bin/bash node

# renovate: datasource=npm depName=@earendil-works/pi-coding-agent
ARG PI_VERSION=1.0.0
COPY --from=dependencies /opt/pi-sdk /opt/pi-sdk
RUN test -d /opt/pi-sdk/node_modules/@earendil-works/pi-coding-agent \
      || (echo "Pi SDK not found at /opt/pi-sdk/node_modules" >&2; exit 1) && \
    mkdir -p /workspace && chown 0:0 /workspace && chmod 1777 /workspace

RUN mkdir -p /tmp/abi-check && ln -sf /opt/pi-sdk/node_modules /tmp/abi-check/node_modules && \
    printf 'const sdk = await import("@earendil-works/pi-coding-agent");\nif (!sdk || typeof sdk !== "object") { throw new Error("Pi SDK import yielded no module namespace"); }\nconsole.log("pi sdk exports:", Object.keys(sdk).length);\n' > /tmp/abi-check/check.ts && \
    node --permission --allow-fs-read=/tmp/abi-check --allow-fs-read=/opt/pi-sdk /tmp/abi-check/check.ts && \
    mkdir -p /tmp/abi-check/work/nested /tmp/abi-check/agent /tmp/abi-check/home && \
    printf 'SENTINEL-ORCHESTRATOR\n' > /tmp/abi-check/agent/AGENTS.md && \
    printf '{}\n' > /tmp/abi-check/agent/settings.json && \
    printf 'DECOY\n' > /tmp/abi-check/work/AGENTS.md && \
    printf '%s\n' \
      'import { readFileSync } from "node:fs";' \
      'import { DefaultResourceLoader, ModelRuntime, SettingsManager } from "@earendil-works/pi-coding-agent";' \
      'const cwd = "/tmp/abi-check/work/nested";' \
      'const agentDir = "/tmp/abi-check/agent";' \
      '// Resolves resources exactly the way the runner does, under the same permissions: an untrusted' \
      '// project so the SDK never walks the ancestors of the working directory for skills, discovery of' \
      '// context files off, and the staged orchestrator handed over by name. The working directory sits' \
      '// below ancestors outside the allowlist and beside a decoy AGENTS.md: an SDK that reaches out fails' \
      '// the build, so does one that discovers the decoy, and so does a loader that carries anything but' \
      '// the staged orchestrator.' \
      'const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });' \
      'const orchestratorPath = `${agentDir}/AGENTS.md`;' \
      'const orchestrator = readFileSync(orchestratorPath, "utf8");' \
      'const loader = new DefaultResourceLoader({ cwd, agentDir, settingsManager, noContextFiles: true, agentsFilesOverride: (base) => {' \
      '  // The decoy must not be in the base list: an SDK that discovers context files with discovery off is the regression this gate exists for.' \
      '  if (base.agentsFiles.length !== 0) { throw new Error(`context files were discovered with discovery off: ${JSON.stringify(base.agentsFiles.map((f) => f.path))}`); }' \
      '  return { agentsFiles: [{ path: orchestratorPath, content: orchestrator }] };' \
      '} });' \
      'await loader.reload();' \
      'const { agentsFiles } = loader.getAgentsFiles();' \
      'if (agentsFiles.length !== 1 || !agentsFiles[0].content.includes("SENTINEL-ORCHESTRATOR")) { throw new Error(`expected the staged orchestrator alone, got ${JSON.stringify(agentsFiles.map((f) => f.path))}`); }' \
      '// Checking a credential takes a lock directory beside auth.json, which the SDK creates on first' \
      '// use along with its models store: writes in the agent dir that the runner grants and that a' \
      '// model turn needs. Only settings.json is staged, as the server stages it.' \
      'const modelRuntime = await ModelRuntime.create({ authPath: `${agentDir}/auth.json`, modelsPath: `${agentDir}/models.json`, allowModelNetwork: false });' \
      'modelRuntime.registerProvider("gate", { name: "gate", baseUrl: "http://127.0.0.1:9/v1", apiKey: "$GATE_TOKEN", authHeader: true, api: "openai-responses", models: [{ id: "model", name: "model", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 1000, maxTokens: 100 }] });' \
      'if (!(await modelRuntime.checkAuth("gate"))) { throw new Error("the credential of the gate provider did not resolve"); }' \
      > /tmp/abi-check/runner-path-check.ts && \
    HOME=/tmp/abi-check/home GATE_TOKEN=gate node --permission --allow-fs-read=/tmp/abi-check --allow-fs-read=/opt/pi-sdk --allow-fs-write=/tmp/abi-check/agent /tmp/abi-check/runner-path-check.ts && \
    ! node --permission --allow-fs-read=/tmp/abi-check --allow-fs-read=/opt/pi-sdk \
      -e 'require("node:fs").readFileSync("/etc/passwd")' 2>/dev/null && \
    # A write grant on a directory absent at startup covers its mkdir but nothing inside it; the
    # server therefore creates every writable directory before Node starts, and this build fails
    # the day Node changes that, so the pre-creation can be revisited rather than cargo-culted.
    printf '%s\n' \
      'const fs = require("node:fs");' \
      'const dir = process.argv[2];' \
      'fs.mkdirSync(dir);' \
      'let denied = false;' \
      'try { fs.writeFileSync(`${dir}/x`, "1"); } catch (error) { denied = error.code === "ERR_ACCESS_DENIED"; }' \
      'if (!denied) { throw new Error("a write inside a directory granted before it existed was allowed; revisit the pre-creation in PiRuntimeFactory"); }' \
      > /tmp/abi-check/absent-grant-check.cjs && \
    node --permission --allow-fs-read=/tmp/abi-check --allow-fs-write=/tmp/abi-check/absent /tmp/abi-check/absent-grant-check.cjs /tmp/abi-check/absent && \
    ! node --permission --allow-fs-read=/tmp/abi-check --allow-fs-read=/opt/pi-sdk \
      -e 'require("node:child_process").spawnSync("true")' 2>/dev/null && \
    rm -rf /tmp/abi-check

COPY --from=dependencies /opt/practice-scripts/node_modules /opt/practice-scripts/node_modules
COPY practice-scripts/package.json practice-scripts/child.ts /opt/practice-scripts/
COPY practice-scripts/lib/ /opt/practice-scripts/lib/
COPY practice-scripts/kits/ /opt/practice-scripts/kits/

# The runner imports the practice program host through a link the runtime creates before Node starts
# (`PracticeRunnerProfile.setupCommands`): `/workspace/work/practice-scripts/lib` → `/opt/practice-scripts/lib`.
# Resolving it here, as /workspace/.run-pi.ts does, proves the toolkit and its own dependencies load beside the
# Pi SDK, and that a program's process starts under the permissions the host gives it.
RUN mkdir -p /tmp/host-check/work/practice-scripts/practices && \
    ln -s /opt/pi-sdk/node_modules /tmp/host-check/node_modules && \
    ln -sfn /opt/practice-scripts/lib /tmp/host-check/work/practice-scripts/lib && \
    ln -sfn /opt/practice-scripts/kits /tmp/host-check/work/practice-scripts/kits && \
    printf '%s\n' \
      'import { createAgentSession } from "@earendil-works/pi-coding-agent";' \
      'import { BUILTIN_SCRIPT, runPracticeProgram } from "./work/practice-scripts/lib/core/program-host.ts";' \
      'import { parseCandidate } from "./work/practice-scripts/lib/core/contract.ts";' \
      'if (typeof createAgentSession !== "function" || typeof runPracticeProgram !== "function") { throw new Error("the host cannot load the Pi SDK and the toolkit together"); }' \
      'if (typeof BUILTIN_SCRIPT.run !== "function" || parseCandidate({}).ok) { throw new Error("the toolkit contract does not load"); }' \
      > /tmp/host-check/.run-pi.ts && \
    node /tmp/host-check/.run-pi.ts && \
    rm -rf /tmp/host-check

RUN git config --system core.hooksPath /nonexistent && \
    git config --system core.fsmonitor false && \
    git config --system safe.directory /workspace/inputs/sources/scm/repo

ENV HOME=/home/agent
ENV XDG_CONFIG_HOME=/home/agent/.local/config
ENV PI_OFFLINE=1
ENV GIT_PAGER=cat
ENV GIT_TERMINAL_PROMPT=0
ENV GIT_OPTIONAL_LOCKS=0
ENV LANG=C.UTF-8

COPY pi/native-tools-check.ts /opt/pi-sdk/native-tools-check.ts
RUN mkdir -p /workspace/inputs/sources/scm/repo/.hidden && \
    printf 'searchable\n' > /workspace/inputs/sources/scm/repo/tracked.txt && \
    printf '.hidden/\n' > /workspace/inputs/sources/scm/repo/.gitignore && \
    head -c 3145728 /dev/zero | tr '\000' x > /workspace/inputs/sources/scm/repo/.hidden/large.txt && \
    printf 'SENTINEL\n' >> /workspace/inputs/sources/scm/repo/.hidden/large.txt && \
    git -C /workspace/inputs/sources/scm/repo init -q && \
    git -C /workspace/inputs/sources/scm/repo add . && \
    git -C /workspace/inputs/sources/scm/repo add -f .hidden/large.txt && \
    git -C /workspace/inputs/sources/scm/repo -c user.name=fixture -c user.email=fixture@example.invalid commit -qm captured && \
    chmod -R a-w /workspace/inputs/sources/scm/repo && \
    mkdir -p /home/agent/.local/tmp /workspace/work && \
    chown -R 1000:1000 /home/agent/.local /workspace/work && \
    setpriv --reuid=1000 --regid=1000 --init-groups node --max-old-space-size=256 \
      /opt/pi-sdk/native-tools-check.ts && \
    rm -rf /workspace/inputs /workspace/work /opt/pi-sdk/native-tools-check.ts /home/agent/.local

# The base image installs Yarn as a tree under /opt that /usr/local/bin only links to, so removing
# the links alone leaves a runnable package manager behind — which is why the check below reads the
# filesystem rather than the PATH.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
        /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg /usr/local/bin/pnpm /usr/local/bin/pnpx && \
    set -eu; \
    node --version; \
    found=$(find / -xdev -path /proc -prune -o -path /sys -prune -o \
        \( -name npm -o -name npx -o -name corepack -o -name yarn -o -name yarnpkg -o -name pnpm -o -name pnpx \) -print); \
    if [ -n "$found" ]; then \
        echo "FATAL: this image must carry no package manager, and ships:" >&2; \
        echo "$found" >&2; \
        exit 1; \
    fi

COPY --from=gateway /opt/pi-sdk/dist/ /opt/pi-sdk/
# The loop resolves the Pi SDK and the toolkit's one dependency from the packages this image already installs.
COPY --from=conversation-loop /opt/conversation-loop/ /opt/conversation-loop/
RUN mkdir -p /opt/conversation-loop/node_modules && \
    ln -s /opt/pi-sdk/node_modules/@earendil-works /opt/conversation-loop/node_modules/@earendil-works && \
    ln -s /opt/practice-scripts/node_modules/jsonc-parser /opt/conversation-loop/node_modules/jsonc-parser && \
    node --input-type=module -e 'const loop = await import("/opt/conversation-loop/conversation-loop.js"); if (typeof loop.createConversationLoop !== "function") { throw new Error("the conversation loop does not load"); }'
RUN mkdir -p /workspace/work /workspace/out /workspace/.pi /workspace/.sessions && \
    chown -R 1000:1000 /workspace/work /workspace/out /workspace/.pi /workspace/.sessions && \
    ln -s /opt/pi-sdk/node_modules /workspace/node_modules

LABEL hephaestus.agent.runtime-contract=9
LABEL hephaestus.agent.node-version=${NODE_VERSION}
LABEL hephaestus.agent.pi-version=${PI_VERSION}

USER 1000:1000
WORKDIR /workspace
