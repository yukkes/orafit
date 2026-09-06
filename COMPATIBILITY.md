# Oracle Compatibility Matrix

Use this page before adopting Orafit to decide whether the Oracle SQL and JDBC behavior used by your application falls inside the supported migration boundary.

Orafit is **not a full Oracle emulator**. It owns a bounded application-SQL/JDBC compatibility surface and fails closed when Oracle semantics cannot be preserved confidently.

## Reference and platform boundary

| Item | Support contract |
|---|---|
| Oracle reference | **Oracle Database 18c XE** is the sole compatibility authority |
| Java | Java 17 |
| PostgreSQL 17 | Primary supported target |
| PostgreSQL 16 | Supported target |
| Aurora PostgreSQL 16/17 Serverless v2 | Supported through the plain-SQL `orafit` installation path; CI validates the package contract on upstream PostgreSQL |
| PostgreSQL 18 | Compatibility check only |
| PostgreSQL 15 and older | Not supported |
| Oracle 19c+ behavior | Not part of this target unless it also exists in Oracle 18c and is represented by the executable contract |

## Why further Oracle compatibility gets harder

The remaining gap is increasingly **not a SQL-parser problem**. A rewrite is considered compatible only when the Oracle-visible behavior stays the same: returned values and row order, NULL behavior, errors, JDBC bind/OUT behavior, result metadata, update counts, and DML post-state. A translation that merely executes successfully on PostgreSQL is not enough.

The remaining feature families tend to cross one or more architectural boundaries:

| Boundary | Oracle example | Why it is difficult to preserve exactly |
|---|---|---|
| Evaluation order and row selection | `SELECT * FROM emp WHERE ROWNUM <= 10 ORDER BY sal DESC` | Oracle `ROWNUM` is assigned before same-block `ORDER BY`. PostgreSQL has no direct equivalent, so additional query layers can change cardinality, ordering, aliases, or metadata. |
| Hierarchical execution state | `SELECT LEVEL, name FROM emp START WITH manager_id IS NULL CONNECT BY PRIOR id = manager_id ORDER SIBLINGS BY name DESC` | Exact sibling ordering, cycle state, path/root expressions, NULL ordering, ties, and collation require state beyond a simple recursive CTE rewrite. |
| DML state machines | `MERGE INTO t USING s ON (...) WHEN MATCHED THEN UPDATE SET ... DELETE WHERE ...` | Oracle can evaluate `DELETE WHERE` against the row after the MERGE update. Reproducing that state transition safely is more than syntax conversion. |
| Ambiguous derived source shape | `SELECT x.* FROM (SELECT ... FROM ...) x START WITH ... CONNECT BY ...` | Physical tables and AST-known derived sources are supported, but reconstructing wildcard shape from ambiguous derived sources can require provenance/schema information that Orafit intentionally does not look up at runtime. |
| Datatype and JDBC metadata identity | `SELECT TIMESTAMP '2026-01-01 00:00:00.123456789' FROM dual` | Oracle and PostgreSQL differ in timestamp precision, NUMBER/CHAR semantics, type names, precision/scale, and timezone representation. Value equality alone does not guarantee JDBC compatibility. |
| Session-dependent semantics | `SELECT TO_CHAR(amount, '999G999D00') FROM sales` | NLS settings, locale, collation, timezone region rules, and session configuration can change results. Guessing the Oracle environment would create silent differences. |
| Oracle-only language/runtime features | `BEGIN pkg.proc(:in_value, :out_value); END;` | Full PL/SQL, package state, overload resolution, REF CURSORs, proprietary JDBC types, and data-dictionary behavior would require a separate Oracle runtime/API emulation layer. |
| Large structural query families | `SELECT * FROM (SELECT deptno, job, sal FROM emp) PIVOT (SUM(sal), COUNT(*) AS cnt FOR job IN ('DEV' AS dev))` | Basic static PIVOT/UNPIVOT forms can be lowered safely, but multiple aggregates/column tuples, implicit Oracle naming, datatype propagation, and direct-result metadata quickly require much broader machinery. |

This is why the remaining `⚠️ Supported subset`, `⛔ Rejected`, and `➖ Out of scope` rows are intentional. Expanding them now would often require one of the following: a new ordering/state subsystem, runtime schema lookup, Oracle-specific type/metadata propagation, session/NLS emulation, or a PL/SQL/proprietary JDBC compatibility layer.

Orafit therefore follows a **fail-closed rule**: when the observable Oracle contract cannot be represented and proven on Oracle 18c XE and PostgreSQL 16/17, the statement is rejected rather than passed through with potentially different semantics.

## How to read the matrix

The **Example** column is representative, not an additional promise. The exact public contract remains the row description plus the executable evidence referenced by `src/test/resources/oracle/scope.toml`.

| Status | Meaning |
|---|---|
| ✅ **Supported** (`same`) | The declared form is part of the Oracle-visible compatibility contract. |
| ⚠️ **Supported subset** (`bounded`) | Only explicitly bounded forms are supported. |
| ⛔ **Rejected** (`reject`) | Orafit detects the form and fails closed. |
| ➖ **Out of scope** (`outside`) | The feature family is outside the runtime migration boundary. |

`src/test/resources/oracle/scope.toml` is the source of truth. `src/test/resources/oracle/cases/**/*.toml` is the only executable Oracle-visible case inventory. Matrix rows are orthogonal: a Supported row promises only the form named by that row, while separate datatype, NLS, proprietary-API, or structural limits still apply to a complete statement. When a broader Oracle feature family contains both proven and residual forms, this matrix splits them into separate Supported and Supported subset rows instead of weakening the proven forms to the family-wide status.

Every `same` entry names canonical Oracle case IDs as evidence. CI rejects a Supported claim whose evidence is missing, duplicated, unknown, or not itself `mode = "same"`.

## SQL core and datatype semantics

