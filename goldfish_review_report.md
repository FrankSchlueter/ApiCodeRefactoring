# Goldfish Annotated Review Report

**Status:** resolved
**Target:** `design_draft.md` (`1..447`)

## Prioritized Findings & AI Suggestions

### 1. Ambiguous Type-Rewriting & Constructor-Replacement Rules Inside the Target Module (`projectA`) [`egm-implementation-readiness`, `egm-codebase-details`, `egm-design`] - Severity: Blocker
*   **Affected Scope:** [Lines 175-210](./design_draft.md#L175-L210) (`3. Transformations-Regeln im Detail`), [Lines 332-343](./design_draft.md#L332-L343) (`12. ApiAnnotationScanner.java`), [Lines 352-365](./design_draft.md#L352-L365) (`14. ApiRefactoring.java`), [Lines 381-385](./design_draft.md#L381-L385) (`15. Test-Szenario-Klassen in projectA`)
*   **Discrepancy Description:** `InternalWorkflowRunner` requires both (a) calling an API method returning `OrderService` and invoking an internal method via `((OrderServiceIntern) ...)` cast, and (b) holding an internal field initialized via constructor and calling an internal method directly without a cast. Without an explicit syntactic rule separating API constructor replacements (`<Name>.create(...)`) from internal `Impl` field/constructor usage (`new <Name>Impl(...)`), replacing all `@Api` constructor calls with `<Name>.create(...)` breaks assignment to `*Impl` fields or forces a cast.
*   **Rationale:** A deterministic syntactic rule is required so `ApiAnnotationScanner` and `ApiRefactoring` handle internal `Impl`-to-`Impl` references without needing a data-flow analyzer or SymbolSolver.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid blocker. Specifying a clear syntactic distinction in Section 3, Item 12, and Item 14 ensures that internal fields in `impl` classes initialized with `new <Name>(...)` (or referencing internal classes) are rewritten to `<Name>Impl` / `new <Name>Impl(...)` and excluded from `ConstructorCallTarget`, while external calls and assignments to API-typed variables use `ConstructorCallTarget` (`<Name>.create(...)`).
*   ***Proposed Edit:***
    *   Location: Section 3.4 (Lines 208–210), Item 12 (Lines 340–342), and Item 14 (Lines 362–364)
    *   Change: Specify explicitly that within `targetModulePackage` (`impl` classes), non-API fields whose type is a transformed class without existing API interface and whose initializer (in field declaration or constructor) is `new <Name>(...)` are rewritten to type `<Name>Impl` with `new <Name>Impl(...)` (and are not added to `constructorCallTargets`), whereas external `new <Name>(...)` calls in `projectB` and calls assigned to API-typed variables are recorded in `constructorCallTargets` and replaced with `<ApiInterface>.create(...)`.

### 2. Duplicate Class Compilation Conflict in `compile-transformed` Maven Profile [`egm-codebase-details`, `egm-design`, `egm-inventive-catalyst`] - Severity: Blocker
*   **Affected Scope:** [Lines 253-266](./design_draft.md#L253-L266) (`1. pom.xml`), [Lines 416-420](./design_draft.md#L416-L420) (`Verification Plan`)
*   **Discrepancy Description:** Adding `transformed/java` via `build-helper-maven-plugin` alongside `src/test/java` in the `compile-transformed` Maven profile causes `javac` `duplicate class` errors because both directories contain `de.tk.refactoring.test.projectA.*` and `de.tk.refactoring.test.projectB.*`.
*   **Rationale:** `javac` rejects two source files with the same fully qualified class name in the same compilation pass.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid blocker for the optional `compile-transformed` Maven profile. Configuring `<testExcludes>` on `maven-compiler-plugin` in the `compile-transformed` profile to exclude `de/tk/refactoring/test/projectA/**` and `de/tk/refactoring/test/projectB/**` from `src/test/java` when `transformed/java` is added resolves the collision cleanly.
*   ***Proposed Edit:***
    *   Location: Item 1 (`pom.xml`, Line 265) and Non-Functional Requirements (Line 237)
    *   Change: Specify that the `compile-transformed` profile adds `transformed/java` via `build-helper-maven-plugin` (`add-test-source`) and configures `maven-compiler-plugin` `<testExcludes>` for `de/tk/refactoring/test/projectA/**` and `de/tk/refactoring/test/projectB/**` so only the transformed copies of those packages are compiled in that profile.

### 3. Record Field Name & Signature Mismatches (`ModuleMetamodel`, `ApiClassMetadata`, `ApiRefactoring.replaceImplUsage`) [`egm-implementation-readiness`, `egm-codebase-details`, `egm-comprehension`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 148-174](./design_draft.md#L148-L174) (`2. Metamodell-Design`) vs. [Lines 308-331](./design_draft.md#L308-L331) (`Items 8-11`) & [Lines 352-357](./design_draft.md#L352-L357) (`14. ApiRefactoring.java`)
*   **Discrepancy Description:** Field names in `ModuleMetamodel` (`targetModuleBasePackage` vs. `targetModulePackage`), `InternCastTarget` / `ConstructorCallTarget` (`targetClassFqn` vs. `classFqn`), and `ApiClassMetadata` (`implementedInterfaces` missing in Section 2) differ between Section 2 and the Detailed Implementation Plan. Also, the 1-arg overload `ApiRefactoring.replaceImplUsage(CompilationUnit implClass)` from `Prompt.txt` is omitted in Item 14, and Sections 3.2/3.3 hardcode `<Name>Impl` even for Case 3 (`existingApiInterfaceFqn`).
*   **Rationale:** Consistent record signatures and helper methods (`effectiveApiInterfaceName()`, `effectiveImplClassName()`) prevent implementation drift.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid consistency and completeness fix across Section 2, Section 3, and Items 8–14.
*   ***Proposed Edit:***
    *   Location: Section 2 (Lines 148–174), Section 3 (Lines 180–205), Items 8–11 (Lines 308–331), and Item 14 (Line 356)
    *   Change: Unify record component names (`targetModulePackage`, `implSubPackage`, `apiClasses`, `targetClassFqn`, and include `List<String> implementedInterfaces` in Section 2), add helper methods `effectiveApiInterfaceName()` / `effectiveImplClassName()` on `ApiClassMetadata` to handle Case 2 and Case 3 uniformly in Section 3, restrict extended interfaces on generated API interfaces to external/API-conform interfaces, and add the 1-argument overload `public static void replaceImplUsage(CompilationUnit implClass)` in Item 14.

### 4. ClassLoader Isolation in `ClassGraphGuiceModule` & Reflective Execution in `RefactoringEndToEndTest` [`egm-implementation-readiness`, `egm-codebase-details`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 192-199](./design_draft.md#L192-L199) (`3.2 ProviderHolder`), [Lines 281-289](./design_draft.md#L281-L289) (`4. ClassGraphGuiceModule.java`), [Lines 408-412](./design_draft.md#L408-L412) (`17. RefactoringEndToEndTest.java`)
*   **Discrepancy Description:** `ClassGraph` must be configured with `.overrideClassLoaders(classLoader).enableAllInfo().ignoreFieldVisibility().acceptPackages(scanPackages)` so it does not scan the untransformed classes in `target/test-classes` and finds the `@Inject static` field in `ProviderHolder` (which should be `public static`). In Test 4, `RefactoringEndToEndTest` must invoke the classes loaded by `transformedClassLoader` via Reflection (`Class.forName(..., true, transformedClassLoader)`).
*   **Rationale:** Without `overrideClassLoaders` and reflective invocation, the JVM links Test 4 against the untransformed classes in `target/test-classes`.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Essential implementation detail for in-process testing of the compiled `transformed/java` classes.
*   ***Proposed Edit:***
    *   Location: Section 3.2 (Line 196), Item 4 (Lines 286–289), and Item 17 (Lines 408–411)
    *   Change: Make `public static <Name>Provider provider;` public in `ProviderHolder`, specify `.overrideClassLoaders(classLoader).enableAllInfo().ignoreFieldVisibility().acceptPackages(scanPackages)` in `ClassGraphGuiceModule`, and specify reflective invocation via `transformedClassLoader` in Test 4.

### 5. AST Mutation Ordering with `LexicalPreservingPrinter` & Cross-Package Imports [`egm-codebase-details`, `egm-inventive-catalyst`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 206-210](./design_draft.md#L206-L210) (`3.4 Syntaktische Zeilennummern-Transformationen`), [Lines 344-365](./design_draft.md#L344-L365) (`Items 13 & 14`)
*   **Discrepancy Description:** Line-number-based transformations (`CastAndConstructorInjector`) rely on the initial 1-based AST line numbers from `StaticJavaParser.parse(...)` and must run in-memory without re-parsing between steps, including `call.getScope().isEmpty()`, `isAlreadyCastTo`, and `matchesLine` guards. Also, moving classes to `.impl` requires adding imports for types remaining in the base API package, and `javaparser-symbol-solver-core` is unnecessary.
*   **Rationale:** Preserves original AST line ranges during multi-step mutations and prevents missing-import compilation errors in `.impl`.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Directly prevents subtle JavaParser AST range and import issues during implementation and removes the unused `javaparser-symbol-solver-core` dependency.
*   ***Proposed Edit:***
    *   Location: Section 3.4 (Lines 207–210), Item 1 (Line 260), Item 13 (Lines 348–351), and Item 14 (Lines 357–364)
    *   Change: Remove `javaparser-symbol-solver-core` from `pom.xml`, specify that `CastAndConstructorInjector` executes in-memory on the original parsed AST with `call.getScope().isEmpty()`, `isAlreadyCastTo`, and `matchesLine` guards before structural package/class header rewrites, and add explicit imports for base API package types when moving classes to `.impl`.

### 6. Editorial & Structural Nits [`egm-style`, `egm-structural`] - Severity: Nit
*   **Affected Scope:** [Lines 11, 18, 23, 59-61, 98, 147, 217, 241, 383](./design_draft.md#L11)
*   **Discrepancy Description:** Minor style adjustments: remove subjective qualifiers („schlanken“, „scharf getrennte“), calibrate external-module change claims (noting constructor calls change to `<Name>.create(...)`), simplify `## Open Questions` to „Keine offenen Architekturfragen.“, promote `## Additional Resources` to H2, and use „im selben Modul“ instead of „im gleichen Produkt“ on line 383.
*   **Rationale:** Keeps prose objective, consistent, and aligned with structural heading hierarchy.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Easy cleanup that makes the specification cleaner and completely consistent.
*   ***Proposed Edit:***
    *   Location: Lines 11, 18, 59–61, 98, 147, 217, 241, 383
    *   Change: Apply all 6 minor text/heading cleanups in `design_draft.md`.
