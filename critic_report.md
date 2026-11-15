# Stage 7: Critic Review Report (`design_draft.md`, Lines 1–248)

## Part 1: Design Critic Findings (`egm-design`)

### Finding D1: Unspecified Rule for Rewriting Internal Direct References (`<Name>` -> `<Name>Impl`) vs. API References Inside the Target Module
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L175-L210) (Section 3, *Transformations-Regeln im Detail*)
* **Finding & Recommendation**:
  * **Finding**: According to [Prompt.txt](file:///home/frank/projects/ApiCodeRefactoring/Prompt.txt#L26-L36), an internal class in the same module may hold a field referencing an implementation class (initialized via constructor) and invoke internal methods on it directly *without* requiring an `Intern` cast. However, in the untransformed source code of `projectA` (Case 2, where no prior API interface existed), that field and constructor call are written using the original class name (e.g., `private OrderService direct = new OrderService(...);`). Because the new API interface takes over the exact original name `OrderService` while the implementation class is renamed to `OrderServiceImpl`, leaving the field type and `new` expression in `InternalWorkflowRunner` as `OrderService` would fail compilation (`Cannot instantiate the type OrderService` and undefined internal method on the API interface). Line 209 states that internal classes may continue to use `<Name>Impl` directly, but Section 3 does not specify the deterministic AST transformation rule that rewrites `OrderService` to `OrderServiceImpl` for internal direct usages while keeping `OrderService` (the API interface) for API method signatures and variables receiving API return values.
  * **Recommendation**: Explicitly specify in Section 3 the syntactic rule used by `ApiRefactoring` within the target module (`projectA`) to distinguish type references and `new` expressions that must be renamed to `<Name>Impl` (e.g., unannotated `new <Name>(...)` calls not listed in `constructorCallTargets` and fields/variables directly assigned from them) from those that must use `<ApiInterface>` (API method parameters/return types and variables holding their results).

### Finding D2: Case 3 (`existingApiInterfaceFqn` Present) Contradicts Hardcoded `<Name>Impl` Naming in Provider and Intern Generation
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L182-L204) (Section 3.1–3.3)
* **Finding & Recommendation**:
  * **Finding**: In Section 3.1 (lines 182–185, Fall 3), when an implementation class already has an existing API interface (`existingApiInterfaceFqn` present, e.g., `BillingService` implementing `BillingServiceApi`), the implementation class is **not** renamed with an `Impl` suffix; it keeps its simple name (`BillingService`) and is moved to `<pkg>.impl.BillingService`. However, Sections 3.2 and 3.3 (lines 189–204) assume that implementation classes are always named `<Name>Impl` (e.g., line 191: *"Die `create(...)`-Methoden rufen `new <Name>Impl(...)` auf; die Wrapper-Methoden delegieren an `<Name>Impl.<staticMethod>(...)`"* and line 203: *"Wenn eine Methode in `<Name>Impl`..."*). If Case 3 is combined with constructors or static methods, generating `new <Name>Impl(...)` will fail to compile.
  * **Recommendation**: Generalize Sections 3.2 and 3.3 so that generated artifacts reference the resolved target implementation class name (`<Name>Impl` in Case 2, `<Name>` in Case 3) and the resolved target API interface (`<Name>` in Case 2, `existingApiInterfaceFqn` in Case 3).

### Finding D3: Duplicate FQN Collision When `transformed/java` Is Added as a Maven Source Folder
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L49-L50), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L117-L143), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L237-L238)
* **Finding & Recommendation**:
  * **Finding**: [Prompt.txt](file:///home/frank/projects/ApiCodeRefactoring/Prompt.txt#L43-L44) requires `transformed/java` to be configured in Maven as an additional source folder to ensure compilability. Because the transformed code in `transformed/java` preserves the exact same package and class names (`de.tk.refactoring.test.projectA.*` and `de.tk.refactoring.test.projectB.*`) as the original test sources in `src/test/java`, adding `transformed/java` alongside `src/test/java` in the same `javac` compilation pass causes immediate `duplicate class` compiler errors. The Feature Spec mentions isolated compilation via `JDK JavaCompiler` (line 125) but does not explain how the Maven source-folder requirement is satisfied without colliding with `src/test/java`.
  * **Recommendation**: Clarify in the Technical Plan / Non-Functional Requirements how the dual source roots are isolated during compilation (e.g., excluding `de/tk/refactoring/test/project*` from `src/test/java` in the Maven profile that activates `transformed/java` via `build-helper-maven-plugin`).

### Finding D4: Contradiction on Extended Interfaces in Generated API Interface & Missing Field in `ApiClassMetadata`
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L28-L29), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L154-L163), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L180-L181)
* **Finding & Recommendation**:
  * **Finding**:
    1. Line 28 states that a newly generated API interface extends *all* existing interfaces of the implementation class (*"das alle bestehenden Interfaces der Klasse erweitert"*), whereas line 180 restricts this to *API interfaces* (*"alle bisher von der Klasse implementierten API-Interfaces"*). If an implementation class implements a module-internal interface (which gets moved to `.impl`), extending all interfaces unconditionally (line 28) would introduce a compile dependency from the API package to the `.impl` package, violating Goal G4 (ArchUnit zero-dependency invariant).
    2. Section 2 (`ApiClassMetadata`, lines 154–163) omits `List<String> implementedInterfaces` from the metamodel specification, even though Section 3.1 requires it to construct the `extends` clause of the generated API interface.
  * **Recommendation**: Align line 28 with line 180 so that the generated API interface only extends external or API-conform interfaces of the original class, and add `List<String> implementedInterfaces` to `ApiClassMetadata` in Section 2.