| Area | Status | Example | Contract |
|---|---|---|---|
| `q-quoted-literal` <!-- compat:q-quoted-literal=same --> | ✅ **Supported** | `SELECT q'[Bob's bike]' FROM dual` | Oracle q-quoted string literals in the canonical form, including embedded single quotes. |
| `lexical-other-literals` <!-- compat:lexical-other-literals=bounded --> | ⚠️ **Supported subset** | `SELECT q'{text}' FROM dual` | Other Oracle-oriented lexical forms are not supported by implication; only explicitly represented forms are owned. |
| `null-empty-string` <!-- compat:null-empty-string=same --> | ✅ **Supported** | `SELECT NVL('', 'x') FROM dual` | Oracle zero-length character semantics, including supported SQL writes and JDBC character binds. |
| `implicit-character-number-comparison` <!-- compat:implicit-character-number-comparison=same --> | ✅ **Supported** | `SELECT 1 FROM dual WHERE '10' = 10` | Literal character/NUMBER equality in either operand order, including represented ORA-01722 failure for invalid character input. Column/bind datatype inference and wider conversion precedence are not implied. |
| `implicit-character-numeric-arithmetic` <!-- compat:implicit-character-numeric-arithmetic=same --> | ✅ **Supported** | `SELECT '10' + 2 FROM dual` | Literal character/NUMBER arithmetic in either operand order uses Oracle-compatible numeric coercion, including represented ORA-01722 failure. Schema-dependent coercion remains bounded. |
| `number-fixed-scale-value` <!-- compat:number-fixed-scale-value=same --> | ✅ **Supported** | `CAST(1.2 AS NUMBER(5,2))` | Represented `NUMBER(p,s)` value/JDBC scale behavior is preserved. |
| `number-conversion-other-semantics` <!-- compat:number-conversion-other-semantics=bounded --> | ⚠️ **Supported subset** | `SELECT '1,234.5' + 1 FROM dual` | Canonical arithmetic and unconstrained-NUMBER CAST cases preserve Oracle overflow (ORA-01426), underflow-to-zero, division precision, and 20-digit rounding. Other implicit conversions, aggregate/storage ranges, format models, and NLS-dependent parsing remain bounded. |
| `char-blank-padding-comparison` <!-- compat:char-blank-padding-comparison=same --> | ✅ **Supported** | `CAST('A' AS CHAR(3)) = 'A'` | Blank-padded equality for the represented Oracle CHAR comparison shape. NLS/collation-dependent comparison rules are not implied. |
| `char-varchar-other-comparisons` <!-- compat:char-varchar-other-comparisons=bounded --> | ⚠️ **Supported subset** | `CAST('A' AS CHAR(3)) = CAST('A' AS VARCHAR2(3))` | CHAR/VARCHAR2 equality and explicitly width-known set-operation forms are covered by canonical cases. Other comparison/coercion shapes remain bounded. |
| `char-varchar-default-byte-length` <!-- compat:char-varchar-default-byte-length=same --> | ✅ **Supported** | `VARCHAR2(3)` / `CHAR(3)` under default `NLS_LENGTH_SEMANTICS=BYTE` | After applying `orafit.apply_byte_length_semantics`, bounded native PostgreSQL `varchar` and `char` columns preserve the represented AL32UTF8 byte boundary and ORA-12899 error code. Explicit CHAR semantics, mixed semantics, and differing database encodings are not implied. |
| `date-literal` <!-- compat:date-literal=same --> | ✅ **Supported** | `SELECT DATE '2026-08-15' FROM dual` | Oracle DATE literal values represented by the canonical contract. |
| `date-numeric-day-arithmetic` <!-- compat:date-numeric-day-arithmetic=same --> | ✅ **Supported** | `SELECT DATE '2026-08-15' + 1.5 FROM dual` | Oracle DATE plus/minus numeric values as day intervals, including represented decimal JDBC binds. |
| `date-timestamp-basic-metadata` <!-- compat:date-timestamp-basic-metadata=same --> | ✅ **Supported** | `SELECT DATE '2026-08-15', TIMESTAMP '2026-08-15 12:34:56' FROM dual` | Basic Oracle DATE/TIMESTAMP JDBC type-name/shape metadata represented by the contract. |
| `timestamp-over-six-fractional-digits` <!-- compat:timestamp-over-six-fractional-digits=reject --> | ⛔ **Rejected** | `TIMESTAMP '2026-01-01 00:00:00.123456789'` | Oracle TIMESTAMP literals above PostgreSQL's six fractional digits fail closed instead of being silently rounded. |
| `date-timestamp-other-semantics` <!-- compat:date-timestamp-other-semantics=bounded --> | ⚠️ **Supported subset** | `DATE '2026-08-15' - DATE '2026-08-14'` | DATE-DATE subtraction returns the represented Oracle NUMBER/scale, and canonical TIMESTAMP-to-DATE casts truncate fractional seconds. TO_DATE input validation covers the represented trailing-data, invalid-day, and year-zero forms. Mixed TIMESTAMP/DATE subtraction, timezone, and other environment-sensitive semantics require explicit case coverage. |
| `searched-case-short-circuit` <!-- compat:searched-case-short-circuit=same --> | ✅ **Supported** | `CASE WHEN 1=1 THEN 1 WHEN 1/0=1 THEN 2 END` | Searched CASE stops evaluating later conditions after a matching branch, as represented by the canonical contract. |
| `simple-case-core-forms` <!-- compat:simple-case-core-forms=same --> | ✅ **Supported** | `CASE 2 WHEN 1 THEN 10 WHEN 2 THEN 20 ELSE 30 END` | Proven simple CASE numeric matching plus SQL NULL non-match behavior for the represented forms. |
| `case-result-datatype-enforcement` <!-- compat:case-result-datatype-enforcement=same --> | ✅ **Supported** | `CASE WHEN 1=1 THEN 1 ELSE DATE '2026-01-01' END` | Represented incompatible CASE result datatypes raise the Oracle-visible datatype error instead of being silently coerced. |
| `case-expression-other-forms` <!-- compat:case-expression-other-forms=bounded --> | ⚠️ **Supported subset** | `CASE status WHEN 'A' THEN 1 ELSE 'x' END` | Other simple/searched CASE coercion combinations are supported only when explicitly represented. |
| `concatenation` <!-- compat:concatenation=same --> | ✅ **Supported** | <code>'A' &#124;&#124; NULL</code> | Oracle <code>&#124;&#124;</code> semantics represented by the canonical contract, including NULL operands and numeric composition. |

## Conditions and expression semantics

