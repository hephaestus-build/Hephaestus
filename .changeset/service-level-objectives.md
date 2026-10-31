---
"hephaestus": minor
---

The shipped alert rules now include service level objectives, as burn-rate alerts: 99.5 % of API requests get no server error, 99.5 % of API reads finish within 2 seconds, and 75 % of page loads show their main content within 2.5 seconds. A fast burn pages, and a slow burn warns. To support the read objective, the server's `http_server_requests_seconds` metric now has one bucket at 2 seconds, which adds two series for each route. Load the new rule file from the release that you run. The page load objective needs page experience metrics on.