### Finding D5: `ProviderHolder` Field Visibility and Uninitialized Guice Injector Failure Mode
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L192-L199) (Section 3.2)
* **Finding & Recommendation**:
  * **Finding**: In the snippet at lines 193–197, `static <Name>Provider provider;` without an access modifier is package-private. Across different packages/classloaders when `ClassGraphGuiceModule` (`de.tk.util.injection`) registers `requestStaticInjection(ProviderHolder.class)`, declaring `public static <Name>Provider provider;` avoids visibility issues.
  * **Recommendation**: Declare `public static <Name>Provider provider;` in `ProviderHolder`.

### Finding D6: Unused `providerInterfaceFqn` in `ConstructorCallTarget` & Empty-Scope Guard
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L170-L174), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L206-L209)
* **Finding & Recommendation**:
  * **Finding**: `ConstructorCallTarget` carries `String providerInterfaceFqn`, while call-site replacement invokes `<ApiInterface>.create(args)`. Also, `CastAndConstructorInjector` should guard against `call.getScope().isEmpty()`.
  * **Recommendation**: Note that `providerInterfaceFqn` is retained in `ConstructorCallTarget` for metamodel completeness and specify the `call.getScope().isEmpty()` guard in Section 3.4.

---

## Part 2: Structural Validator Findings (`egm-structural`)

### Finding S1: Heading Level for `### Additional Resources`
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L239-L246)
* **Discrepancy Description & Rationale**: `### Additional Resources` (H3) appears after a top-level horizontal rule (`---`). Adhering to the template (`### Additional Resources`) is acceptable, or promoting to H2 aligns it with sibling sections.

### Finding S2: Mixed Language Sub-Header (`### Vorgeschlagene Lösung`)
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L23)
* **Discrepancy Description & Rationale**: Top-level structural headers use English (`## Overview`, `## The Problem`, etc.), whereas line 23 uses German (`### Vorgeschlagene Lösung`) instead of `### Proposed Solution`.

---

## Part 3: Style Copy Editor Findings (`egm-style`)

### Finding ST1: Subjective Qualifier („schlanken“)
* **Severity**: Nit
* **Affected Scope**: `## Overview` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L11))
* **Style Rationale**: Remove subjective adjective „schlanken“ in „von schlanken API-Interfaces“.

### Finding ST2: Uncalibrated Claim on External Module Changes
* **Severity**: Nit
* **Affected Scope**: `## The Problem` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L18)) & `## Alternatives Considered — Alternative A` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L217))
* **Style Rationale**: Specify accurately that imports, type references, and method/field accesses in external modules remain unchanged when no prior API interface exists, whereas external constructor calls (`new <Name>(...)`) are replaced with `<Name>.create(...)`.

### Finding ST3: Process Meta-Commentary in `## Open Questions`
* **Severity**: Nit
* **Affected Scope**: `## Open Questions` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L59-L61))
* **Style Rationale**: Simplify `## Open Questions` to „Keine offenen Architekturfragen.“ without repeating brainstorming meta-commentary.

### Finding ST4: Emphatic Modifier („scharf getrennte“)
* **Severity**: Nit
* **Affected Scope**: `### 1. Systemarchitektur & Datenfluss` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L98))
* **Style Rationale**: Replace „vier scharf getrennte Subsysteme“ with „vier Subsysteme“.

### Finding ST5: Meta-Commentary in `### 2. Metamodell-Design`
* **Severity**: Nit
* **Affected Scope**: `### 2. Metamodell-Design` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L147))
* **Style Rationale**: Replace „Das Metamodell enthält exakt die in der Anforderung definierten Strukturen:“ with „Das Metamodell besteht aus folgenden unveränderlichen Record-Strukturen:“.