| Area | Status | Example | Contract |
|---|---|---|---|
| `condition-precedence-and-or` <!-- compat:condition-precedence-and-or=same --> | ✅ **Supported** | `a = 1 OR b = 2 AND c = 3` | Oracle SQL condition precedence where AND binds more tightly than OR, as represented by the canonical case. |
| `null-comparison-three-valued-logic` <!-- compat:null-comparison-three-valued-logic=same --> | ✅ **Supported** | `NULL = NULL` | Direct NULL equality remains UNKNOWN rather than TRUE or FALSE, as proven by the canonical three-valued-logic case. |
| `comparison-and-null-logic-other` <!-- compat:comparison-and-null-logic-other=bounded --> | ⚠️ **Supported subset** | `a = NULL OR b <> NULL` | Other comparison/coercion and three-valued NULL logic shapes remain bounded to explicit contracts below. |
| `in-not-in` <!-- compat:in-not-in=same --> | ✅ **Supported** | `x NOT IN (1, 2, NULL)` | Ordinary IN / NOT IN, including NULL and empty-subquery truth behavior. |
| `any-all` <!-- compat:any-all=same --> | ✅ **Supported** | `x > ALL (SELECT n FROM t)` | Ordinary ANY/SOME/ALL quantified comparisons, including empty-set behavior. |
| `between` <!-- compat:between=same --> | ✅ **Supported** | `x BETWEEN 10 AND 20` | Inclusive boundaries and NULL/UNKNOWN behavior represented by the contract. |
| `like` <!-- compat:like=same --> | ✅ **Supported** | `name LIKE 'A\_%' ESCAPE '\'` | Ordinary LIKE/ESCAPE and SQL three-valued NULL behavior. |
| `exists-and-correlation` <!-- compat:exists-and-correlation=same --> | ✅ **Supported** | `EXISTS (SELECT 1 FROM child c WHERE c.parent_id = p.id)` | EXISTS and declared correlated-subquery behavior. |

## Query semantics

