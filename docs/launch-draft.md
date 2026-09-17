# Launch materials — unpublished draft

This file prepares copy and assets only. No launch, website deployment or submission has occurred. Submission eligibility, open status, deadline and any required Astra runtime role have not been verified during implementation.

## Product copy

**Name:** GraphHarness Live

**Tagline:** A shared code map for coding agents

**Description:** Watch coding agents search, read and change a shared checkout. Explore Java, TypeScript, JavaScript and Python structure, inspect actual tool activity, and coordinate supported Java edits with file reservations and version checks.

**Suggested topics:** Developer Tools, Open Source, Artificial Intelligence

**Maker comment draft:**

I wanted the repository map that helps an agent navigate to also help me supervise its work. GraphHarness Live gives multiple MCP clients one local graph and shows their actual searches, source reads, plans and reservation conflicts. A reservation protects cooperating harness writes; it does not lock unrelated editors or prove semantic safety.

The build extended an existing Kotlin/Java analyzer. Astra coordinated integration, with delegated agents handling protocol/session work, source consistency, language adapters, validation isolation, browser UI and independent critique. The recorded client configuration identifies the models used for the demo; display labels alone are not model evidence. The journal records dispatched actions, never private reasoning.

## Assets and attribution

- Landing page: `docs/index.html` with `docs/site.css`; static assets only. A hosting service cannot run the local daemon.
- Existing gallery screenshot: `docs/assets/coordinated-scripted-smoke.png`, explicitly labeled scripted clients.
- Real-client recording: `docs/assets/real-agents-demo.mp4` (1.1× playback), screenshots `real-agents-applied.png` and `real-agents-conflict.png`. Evidence and source media hashes: `reviews/recorded-demo-evidence.json`. Only the public fixture is shown.
- Suggested thumbnail: crop an actual public-fixture screenshot or use the text wordmark from the landing page. Do not use a fabricated product screen.
- Tool acknowledgements: [Cytoscape.js](https://js.cytoscape.org/) for graph rendering, [Joern](https://joern.io/) for the preferred Java analysis backend, [TypeScript](https://www.typescriptlang.org/) for TS/JS parsing, Python AST and the JDK compiler tree API for structural parsing, and the [Model Context Protocol](https://modelcontextprotocol.io/) for client integration.

## Checks before a future publication

Confirm the intended venue is open and the project is eligible; verify required model-use evidence against the venue’s current rules. Review public media for credentials, private code and machine paths. Upload the actual recording and screenshots, verify source/install links after pushing the changes, and obtain the user’s instruction before publishing or submitting. Prepared copy is not evidence of publication or eligibility.
