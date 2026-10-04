# Phase 3 — SQL Lexer

Goal for this phase: turn SQL text into a stream of tokens. Nothing about grammar or statement
structure yet — that's the parser's job next phase. Given

```sql
SELECT * FROM Student;
```

this phase's `Lexer` produces exactly:

```
SELECT, STAR, FROM, IDENTIFIER("Student"), SEMICOLON, EOF
```

## 1. Why split lexing and parsing into two separate phases at all?

You could, in principle, write one big function that reads SQL text and directly produces an
AST, character by character. Almost nobody does, because two much smaller, focused problems are
easier to get right than one tangled one:

- **Lexing** answers "what are the meaningful chunks of text here?" — turning a stream of
  *characters* into a stream of *tokens* (words, numbers, symbols).
- **Parsing** (Phase 4) answers "what SQL statement do these tokens form?" — turning a stream of
  *tokens* into a tree that represents the statement's structure.

Separating them means the parser never has to think about whitespace, comments, or where a
keyword ends and the next word begins — by the time it runs, all of that's already resolved into
a clean list of `Token`s.

## 2. Scanning: two pointers walking the source string

The `Lexer` keeps two indices into the source text: `start` (where the token currently being
scanned began) and `current` (the next character to look at). The main loop:

1. Skips over whitespace and `--` comments.
2. Records `start = current` — this is where the next real token begins.
3. Looks at one character to decide what *kind* of token is starting (a digit means a number, a
   quote means a string, a letter means an identifier or keyword, anything else is checked
   against the fixed set of symbols), then consumes however many more characters that kind of
   token needs.
4. The token's raw text is always just `source.substring(start, current)` once step 3 finishes —
   whatever got consumed between the two pointers.

This is about as close to "the standard way to write a hand-rolled scanner" as it gets, and for
good reason: it's simple, and its performance is linear in the length of the input with no
backtracking.

## 3. Keywords are just identifiers, classified after the fact

Notice that `SELECT` and `Student` are scanned by the *exact same code path* —
`scanIdentifierOrKeyword`: a letter or underscore, then any run of letters/digits/underscores.
The only difference is what happens after the raw text is collected: it's looked up (uppercased)
in a fixed `KEYWORDS` map, and if it's there, that keyword's `TokenType` is used instead of the
generic `IDENTIFIER`.

This is *why* `SELECT`, `select`, and `SeLeCt` all become the same `TokenType.SELECT` token —
matching the SQL convention that keywords are case-insensitive — while an identifier like
`Student` keeps exactly the casing the user typed, in its `Token.getText()`. Real databases
differ on whether they then *fold* an unquoted identifier's case (Postgres lowercases it,
others preserve it); StrataDB simply preserves it, which is the simpler rule to reason about.

## 4. Deciding what belongs in the lexer vs. the parser

A concrete example: what should `-5` produce? You might expect a single "negative number"
token. The lexer instead produces **two** tokens — `MINUS` then `INTEGER_LITERAL(5)` — and
leaves it entirely to the parser to decide whether that `MINUS` means "negate the following
literal" or something else (like subtraction, if StrataDB ever grows arithmetic expressions).

This is a real, general lexer/parser design principle, not just an arbitrary choice here: the
lexer's job is to recognize the smallest meaningful *lexical* units; anything that depends on
*where a token sits relative to its neighbors* is a grammar question, which belongs to the
parser. Baking "a minus immediately before a number means negative" into the lexer would work
for this one case but starts to blur the boundary the moment the grammar grows more expressions
— easier to keep that boundary sharp from the start.

## 5. String literals: escaping is a lexer concern

A quoted string like `'it''s'` needs its surrounding quotes stripped and its escaping resolved
before anyone above the lexer should ever see it — nobody parsing a `WHERE name = 'it''s'`
clause should have to know that SQL strings escape an embedded quote by doubling it. That's why
`Token` carries both `getText()` (`"'it''s'"`, the exact raw source, useful for error messages
that want to show what the user actually typed) and `getLiteral()` (`"it's"`, the fully resolved
value the rest of the system will actually use).

## 6. Errors carry a position, on purpose

`LexException` always carries a line and column, not just a message. A message like "unexpected
character '@'" is much less useful without "at line 3, column 12" attached — and since this
information is already being tracked for every token anyway (see `Token.getLine()`/`getColumn()`),
threading it through to error messages costs nothing extra. This groundwork is what will let the
parser (and eventually a REPL) point directly at the offending part of a query instead of just
saying "syntax error" and leaving the user to guess.

## 7. What Phase 3 deliberately does not do

- **No block comments** (`/* ... */`) — only `--` line comments. Easy to add later; left out to
  keep this phase's scope matched to what the parser actually needs next.
- **No arithmetic beyond unary minus.** There's no `PLUS`, `SLASH`, or general expression
  grammar yet — nothing in Phase 4 or 5's planned SQL subset needs it yet.
- **No floating-point literals.** There's no `DOUBLE`/`FLOAT` type in the catalog
  (`com.strata.catalog.DataType`) for one to represent.
- **No identifier quoting** (e.g. `"Weird Column Name"`) — every identifier must be a plain
  word; quoted identifiers are a reasonable future addition if they're ever needed.

## 8. What to look at in the code

- `src/main/java/com/strata/lexer/TokenType.java` — every token category the lexer produces.
- `src/main/java/com/strata/lexer/Token.java` — one token: type, raw text, optional resolved
  literal value, and position.
- `src/main/java/com/strata/lexer/LexException.java` — errors with a line/column attached.
- `src/main/java/com/strata/lexer/Lexer.java` — the scanner itself.
- `src/test/java/com/strata/lexer/TokenTest.java` and `LexerTest.java` — 20 tests, including
  the exact `SELECT * FROM Student;` example from the project plan, case-insensitive keywords,
  string escaping, comparison operators (`<>` and `!=` both meaning "not equals"), comment
  skipping, and precise line/column tracking across multiple lines.