| Area | Status | Example | Contract |
|---|---|---|---|
| `scalar-subquery` <!-- compat:scalar-subquery=same --> | ✅ **Supported** | `SELECT (SELECT value FROM t WHERE id = 1) FROM dual` | Zero-row NULL, one-row value, and Oracle-compatible cardinality failure. |
| `select-dual` <!-- compat:select-dual=same --> | ✅ **Supported** | `SELECT 1 FROM dual` | Oracle DUAL single-row behavior. |
| `distinct-unique` <!-- compat:distinct-unique=same --> | ✅ **Supported** | `SELECT UNIQUE deptno FROM emp` | DISTINCT and Oracle SELECT UNIQUE behavior represented by the contract. |
| `ansi-joins` <!-- compat:ansi-joins=same --> | ✅ **Supported** | `SELECT * FROM a LEFT JOIN b ON b.a_id = a.id` | Ordinary INNER/LEFT/RIGHT/FULL/CROSS/self joins represented by canonical cases. |
| `legacy-outer-join-basic-forms` <!-- compat:legacy-outer-join-basic-forms=same --> | ✅ **Supported** | `WHERE a.id = b.a_id(+)` | Oracle `(+)` optional-right, optional-left, marked optional-side filter, simple chained forms, and the represented shape where one optional relation is constrained by multiple mandatory comma-join relations. |
| `legacy-outer-join-other` <!-- compat:legacy-outer-join-other=bounded --> | ⚠️ **Supported subset** | `WHERE a.id = b.a_id(+) OR b.flag(+) = 'Y'` | Other `(+)` predicate and placement shapes remain bounded; complex or ambiguous placement fails closed. |
| `cross-outer-apply-correlated-inline-view` <!-- compat:cross-outer-apply-correlated-inline-view=same --> | ✅ **Supported** | `FROM dept d OUTER APPLY (SELECT * FROM emp e WHERE e.deptno=d.deptno)` | Represented correlated inline-view forms for both CROSS APPLY and OUTER APPLY, including preservation of unmatched left rows for OUTER APPLY. |
| `cross-outer-apply-other` <!-- compat:cross-outer-apply-other=bounded --> | ⚠️ **Supported subset** | `OUTER APPLY (SELECT ... FROM ... APPLY (...))` | Other APPLY nesting, projection, and lateral-query shapes require explicit evidence. |
| `with-subquery-factoring` <!-- compat:with-subquery-factoring=same --> | ✅ **Supported** | `WITH x AS (SELECT 1 n FROM dual) SELECT n FROM x` | Non-recursive WITH factoring and dependency chains represented by the contract. |
| `aggregate-group-having` <!-- compat:aggregate-group-having=same --> | ✅ **Supported** | `SELECT deptno, SUM(sal) FROM emp GROUP BY deptno HAVING SUM(sal)>1000` | Ordinary aggregate empty-input behavior plus GROUP BY/HAVING. |
| `set-operations` <!-- compat:set-operations=same --> | ✅ **Supported** | `SELECT id FROM a MINUS SELECT id FROM b` | UNION, UNION ALL, INTERSECT, and Oracle MINUS set behavior represented by the contract. |
| `analytic-default-range-frame` <!-- compat:analytic-default-range-frame=same --> | ✅ **Supported** | `SUM(sal) OVER (ORDER BY hiredate)` | Analytic `ORDER BY` default frame behaves as Oracle `RANGE` through peer rows for the represented aggregate form. |
| `analytic-explicit-rows-frame` <!-- compat:analytic-explicit-rows-frame=same --> | ✅ **Supported** | `SUM(sal) OVER (ORDER BY hiredate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` | Explicit row-sensitive frame in the represented form. |
| `analytic-row-number-partition-order` <!-- compat:analytic-row-number-partition-order=same --> | ✅ **Supported** | `ROW_NUMBER() OVER (PARTITION BY deptno ORDER BY sal DESC, empno)` | `ROW_NUMBER()` partition reset and deterministic multi-key ordering in the represented form. |
| `analytic-window-other` <!-- compat:analytic-window-other=bounded --> | ⚠️ **Supported subset** | `RANK() OVER (ORDER BY sal)` | Other analytic functions, frame boundaries, ranking semantics, and window compositions require explicit evidence. |
| `order-by-null-ordering` <!-- compat:order-by-null-ordering=same --> | ✅ **Supported** | `ORDER BY sal DESC` | Oracle default NULL ordering for declared ascending/descending forms. |
| `optimizer-hint-use-hash` <!-- compat:optimizer-hint-use-hash=same --> | ✅ **Supported** | `/*+ USE_HASH(a b) */` | Advisory `USE_HASH` hints are removed before PostgreSQL execution; the represented form preserves Oracle-visible query results while intentionally not preserving the Oracle execution plan. |
| `rownum-filter-and-pagination` <!-- compat:rownum-filter-and-pagination=same --> | ✅ **Supported** | `SELECT * FROM emp WHERE ROWNUM <= 10` | Proven ROWNUM upper-bound filters, `ROWNUM = 1`, Oracle's unreachable `ROWNUM = 2` behavior, bind bounds, combined upper bounds, bounded direct `ROWNUM` projection, ordered-inline-view top-N, and classic two-level pagination. |
| `rownum-same-block-order-by` <!-- compat:rownum-same-block-order-by=reject --> | ⛔ **Rejected** | `SELECT * FROM emp WHERE ROWNUM <= 10 ORDER BY sal DESC` | Same-block ORDER BY fails closed because Oracle assigns ROWNUM before that sort and selected rows can depend on the Oracle access path; PostgreSQL top-N semantics would be unsafe. |
| `rownum-other` <!-- compat:rownum-other=bounded --> | ⚠️ **Supported subset** | `SELECT ROWNUM, empno FROM emp` | Other ROWNUM shapes remain bounded. Unbounded projection, `ROWNUM >`, and cardinality-changing aggregate forms remain deliberately fail-closed where represented. |
| `offset-fetch` <!-- compat:offset-fetch=same --> | ✅ **Supported** | `ORDER BY id OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY` | Ordered OFFSET/FETCH NEXT ROWS ONLY pagination represented by the contract, including canonical fractional, NULL, and negative row-count behavior (Oracle truncates or returns zero rows as represented). FETCH PERCENT remains explicitly rejected. |
| `connect-by-single-source-forms` <!-- compat:connect-by-single-source-forms=same --> | ✅ **Supported** | `START WITH manager_id IS NULL CONNECT BY PRIOR id = manager_id` | Proven physical single-source hierarchy forms with explicit START WITH and one direct PRIOR equality, including LEVEL, acyclic NOCYCLE, residual WHERE, ascending ORDER SIBLINGS BY, CONNECT_BY_ROOT, SYS_CONNECT_BY_PATH, and CONNECT_BY_ISLEAF. |
| `connect-by-prior-expression-forms` <!-- compat:connect-by-prior-expression-forms=same --> | ✅ **Supported** | `CONNECT BY PRIOR ABS(id) = manager_id` | Proven bounded PRIOR arithmetic and ABS source expressions represented by the Oracle 18c differential contract. |
| `connect-by-derived-source-forms` <!-- compat:connect-by-derived-source-forms=same --> | ✅ **Supported** | `FROM (SELECT id, manager_id FROM emp) e START WITH ... CONNECT BY PRIOR id = manager_id` | Proven explicit inline-view, join-derived, CTE, explicit CTE-column-list, DISTINCT, nested-WITH, and AST-known derived wildcard hierarchy sources. Ambiguous source shapes remain fail-closed. |
| `connect-by-final-output-order` <!-- compat:connect-by-final-output-order=same --> | ✅ **Supported** | `... CONNECT BY PRIOR id = manager_id ORDER BY LEVEL, name` | Proven final hierarchy ORDER BY forms using direct source columns, LEVEL, direct-column SELECT aliases and ordinals, including represented ASC/DESC and NULL ordering. |
| `connect-by-order-siblings-advanced` <!-- compat:connect-by-order-siblings-advanced=same --> | ✅ **Supported** | `... CONNECT BY PRIOR id = manager_id ORDER SIBLINGS BY name DESC NULLS LAST, id` | Proven descending, multi-key and explicit NULL sibling ordering, including represented UPPER/LOWER SELECT aliases, ordinals, and UPPER/LOWER applied to a direct-column SELECT alias, using a bounded per-parent rank path. |
| `connect-by-physical-wildcard` <!-- compat:connect-by-physical-wildcard=same --> | ✅ **Supported** | `SELECT e.* FROM emp e START WITH ... CONNECT BY PRIOR id = manager_id` | Physical-table `*` and qualified `alias.*` preserve source column shape and JDBC metadata by rejoining the current physical row after recursive expansion; translation performs no schema lookup. |
| `connect-by-other` <!-- compat:connect-by-other=bounded --> | ⚠️ **Supported subset** | `... CONNECT BY NOCYCLE PRIOR id = manager_id` | Other hierarchy shapes remain bounded. No START WITH, hierarchy aggregates, CONNECT_BY_ISCYCLE, arbitrary sibling expressions such as CASE, unsupported CONNECT_BY_ROOT composition, and compound PRIOR predicates remain fail-closed. |
| `sequence-nextval-currval` <!-- compat:sequence-nextval-currval=same --> | ✅ **Supported** | `SELECT order_seq.NEXTVAL, order_seq.NEXTVAL FROM dual` | Declared sequence NEXTVAL/CURRVAL behavior, including one value per source row for repeated projection references and supported DML composition. NEXTVAL in a SELECT `WHERE` clause is rejected with the Oracle ORA-02287 contract rather than consuming a value. |
| `pivot-static-single-sum` <!-- compat:pivot-static-single-sum=same --> | ✅ **Supported** | `FROM (SELECT deptno, job, sal FROM sales) PIVOT (SUM(sal) FOR job IN ('DEV' AS dev, 'OPS' AS ops))` | Explicit derived-query source with explicit simple columns, one `SUM(simple_column)`, one `FOR` column, and static literal buckets with explicit aliases; the represented source-qualifier form is included. Direct-table PIVOT, multiple aggregates/FOR columns, dynamic buckets, and implicit output naming are outside this row. |
| `unpivot-static-single-value` <!-- compat:unpivot-static-single-value=same --> | ✅ **Supported** | `FROM (SELECT id, q1, q2 FROM t) UNPIVOT (amount FOR quarter IN (q1 AS 'Q1', q2 AS 'Q2'))` | Explicit derived simple-column source, one value column, one `FOR` column, static source-column list, default NULL exclusion, and `INCLUDE NULLS` row semantics. Direct JDBC result metadata remains governed by `jdbc-result-metadata-other`; tuple/multi-value and direct-table forms are outside this row. |
| `advanced-query-constructs` <!-- compat:advanced-query-constructs=reject --> | ⛔ **Rejected** | `FROM (SELECT deptno, job, sal FROM sales) PIVOT (SUM(sal), COUNT(*) AS cnt FOR job IN ('DEV' AS dev))` | Residual PIVOT/UNPIVOT forms plus MODEL, MATCH_RECOGNIZE and similar unsafe advanced forms remain fail closed; the two explicit basic rows above are the bounded exceptions. |

## Functions

Function rows are intentionally fine-grained. A simple function that is fully proven does not inherit the limits of an unrelated function in the same Oracle family.

