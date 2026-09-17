# Repository workflow

- Commit coherent, verified changes as milestones are completed. Do not accumulate an entire implementation in one uncommitted working tree.
- Keep commits buildable and include the relevant tests with the code they verify. Commit messages should explain the problem and resulting behavior; claim only checks actually run.
- Stage explicit paths or reviewed hunks. Inspect the staged diff and run `git diff --cached --check` before committing. Preserve unrelated user changes and local state.
- During delegated work, the integrating agent owns the shared Git index and commits. Other agents hand back bounded changes and verification results before integration.
- If commits must be organized after the work was done, say so. Do not backdate commits, invent a development chronology, or attribute old results to a different artifact.
- Do not push or rewrite existing history unless the user authorizes it.
