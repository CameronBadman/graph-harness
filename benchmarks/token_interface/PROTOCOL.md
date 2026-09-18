# Slim responses and node edits: registered factorial pilot

Registered before implementation results or scored execution, 2026-09-18.
Decision: whether source-centered responses and/or one-call Java node edits reduce
whole-task Codex input and output while preserving independently checked correctness.
Old tasks/results remain development diagnostics; none are replaced or relabeled.

## Treatments

Five arms, three independently authored Java repair tasks, two repetitions: 30
scored invocations. Arms are `native`, `compact`, `slim`, `node`, `combined`.
Native has normal native tools only. All graph arms have the same four navigation
tools. Slim/combined additionally select `--response-format source-v1`; node/combined
add `--node-edits` exposing `replace_node_body`. The format changes only bundle
presentation; selection, budget and source information are unchanged. All graph
daemons start with writes enabled so daemon capability does not confound bridge
tool exposure; read-only profiles reject write calls even if their names are guessed.
Node editing is Java concrete-body replacement with existing 4096-character limits.

Same base prompt, model (`gpt-5.6-terra`), medium reasoning, native tools, permissions
and evaluation in all arms. Every graph arm receives the identical emitted
`codex-instructions` guidance, including format interpretation and optional node
editing. Native gets no graph guidance. Guidance overhead is counted. Native edits
and tests remain available; do not compel a tool route in scored trials or count
nonuse as evidence of a mechanism's efficiency. The new compact control therefore
has updated guidance and is not interchangeable with the historical control.

Task families: short meaningful method repair; tiny change in a longer method below
the argument limit; overloaded-method repair requiring another file's context.
Fixtures have realistic related code and independent behavior/scope checks. The
fixture worker may know task families but product workers may not see fresh answers
before their changes freeze. No target-specific retrieval tuning. No agent-authored
memory, new languages, adaptive routing or coverage changes in this cycle.

## Execution and evidence

Randomize arm order inside task/repetition blocks and randomize block order with
seed 2026091804. Five separate unscored calibration runs exercise actual retrieval,
response format and enabled node writes before scoring. Calibration fixture is not
a scored task. All calibrations require correctness, routes and independent access
review. Start only after one complete build and relevant regression tests pass.

Freeze product distribution, runner, task/evaluator, protocol, actual CLI native
binary/launcher, exact prompts, known host instructions/rules and schedule. Verify
hashes before each invocation. Use fresh workspaces outside ignored ancestors;
check indexed source counts against the fixture manifest. Capture actual MCP
initialize/list contract and daemon capabilities. Keep credentials out of public
artifacts. Raw prompts, transcripts, stderr and workspaces remain private.

Each trial has a 240-second model timeout. Run sequentially, without silent retries;
stop new launches after two consecutive infrastructure failures. Retain every
failure, timeout and unknown counter. A failing calibration stops scored launch;
any subsequent diagnostic revision must retain the failed calibration and refreeze
in a new directory before scoring. No optimization after inspecting scored results.

External evaluation runs after the model exits, compiles source and checks behavior
and unchanged scope without exposing probes/answers to the model. Temporary model
artifacts belong in `.scratch`. Audit commands, writes, MCP calls and stderr against
the declared workspace/access scope. Do not infer source provenance from display
names or outcome summaries. Configuration isolation is not complete host-context
isolation; freeze known host files and disclose the limit.

## Metrics and decision

Use the single terminal CLI usage event: input includes cached input, output includes
reasoning; neither subset is added twice. Report cached/uncached subsets separately,
all attempts, correctness/access status, actual read/node-write use, errors, model
and setup latency. Mechanism diagnostics measure repeated source exposure and
observable actions/bytes, not model reasoning or token attribution.

Compare each arm against native on the same valid/correct task/repetition cells
across all five arms. Also isolate format (`compact` vs `slim`, `node` vs `combined`)
and write route (`compact` vs `node`, `slim` vs `combined`). Display every paired
result, including increases. Percentage decrease is `100*(baseline-treatment)/baseline`.
Negative values mean more tokens. Missing/failed cells remain visible and prevent
a complete-success claim; do not hide their known token costs.

Acceptance remains at least 20% less aggregate total input versus native, no
aggregate output increase, all six arm attempts correct and access-valid, complete
matched coverage and actual retrieval in at least half the runs. Node/combined also
require actual node editing in at least half. Slim/combined must demonstrate the
new format in at least half. A passing small pilot justifies broader testing, not
production generalization or billing savings. Provider cache is uncontrolled;
model identity is configured rather than provider-attested. All negative/mixed
results must remain alongside any improvement.

## Delegation and milestones

Interface worker owns presentation/bridge flags; edit worker owns safe transaction
wrapper; evaluator worker owns fresh tasks/checks; root owns runner/integration and
Git index. A separate auditor checks completed raw runs. Commit the registered
protocol, verified interface, verified edits, frozen evaluation harness, and final
results/documentation as coherent milestones. Preserve user changes; no push.

## Calibration correction before scoring

The first freeze (commit 964639a) ran four calibrations and zero scored tasks.
Its exact-source calibration checker falsely rejected a correct nested-block
replacement. Independent compilation and behavior reproduced the false negative.
A separate calibration violated the declared scratch-output location by compiling
a class beside its source, then moving it. Both original records and access
decisions remain unchanged; they do not enter scored totals.

The second freeze replaces only calibration grading with compilation, behavior and
compiler-backed scope checks, and makes the existing scratch-output instruction
explicit for every arm. Scored task evaluators, product, seed, metrics and gates
remain unchanged. New regression tests accept equivalent bodies and reject wrong
behavior, scope escape and fabricated completion. Refreeze in a new directory and
rerun all five calibrations before scoring; do not overwrite the initial attempt.