| Area | Status | Example | Contract |
|---|---|---|---|
| `common-native-scalar-functions` <!-- compat:common-native-scalar-functions=same --> | ✅ **Supported** | `COALESCE(NULL, 7)`, `LENGTH(CAST('A' AS CHAR(3)))`, `SQRT(-1)` | Proven deterministic common scalar forms for `COALESCE`, `NULLIF`, `UPPER`, `LOWER`, `ABS`, `CEIL`, `FLOOR`, `MOD`, `REMAINDER`, `SQRT`, `NEXT_DAY`, ANSI numeric `CAST`, `TRIM`, and `LENGTH` on represented numeric/non-empty ASCII and fixed-CHAR inputs. Oracle domain errors and metadata are preserved only for canonical cases; NLS/collation and other datatype edges are not implied. |
| `nvl-nvl2` <!-- compat:nvl-nvl2=same --> | ✅ **Supported** | `NVL(name, 'unknown')` | NVL and NVL2 NULL/empty-string branch semantics represented by canonical cases. |
| `decode-core-forms` <!-- compat:decode-core-forms=same --> | ✅ **Supported** | `DECODE(NULL, NULL, 'match', 'miss')` | Proven DECODE NULL-search equality, literal numeric search/default behavior, numeric-result NUMBER metadata, and the represented numeric JDBC-bind source form. |
| `decode-other` <!-- compat:decode-other=bounded --> | ⚠️ **Supported subset** | `DECODE(status, other_column, 1, 0)` | Other DECODE search/result datatype and coercion combinations remain bounded; ambiguous or unproven forms fail closed. |
| `to-number-basic` <!-- compat:to-number-basic=same --> | ✅ **Supported** | `TO_NUMBER('1234.5')` | Basic decimal/integer TO_NUMBER, VARCHAR bind input, and represented invalid-number error semantics. Format-model/NLS forms are not implied. |
| `numeric-trunc-round` <!-- compat:numeric-trunc-round=same --> | ✅ **Supported** | `ROUND(123.456, 2)` | Numeric TRUNC/ROUND with omitted, positive, and negative decimal places represented by canonical cases. |
| `greatest-numeric-first` <!-- compat:greatest-numeric-first=same --> | ✅ **Supported** | `GREATEST(10, '20', 5)` | GREATEST where the first argument is numeric, including represented character coercion and NULL propagation. |
| `least-numeric-first` <!-- compat:least-numeric-first=same --> | ✅ **Supported** | `LEAST(10, '20', 5)` | LEAST where the first argument is numeric, including represented character coercion. |
| `substr` <!-- compat:substr=same --> | ✅ **Supported** | `SUBSTR('abcdef', -3, 2)` | Oracle SUBSTR positive/zero/negative start and non-positive length behavior represented by the contract. |
| `instr` <!-- compat:instr=same --> | ✅ **Supported** | `INSTR('abcabc', 'a', 2, 1)` | Oracle INSTR start/occurrence/backward-search behavior and zero-occurrence error semantics represented by the contract. |
| `to-char-date-deterministic` <!-- compat:to-char-date-deterministic=same --> | ✅ **Supported** | `TO_CHAR(DATE '2024-02-29', 'YYYY-MM-DD')` | Proven Oracle DATE conversion using the canonical default form and deterministic numeric date/time format literals. |
| `to-char-number-deterministic` <!-- compat:to-char-number-deterministic=same --> | ✅ **Supported** | `TO_CHAR(123.45)` / `TO_CHAR(?)` / `TO_CHAR(amount, '9990.00')` | Deterministic default numeric TO_CHAR plus represented explicit numeric `9`/`0` format models without NLS-dependent elements. |
| `to-char-timestamp-format-exact-metadata` <!-- compat:to-char-timestamp-format-exact-metadata=reject --> | ⛔ **Rejected** | `TO_CHAR(TIMESTAMP '2024-02-29 12:34:56.123456', 'YYYYMMDD HH24:MI:SS.FF6')` | Explicit TIMESTAMP formatting fails closed where Oracle format-derived JDBC precision cannot be reproduced exactly. |
| `to-char-textual-nls-dependent-format` <!-- compat:to-char-textual-nls-dependent-format=reject --> | ⛔ **Rejected** | `TO_CHAR(DATE '2024-02-29', 'MON')` | Textual date formats whose result depends on NLS language fail closed rather than guessing session state. |
| `to-char-other` <!-- compat:to-char-other=bounded --> | ⚠️ **Supported subset** | `TO_CHAR(value, format)` | Other datatype and format-model combinations remain bounded to explicit evidence. |
| `sysdate` <!-- compat:sysdate=same --> | ✅ **Supported** | `SELECT SYSDATE FROM dual` | SYSDATE shape, statement stability, expression use, and supported day arithmetic represented by canonical cases. |
| `to-date` <!-- compat:to-date=same --> | ✅ **Supported** | `TO_DATE('2026-08-15', 'YYYY-MM-DD')` | Deterministic TO_DATE default/explicit formats, binds, punctuation flexibility, trailing-data validation, invalid-day errors, and the represented year-zero error order. Month-name, YY/RR, BC, NLS-language, and other format-model behavior remains outside this row. |
| `to-timestamp` <!-- compat:to-timestamp=same --> | ✅ **Supported** | `TO_TIMESTAMP('2026-08-15 12:34:56.123', 'YYYY-MM-DD HH24:MI:SS.FF3')` | Non-time-zone TO_TIMESTAMP fractional-second conversion represented by the canonical contract. |
| `to-timestamp-tz-exact` <!-- compat:to-timestamp-tz-exact=reject --> | ⛔ **Rejected** | `TO_TIMESTAMP_TZ('2026-08-15 12:00 +09:00', 'YYYY-MM-DD HH24:MI TZH:TZM')` | Exact Oracle TIMESTAMP WITH TIME ZONE offset identity cannot be preserved by PostgreSQL timestamptz without semantic loss. |
| `last-day` <!-- compat:last-day=same --> | ✅ **Supported** | `LAST_DAY(DATE '2024-02-10')` | LAST_DAY calendar behavior represented by canonical cases. |
| `add-months` <!-- compat:add-months=same --> | ✅ **Supported** | `ADD_MONTHS(DATE '2024-01-31', 1)` | ADD_MONTHS end-of-month, short-month, backward movement, and DATE time preservation represented by the contract. |
| `months-between` <!-- compat:months-between=same --> | ✅ **Supported** | `MONTHS_BETWEEN(DATE '2024-03-31', DATE '2024-02-29')` | MONTHS_BETWEEN same-day, last-day, and represented fractional/time behavior. |
| `date-trunc-round` <!-- compat:date-trunc-round=same --> | ✅ **Supported** | `TRUNC(DATE '2000-06-01', 'CC')` | Oracle date TRUNC/ROUND day, month, year, quarter, century (`CC`/`SCC`) and represented half-unit boundaries. BC and other unrepresented calendar/environment forms remain outside this row. |
| `regexp-like-basic-flags` <!-- compat:regexp-like-basic-flags=same --> | ✅ **Supported** | `REGEXP_LIKE(name, '^a', 'i')` | REGEXP_LIKE basic matching plus explicit `i` and `c` flags represented by canonical cases. Other Oracle regex/NLS flag combinations are not implied. |
| `regexp-count-basic-start` <!-- compat:regexp-count-basic-start=same --> | ✅ **Supported** | `REGEXP_COUNT('abcabc', 'a', 2)` | REGEXP_COUNT basic occurrence counting and represented start-position form. |
| `regexp-instr-basic-occurrence` <!-- compat:regexp-instr-basic-occurrence=same --> | ✅ **Supported** | `REGEXP_INSTR('abcabc', 'a', 1, 2)` | REGEXP_INSTR basic and represented occurrence-selection form. |
| `regexp-substr-basic-occurrence` <!-- compat:regexp-substr-basic-occurrence=same --> | ✅ **Supported** | `REGEXP_SUBSTR('a1b2', '[0-9]', 1, 2)` | REGEXP_SUBSTR basic/occurrence forms and NULL input behavior represented by the contract. |
| `regexp-replace-basic-backref` <!-- compat:regexp-replace-basic-backref=same --> | ✅ **Supported** | `REGEXP_REPLACE('ab', '(a)(b)', '\2\1')` | REGEXP_REPLACE basic replacement and represented capture-group backreference behavior. |
| `listagg-18c-core-forms` <!-- compat:listagg-18c-core-forms=same --> | ✅ **Supported** | `LISTAGG(name, ',') WITHIN GROUP (ORDER BY name)` | Oracle 18c LISTAGG basic and grouped forms, NULL delimiter/measure behavior, descending ordering, explicit ON OVERFLOW ERROR, and represented TRUNCATE forms including a custom indicator without count. |
| `listagg-distinct-18c` <!-- compat:listagg-distinct-18c=reject --> | ⛔ **Rejected** | `LISTAGG(DISTINCT name, ',') WITHIN GROUP (ORDER BY name)` | LISTAGG DISTINCT is not an Oracle 18c feature and fails closed rather than accepting newer-version syntax against the 18c authority. |
| `listagg-other` <!-- compat:listagg-other=bounded --> | ⚠️ **Supported subset** | `LISTAGG(name, ',') WITHIN GROUP (ORDER BY deptno, name)` | Other Oracle 18c LISTAGG ordering, overflow, and composition shapes remain bounded. |

