# Phase 4 — SQL Parser

Goal for this phase: turn the token stream from Phase 3 into a tree that represents a SQL
statement's actual structure — supporting `CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`, and
`SELECT` (with `WHERE`, `ORDER BY`, and `LIMIT` clauses, ready for Phase 5's executor to run).

## 1. What "parsing" means here

The lexer already turned `SELECT * FROM users WHERE id = 1;` into a flat list of tokens. A flat
list doesn't capture *structure*, though — it doesn't say "the WHERE clause belongs to this
SELECT" or "these two things are being compared to each other." Parsing recovers that structure,
producing a tree — the **AST** (Abstract Syntax Tree) — where each node's children are exactly
its meaningful sub-parts. `Statement.Select`'s `where` field holds an `Expr`, which itself might
be a whole tree of comparisons joined by `AND`/`OR`.

## 2. Recursive descent: one method per grammar rule

`Parser` is a classic **recursive-descent** parser: each kind of thing the grammar can produce
gets its own private method, and those methods call each other exactly the way the grammar rules
refer to each other. `parseCreateTable()` calls `parseColumnDef()` in a loop because a
`CREATE TABLE`'s grammar rule *contains* a list of column definitions. There's no separate
"parser generator" tool involved — this is hand-written, matching the "built from first
principles" spirit of the whole project, and it's genuinely how a large fraction of real-world
parsers (including production SQL parsers) are still written today.

## 3. Operator precedence, for free, from how methods call each other

This is the one idea in this phase worth really sitting with. `a = 1 OR b = 2 AND c = 3` has to
mean `a = 1 OR (b = 2 AND c = 3)` — `AND` binds tighter than `OR`, the same way `*` binds tighter
than `+` in arithmetic. Nothing in the code says "AND has precedence 2 and OR has precedence 1"
anywhere explicitly. Instead, look at the chain of calls:

```
parseExpression → parseOr → parseAnd → parseComparison → parsePrimaryExpr
```

Each method's very first move is to ask the method **below** it for a complete sub-expression,
and only *then* does it check for its own operator. `parseOr` calls `parseAnd` to get its left
side — which means any `AND` next to an `OR` gets fully absorbed into that `parseAnd` call before
`parseOr` even starts looking for `OR` tokens. The `AND` ends up *nested inside* the `OR`'s tree,
never the other way around, purely as a side effect of which method calls which. Rules that are
parsed by methods *deeper* in this chain end up binding *tighter* in the resulting tree. This
technique is usually called **precedence climbing**, and it generalizes to as many precedence
levels as a grammar needs, just by inserting another method into the chain.

Parentheses are the escape hatch: `parsePrimaryExpr` sees `(`, and instead of trying to figure
out precedence itself, just calls all the way back to `parseExpression` — resetting precedence
from scratch — before requiring a matching `)`. That's why `(a = 1 OR b = 2) AND c = 3` correctly
comes out the other way around; both cases are tested directly in `ParserTest`.

## 4. Why `sealed interface` + `record` is a genuinely good fit for an AST

Every node in `Statement.java` and `Expr.java` is a Java `record`, and the two top-level types
are `sealed interface`s. Three things fall out of that combination almost for free:

- **Structural equality, with zero code.** Records generate `equals()`/`hashCode()` based on
  their components automatically. That's what lets every test in `ParserTest` just build the
  *expected* tree by hand and compare it to the parsed one with a single `assertEquals` — no
  hand-written AST comparison logic needed anywhere.
- **Exhaustiveness the compiler enforces.** Because `Expr` is `sealed` and lists every possible
  subtype in one file, a future `switch` over an `Expr` (the executor, next phase, will need
  exactly this to evaluate a `WHERE` clause) can omit a `default` branch — and if a new kind of
  expression is ever added later, every unhandled `switch` becomes a compile error, not a bug
  discovered at runtime.
- **Immutability by construction.** A `record`'s fields are final and set once in its
  constructor. An AST that can't be mutated after it's built is one fewer category of bug to
  worry about once a tree is handed off to the executor.

## 5. Two nested sealed hierarchies, on purpose

`Statement` and `Expr` are each declared with all of their variants nested directly inside them,
in one file apiece (Java lets a `sealed` type's `permits` list be inferred when every direct
subtype is declared in the same source file — so it doesn't even need to be written out). This
means `Statement.java` reads, top to bottom, as the complete specification of "every SQL
statement StrataDB understands," and the same for `Expr.java` and expressions.

`Expr.Literal` is itself a nested `sealed interface`, not just a flat member of `Expr`. That's
what lets `Statement.Insert`'s `values` field be typed as `List<Expr.Literal>` rather than
`List<Expr>` — an `INSERT ... VALUES (...)` is only ever allowed to contain literal values, never
a column reference or a comparison, and typing that field precisely means the Java compiler
enforces it. There's no runtime check anywhere asking "is this actually a literal?" — it's
simply impossible to construct an `Insert` that holds anything else.

## 6. Why the parser doesn't know the catalog exists

`ast.ColumnType` (used in `CREATE TABLE`) has the exact same four values as
`catalog.DataType` — and yet they're two separate types, not one shared enum. This is
deliberate: a parser's job is purely syntactic (does this text form a valid statement?), while
knowing what `catalog.DataType` even means is a semantic concern that belongs to whichever
module actually talks to the catalog. Keeping the parser catalog-free means it can be fully
tested — as it is, in `ParserTest` — without ever touching disk or constructing a `DiskManager`.
Translating a parsed `ast.ColumnType` into a real `catalog.DataType` becomes the executor's job
next phase, at the one point where the two worlds need to meet.

## 7. Errors, consistently, across the whole front end

`ParseException` carries a line and column, exactly like `LexException` did in Phase 3 — and for
the same reason: a `Token` already knows where it came from, so threading that through to a
parse error costs nothing and turns "expected a table name" into "expected a table name, but
found ';' (line 1, column 14)," which is a substantially more useful message.

## 8. What Phase 4 deliberately does not support yet

- **No JOINs, subqueries, or `GROUP BY`/aggregates.** These need real query planning
  (Phase 10) to make sense of; adding their syntax now with nothing downstream to execute them
  would be premature.
- **No `NOT` in expressions**, even though the lexer already recognizes the keyword (from
  Phase 3) — the comparison/`AND`/`OR` grammar here doesn't have a place for it yet.
- **No arithmetic beyond unary minus** on an integer literal — consistent with the lexer's
  own scope from Phase 3.
- **One statement per `Parser.parse()` call.** Trailing content after a statement's `;` is
  rejected outright rather than treated as a second statement; a REPL or network server
  (Phase 12) will be the place that splits multi-statement input and calls `parse()` once per
  statement.

## 9. What to look at in the code

- `src/main/java/com/strata/parser/ast/Statement.java` — every statement kind, nested.
- `src/main/java/com/strata/parser/ast/Expr.java` — every expression kind, nested, including
  the `Literal` sub-hierarchy.
- `src/main/java/com/strata/parser/ast/ColumnDef.java` / `ColumnType.java` — the small,
  catalog-independent building blocks `CREATE TABLE` needs.
- `src/main/java/com/strata/parser/Parser.java` — the recursive-descent parser itself.
- `src/main/java/com/strata/parser/ParseException.java` — parse errors with position info.
- `src/test/java/com/strata/parser/ParserTest.java` — 24 tests covering all five statement
  kinds, `OR`/`AND` precedence (and parentheses overriding it), `ORDER BY`/`LIMIT`, and error
  cases like missing semicolons, trailing content, and unknown statement keywords.
