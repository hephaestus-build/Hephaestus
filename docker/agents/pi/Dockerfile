# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.20.0
FROM ghcr.io/pnpm/pnpm:12.3.4@sha256:b81d53184f670fe19d1a33f9d5041907d314b31d596838e8133cbd83d45be043 AS dependencies
ARG NODE_VERSION
WORKDIR /opt/pi-sdk
RUN pnpm runtime set node ${NODE_VERSION} -g
COPY pi/package.json pi/pnpm-lock.yaml pi/pnpm-workspace.yaml ./
RUN pnpm install --prod --frozen-lockfile --ignore-scripts

WORKDIR /opt/precompute
COPY precompute/package.json precompute/pnpm-lock.yaml precompute/pnpm-workspace.yaml ./
RUN pnpm install --prod --frozen-lockfile --ignore-scripts

FROM node:${NODE_VERSION}-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
ARG NODE_VERSION

# The base digest is pinned, so Debian security updates published after the upstream image was
# built can reach it only here; Renovate bumps the digest only when upstream republishes one.
#
# SOURCE_COMMIT is what makes that upgrade actually run. The builder imports the `cache-main`
# registry cache and nothing else in this layer changes between builds, so without a per-build
# input BuildKit would replay a weeks-old package set and the upgrade would silently stop
# happening — the exact failure this layer exists to prevent. webapp/Dockerfile gets the same
# argument, one instruction ahead of its own upgrade.
ARG SOURCE_COMMIT
RUN echo "source commit: ${SOURCE_COMMIT:-<unset — local build>}" && \
    apt-get update -qq && apt-get upgrade -y -qq && \
    apt-get install -y --no-install-recommends bash git ripgrep fd-find findutils tree jq ca-certificates grep coreutils util-linux tar && \
    rm -rf /var/lib/apt/lists/*

RUN groupmod --new-name agent node && \
    usermod --login agent --home /home/agent --move-home --shell /bin/bash node

# renovate: datasource=npm depName=@earendil-works/pi-coding-agent
ARG PI_VERSION=0.84.4
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
      'modelRuntime.registerProvider("gate", { name: "gate", baseUrl: "http://127.0.0.1:9/v1", apiKey: "$GATE_TOKEN", authHeader: true, api: "openai-completions", models: [{ id: "model", name: "model", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 1000, maxTokens: 100 }] });' \
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

COPY --from=dependencies /opt/precompute/node_modules /opt/precompute/node_modules
COPY precompute/package.json precompute/runner.ts /opt/precompute/
COPY precompute/lib/ /opt/precompute/lib/

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

COPY pi/gateway-init.ts pi/gateway-run.ts pi/gateway-mentor.ts /opt/pi-sdk/
RUN mkdir -p /workspace/work /workspace/out /workspace/.pi /workspace/.sessions && \
    chown -R 1000:1000 /workspace/work /workspace/out /workspace/.pi /workspace/.sessions && \
    ln -s /opt/pi-sdk/node_modules /workspace/node_modules

LABEL hephaestus.agent.runtime-contract=3
LABEL hephaestus.agent.node-version=${NODE_VERSION}
LABEL hephaestus.agent.pi-version=${PI_VERSION}

USER 1000:1000
WORKDIR /workspace