## DML

| Area | Status | Example | Contract |
|---|---|---|---|
| `insert` <!-- compat:insert=same --> | ✅ **Supported** | `INSERT INTO t(id, name) VALUES (seq.NEXTVAL, :name)` | Ordinary single-table INSERT VALUES / INSERT SELECT composition with supported expressions, binds and sequences. |
| `update` <!-- compat:update=same --> | ✅ **Supported** | `UPDATE t SET name = :name WHERE id = :id` | Ordinary UPDATE with supported predicates/expressions/binds and differential post-state verification where relevant. |
| `delete` <!-- compat:delete=same --> | ✅ **Supported** | `DELETE FROM t WHERE id = :id` | Ordinary DELETE with supported predicates and deterministic update-count/post-state observation where relevant. |
| `merge-core-forms` <!-- compat:merge-core-forms=same --> | ✅ **Supported** | `MERGE INTO t USING s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.v=s.v WHEN NOT MATCHED THEN INSERT (id,v) VALUES (s.id,s.v)` | Proven matched UPDATE, not-matched INSERT, combined UPDATE/INSERT, action WHERE clauses, supported expression composition in ON, and Oracle's ON-column-update error contract. |
| `merge-other` <!-- compat:merge-other=bounded --> | ⚠️ **Supported subset** | `WHEN MATCHED THEN UPDATE SET ... DELETE WHERE t.flag='X'` | Other MERGE shapes remain bounded. Oracle `DELETE WHERE` is deliberately fail-closed because its post-update semantics are not reproduced safely. |
| `returning-into-single-row-dml` <!-- compat:returning-into-single-row-dml=same --> | ✅ **Supported** | `INSERT INTO t(v) VALUES (:v) RETURNING id INTO :out_id` | Represented single-row INSERT, UPDATE, and DELETE RETURNING INTO through JDBC OUT binds, including multi-column INSERT RETURNING. |
| `returning-into-other` <!-- compat:returning-into-other=bounded --> | ⚠️ **Supported subset** | `UPDATE t SET ... RETURNING id, value INTO :id, :value` | Other RETURNING cardinality, expression, datatype, and JDBC binding shapes require explicit evidence. |
| `multi-table-insert` <!-- compat:multi-table-insert=reject --> | ⛔ **Rejected** | `INSERT ALL INTO a VALUES (x) INTO b VALUES (x) SELECT x FROM src` | INSERT ALL / INSERT FIRST fail closed. |

## JDBC and routine invocation

