#### 🔴 Rebuild custom agent images

If you use a custom agent image, rebuild it from the updated `docker/agents/pi/Dockerfile` before you upgrade the server. The runtime requires a compiled JavaScript toolkit and program child. An older image with TypeScript toolkit files does not meet this contract.

Official releases include matching images. Custom practice programs can still use TypeScript and do not need a source change.
