---
"hephaestus": minor
---

The server is no longer killed for memory under sustained load. The chart now keeps 15 % of the server's memory limit outside the JVM's budget, for the native memory that the JVM does not count. It also budgets 200 threads instead of 250 plus 8 per review. Before, the heap could grow until the container passed its limit, and Kubernetes restarted the server, which ended the reviews in flight. At the 3 GiB default the heap maximum is now about 1,914 MiB, down from 2,167 MiB. The sandbox gateway's memory for request bodies, a quarter of the heap, falls with it.

A new alert, `HephaestusContainerMemoryHigh`, fires when a container's anonymous memory stays above 85 % of its limit for 10 minutes. It reads the kubelet's cAdvisor metrics, so it needs a Prometheus that scrapes them. The bundled monitoring stack does not evaluate it.

Two new optional values, `server.jvm.headRoomPercent` (default `15`) and `server.jvm.threadCount` (default `200`), set these inputs. Your values need no change.