| Area | Status | Example | Contract |
|---|---|---|---|
| `routine-call-core-forms` <!-- compat:routine-call-core-forms=same --> | ✅ **Supported** | `BEGIN ? := bs_fn_add(?, ?); END;` | Proven positional/named scalar function calls, procedures with IN/OUT/INOUT binds, named procedures, and package function/procedure calls. |
| `anonymous-plsql-single-dml` <!-- compat:anonymous-plsql-single-dml=same --> | ✅ **Supported** | `BEGIN UPDATE t SET v = ? WHERE id = ?; END;` | An anonymous block containing exactly one INSERT, UPDATE, DELETE, or MERGE statement is unwrapped and executed through the ordinary SQL/JDBC compatibility path. |
| `routine-arbitrary-plsql-block` <!-- compat:routine-arbitrary-plsql-block=reject --> | ⛔ **Rejected** | `BEGIN NULL; NULL; END;` | Multi-statement, control-flow, declaration, exception-handling, and other arbitrary PL/SQL blocks remain outside the bounded runtime and fail closed. |
| `routine-ref-cursor-output` <!-- compat:routine-ref-cursor-output=reject --> | ⛔ **Rejected** | `BEGIN bs_ref_cursor(?, ?); END;` | Standard JDBC REF CURSOR output is explicitly fail-closed because PostgreSQL ResultSet semantics are not mapped to the Oracle CallableStatement contract. |
| `routine-ambiguous-overload` <!-- compat:routine-ambiguous-overload=reject --> | ⛔ **Rejected** | `BEGIN bs_pkg.choose(?, ?); END;` | Ambiguous PostgreSQL routine overload resolution fails closed instead of guessing the Oracle overload selected from JDBC bind types. |
| `routine-invocation-other` <!-- compat:routine-invocation-other=bounded --> | ⚠️ **Supported subset** | `{ call pkg_proc(?, ?) }` | Other routine signatures, overload patterns, datatypes, and call-envelope forms remain bounded to explicit evidence. |
| `jdbc-bind-rewrite-core-forms` <!-- compat:jdbc-bind-rewrite-core-forms=same --> | ✅ **Supported** | `SELECT GREATEST(1, ?) FROM dual` | Proven JDBC bind preservation through represented concatenation, DECODE, GREATEST, SUBSTR, INSTR, REGEXP and LISTAGG rewrite forms, including represented NULL and numeric/character bind types. |
| `jdbc-bind-lineage-other` <!-- compat:jdbc-bind-lineage-other=bounded --> | ⚠️ **Supported subset** | `WHERE a = ? OR b = ?` | Other bind duplication, reordering, empty-string normalization and rewrite compositions remain bounded to explicit evidence. |
| `jdbc-result-metadata-core` <!-- compat:jdbc-result-metadata-core=same --> | ✅ **Supported** | `SELECT CAST(12.34 AS NUMBER(10,2)), DATE '2024-02-29', TIMESTAMP '2024-02-29 12:34:56.123456' FROM dual` | Proven result-column label/name, JDBC type, precision, scale and nullability for represented NUMBER(p,s), DATE/TIMESTAMP literals and DATE-family functions. Oracle type names are owned only by canonical cases that opt into type-name comparison. |
| `jdbc-result-metadata-other` <!-- compat:jdbc-result-metadata-other=bounded --> | ⚠️ **Supported subset** | `SELECT expression AS value FROM dual` | Other expression-derived widths, source-provenance/nullability recovery, bind-dependent metadata and unclassified Oracle type identities remain bounded to explicit evidence. |
| `jdbc-vendor-error-core` <!-- compat:jdbc-vendor-error-core=same --> | ✅ **Supported** | `SELECT TO_NUMBER('not-a-number') FROM dual` | Proven Oracle vendor codes for represented invalid NUMBER (ORA-01722), format mismatch (ORA-01861), invalid numeric domain/overflow (ORA-01428/ORA-01426), invalid date (ORA-01839/ORA-01841/ORA-01843/ORA-01847), scalar-subquery cardinality (ORA-01427), CASE/argument datatype (ORA-00932), sequence placement (ORA-02287), and MERGE ON-column update (ORA-38104) paths. |
| `jdbc-vendor-errors-other` <!-- compat:jdbc-vendor-errors-other=bounded --> | ⚠️ **Supported subset** | delegated PostgreSQL error | Other SQLState/message/vendor-code adaptations remain bounded; Orafit does not infer arbitrary Oracle errors. |
| `jdbc-prepared-parameter-metadata` <!-- compat:jdbc-prepared-parameter-metadata=bounded --> | ⚠️ **Supported subset** | `PreparedStatement#getParameterMetaData()` | Original application bind count/index identity is preserved through represented rewrites, but wider Oracle parameter type/mode metadata remains unproven. |
| `jdbc-callable-parameter-metadata` <!-- compat:jdbc-callable-parameter-metadata=reject --> | ⛔ **Rejected** | `CallableStatement#getParameterMetaData()` | Function, procedure, and RETURNING callable parameter metadata fails closed until Oracle IN/OUT mode/type metadata is modeled exactly. |
| `jdbc-prepared-statement-reuse-batching` <!-- compat:jdbc-prepared-statement-reuse-batching=bounded --> | ⚠️ **Supported subset** | `PreparedStatement` reuse / `addBatch()` | Represented setter replay, reuse and translated prepared batching are covered by product/integration tests; wider driver state interactions remain bounded. |
| `jdbc-callable-statement-reuse` <!-- compat:jdbc-callable-statement-reuse=bounded --> | ⚠️ **Supported subset** | reused function/procedure call | Represented IN/OUT state reset and reuse are protected by product tests; wider callable state and overload interactions remain bounded. |
| `jdbc-transactions-savepoints` <!-- compat:jdbc-transactions-savepoints=bounded --> | ⚠️ **Supported subset** | `commit()` / `rollback(savepoint)` | Standard transaction and savepoint behavior is delegated through pgJDBC and integration-tested; Oracle-specific transaction/session semantics are not implied. |

## Explicitly outside the product boundary

| Area | Status | Example | Reason |
|---|---|---|---|
| `schema-and-administration-ddl` <!-- compat:schema-and-administration-ddl=outside --> | ➖ **Out of scope** | `CREATE TABLESPACE ...` | Schema migration and database administration are separate concerns. |
| `users-roles-privileges-tablespaces` <!-- compat:users-roles-privileges-tablespaces=outside --> | ➖ **Out of scope** | `GRANT DBA TO app_user` | Database administration is not emulated. |
| `plsql-language-and-program-unit-definition` <!-- compat:plsql-language-and-program-unit-definition=outside --> | ➖ **Out of scope** | `CREATE OR REPLACE PROCEDURE ...` | Orafit owns a bounded routine-call boundary, not the PL/SQL language/runtime. |
| `object-collection-lob-special-types` <!-- compat:object-collection-lob-special-types=outside --> | ➖ **Out of scope** | `CREATE TYPE phone_list AS TABLE OF VARCHAR2(20)` | Oracle object/collection/LOB/special-type compatibility is outside the application SQL core. |
| `xml-json-spatial-text-mining-olap` <!-- compat:xml-json-spatial-text-mining-olap=outside --> | ➖ **Out of scope** | `SDO_GEOMETRY(...)` / `XMLTABLE(...)` | Specialized Oracle feature families require separate migration solutions. |
| `nls-collation-region-timezone-dependent-semantics` <!-- compat:nls-collation-region-timezone-dependent-semantics=outside --> | ➖ **Out of scope** | `ALTER SESSION SET NLS_DATE_LANGUAGE='JAPANESE'` | Environment-dependent semantics are not guessed. |
| `distributed-db-links-flashback` <!-- compat:distributed-db-links-flashback=outside --> | ➖ **Out of scope** | `SELECT * FROM emp@remote_db` | Distributed/server execution features are outside the runtime SQL/JDBC layer. |
| `oracle-proprietary-jdbc-types-and-apis` <!-- compat:oracle-proprietary-jdbc-types-and-apis=outside --> | ➖ **Out of scope** | `oracle.sql.STRUCT` / Oracle-specific REF CURSOR APIs | Standard JDBC-facing migration behavior is owned, not the complete proprietary API. |

