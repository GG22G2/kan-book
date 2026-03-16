# Repository Guidelines

## Project Structure & Module Organization
`kan-book` is a single-module Gradle IntelliJ plugin project. Production code lives in `src/main/java/com/fish/novel` and is organized around startup (`NovelStartup`), editor hooks (`NovelEditorListener`), rendering (`NovelInlayRenderer`), configuration (`NovelConfig`, `NovelConfigurable`), and Legado integration (`LegadoUtil`). Plugin metadata and assets live in `src/main/resources/META-INF`, including `plugin.xml` and `pluginIcon.svg`. Gradle files stay at the repository root, and generated output belongs in `build/`. Add new automated tests under `src/test/java/com/fish/novel`.

## Build, Test, and Development Commands
Use the Gradle wrapper on Windows:

- `./gradlew.bat build` compiles the plugin and runs configured checks.
- `./gradlew.bat test` runs unit tests once a `src/test` suite exists.
- `./gradlew.bat runIde` launches a sandbox IntelliJ instance with the plugin loaded.
- `./gradlew.bat clean` removes generated artifacts from `build/`.

For IDE-based debugging, use `.run/Run IDE with Plugin.run.xml`.

## Coding Style & Naming Conventions
Target Java 21 and keep the existing 4-space indentation style. Use `PascalCase` for classes, `camelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants such as `RENDER_BUFFER_SIZE`. Keep packages under `com.fish.novel`. Prefer small, focused classes that match plugin responsibilities such as startup, settings, services, and rendering. No formatter or linter is committed, so match surrounding style and use IntelliJ reformatting on changed files before submitting.

## Testing Guidelines
There is no committed test suite yet and no coverage gate. For new logic, add JUnit tests in `src/test/java/com/fish/novel` with names like `LegadoUtilTest` or `NovelGlobalServiceTest`. For UI-facing changes, also validate manually with `./gradlew.bat runIde`: confirm activation in a `.java` file, Settings | Tools | Novel Reader, and mouse-wheel inlay behavior.

## Commit & Pull Request Guidelines
Recent history uses very short subjects such as `.` and `Initial commit`, so there is no strong convention to preserve. Prefer clear, imperative commit messages such as `feat: add chapter cache` or `fix: guard null editor state`. Pull requests should describe the user-visible change, note any config updates, link related issues, and include screenshots or GIFs for settings or editor-rendering changes.

## Configuration Notes
`build.gradle.kts` currently points to a local IntelliJ installation and a custom sandbox directory. Update those paths before building on a new machine to avoid environment-specific failures.

## 运行方式
intellj idea中通过运行插件的runIde来运行。 gradle已经配置为本地的gradle