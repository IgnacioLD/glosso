# Contributing to Glosso

Thanks for helping. Bug reports and pull requests are welcome.

## Before you open a pull request

- Run the tests for the modules you touched:
  `./gradlew :engine:jvmTest`, `./gradlew :server:test`, `./gradlew :androidApp:testFdroidDebugUnitTest :androidApp:testPlaystoreDebugUnitTest`
  and `./gradlew :androidApp:lintFdroidDebug :androidApp:lintPlaystoreDebug`.
- Keep a pull request to one fix or feature.
- Commit messages follow `type(scope): description`, for example `fix(engine): keep schwa in unstressed syllables`.
- Write everything in English: commit messages, pull requests, issues, docs and code comments.
  UI text is translated per locale under `shared/src/commonMain/composeResources/values*/`.

## Things to know

- `:engine` is the only place a score is computed, and the app and the server must give the same
  score for the same take. Any change that moves scores needs numbers from
  `tools/engine-bench/run.sh` (see `docs/ENGINE-BENCHMARK.md`).
- Audio that cannot be judged is reported as "not evaluated", never as a low score.
- A store that moves keeps its file name and keys, or existing installs lose data
  (`AndroidStorageCompatibilityTest`).
- Adding or changing a dependency updates the license lock, or the app build fails:
  `./gradlew :androidApp:licensesRuntimeClasspath && python3 tools/licensing/collect.py`.
- Never commit models, databases, keystores, `local.properties` or secrets.

## License

By contributing you agree that your contribution is licensed under the AGPL-3.0-or-later, like the
rest of the project.