## Known fail-closed examples

Examples include:

```sql
-- Residual advanced structural query family
SELECT *
FROM (SELECT deptno, job, sal FROM sales)
PIVOT (SUM(sal), COUNT(*) AS cnt FOR job IN ('DEV' AS dev));

-- Multi-table DML
INSERT ALL
  INTO sales_a(id) VALUES (id)
  INTO sales_b(id) VALUES (id)
SELECT id FROM source_rows;

-- Access-path/evaluation-order-sensitive ROWNUM
SELECT *
FROM emp
WHERE ROWNUM <= 10
ORDER BY sal DESC;

-- Hierarchy state that is not represented safely
SELECT id, CONNECT_BY_ISCYCLE
FROM hierarchy_rows
START WITH id = 1
CONNECT BY NOCYCLE PRIOR id = parent_id;

-- MERGE post-update delete state
MERGE INTO target t
USING source s
ON (t.id = s.id)
WHEN MATCHED THEN
  UPDATE SET t.status = s.status
  DELETE WHERE t.status = 'DELETE_ME';

-- Sequence pseudocolumn placement that Oracle rejects
SELECT empno FROM bs_emp WHERE bs_seq.NEXTVAL > 0;

-- Oracle rejects year zero in this represented TO_DATE form
SELECT TO_DATE('0000-01-01', 'YYYY-MM-DD') FROM dual;
```

Other fail-closed boundaries include residual PIVOT/UNPIVOT forms outside the two explicit basic rows, Oracle TIMESTAMP literals above six fractional digits, arbitrary PL/SQL blocks, JDBC REF CURSOR output, ambiguous routine overloads, arbitrary `CONNECT_BY_ROOT` expressions, and exact region/offset-preserving TIMESTAMP WITH TIME ZONE behavior.

Fail-closed is intentional: **a visible migration error is preferred to successful execution with changed Oracle semantics**.

## Current compatibility gap review (2026-09-06)

The 67-case Oracle gap inventory has been re-evaluated against Oracle 18c XE and the embedded PostgreSQL 17.10 contract. The implementation review records **46 cases handled and 21 left unhandled**. The count is an inventory of reviewed cases, not a claim that every spelling, datatype, NLS setting, or composition of a function is compatible.

| Review group | Current result | Boundary recorded by the executable cases |
|---|---|---|
| P1: arithmetic, type checks, dates, and character comparison | 10 handled | NUMBER division precision, mixed-type `NULLIF`/`COALESCE` rejection, DATE subtraction scale, fractional-second truncation for TIMESTAMP-to-DATE, fixed-width `CHAR` length, TO_DATE trailing-input validation, and CHAR/VARCHAR2 equality are covered only in the represented forms. |
| P2: numeric range, pagination, aggregates, set operations, and sequences | 16 handled; 3 deferred | Overflow/underflow, NUMBER rounding, domain errors, FETCH boundary counts, DATE functions, set-operation typing/padding, and one-value-per-row repeated `NEXTVAL` are covered. STDDEV precision, FETCH PERCENT, and mixed TIMESTAMP/DATE subtraction remain explicit deferred boundaries. |
| Former P3: placement and input-validation safety | 2 handled; 5 conditional; 13 deferred | `NEXTVAL` in SELECT `WHERE` is rejected with ORA-02287, and the represented TO_DATE year-zero forms preserve Oracle error precedence. BYTE-oriented functions, Unicode width/encoding, analysis functions, RAW, and ordering statistics remain conditional or deferred until their shared contracts are designed. |

The detailed case-by-case decision record is [`src/test/resources/oracle/incompatibility-decisions.md`](src/test/resources/oracle/incompatibility-decisions.md). Deferred P2 evidence remains in [`src/test/resources/oracle/p2-deferred.toml`](src/test/resources/oracle/p2-deferred.toml); it is not part of the normal compatibility success set. These documents explain why an implementation is bounded or rejected and must be read together with the public matrix and `scope.toml`.

The final validation for this review passed `./mvnw verify` with 672 tests and the Oracle 18c XE versus embedded PostgreSQL 17.10 differential with 501 tests. Those results verify the recorded canonical cases; they do not promote conditional or deferred forms to Supported.

## Migration pre-check

Before changing the JDBC URL, check whether the application depends on Oracle behavior outside the rows above: newer-than-18c syntax, rejected query families, advanced hierarchical SQL, arbitrary PL/SQL, proprietary JDBC classes/types, environment-dependent NLS/timezone behavior, or exact metadata/vendor errors not represented by canonical cases.

From a repository checkout, the compatibility analyzer can measure the SQL shapes actually used by an application:

```bash
./mvnw -q -DskipTests package
java -jar target/orafit-jdbc-*.jar src/main/resources
java -jar target/orafit-jdbc-*.jar --details path/to/sql
```

Directories are scanned recursively for `*.sql` files. The analyzer reuses the same `OrafitEngine`, feature gate, fail-closed codes, and pinned JSqlParser as runtime translation. It reports accepted pass-through/rewritten statements, rejected statements grouped by stable rejection code, out-of-scope statement types, and recognized Oracle-feature frequency. It does not log SQL text in the detailed rejection list.

Analyzer **Accepted** means that the current engine did not reject the statement. It is useful for demand-driven migration triage, but it is not a replacement for a public **Supported** row: public compatibility claims still require explicit Oracle 18c canonical evidence in this matrix.

If an important form is not clearly covered here, treat it as **unproven**, not supported by implication. Check the executable cases under [`src/test/resources/oracle/cases`](src/test/resources/oracle/cases/) or open an issue with a minimal Oracle 18c example.

## Evidence model

Every release-authority commit is tested against Oracle Database 18c XE and compared with the supported PostgreSQL targets. The differential contract observes values, NULL behavior, Oracle-visible errors, binds, OUT/RETURNING behavior, result metadata, update counts, and deterministic DML post-state where relevant.

A public Supported classification carries explicit canonical-case evidence in `scope.toml`; the scope validator checks those references against the executable inventory. The broader safety corpus separately proves that unsupported syntax does not become unsafe passthrough or unsafe acceptance.

For implementation-level evidence and maintenance rules, see:

- [`src/test/resources/oracle/scope.toml`](../src/test/resources/oracle/scope.toml)
- [`compat/oracle/cases/`](../compat/oracle/cases/)
- [Compatibility maintenance](../maintainers/compatibility.md)
- [Validation](../maintainers/validation.md)
- [Practical compatibility audit](../maintainers/compatibility-audit.md)
