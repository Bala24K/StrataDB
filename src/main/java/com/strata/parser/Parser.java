package com.strata.parser;

import com.strata.lexer.Lexer;
import com.strata.lexer.Token;
import com.strata.lexer.TokenType;
import com.strata.parser.ast.ColumnDef;
import com.strata.parser.ast.ColumnType;
import com.strata.parser.ast.Expr;
import com.strata.parser.ast.Statement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A hand-written <b>recursive-descent</b> parser: one private method per grammar rule, each
 * one calling the methods for the rules just below it. This turns a flat list of {@link Token}s
 * (from {@link Lexer}) into a {@link Statement} AST — currently one of {@code CREATE TABLE},
 * {@code INSERT}, {@code UPDATE}, {@code DELETE}, or {@code SELECT}.
 *
 * <h2>How operator precedence falls out of the call structure</h2>
 * WHERE-clause expressions need {@code OR} to bind more loosely than {@code AND}, which in turn
 * binds more loosely than comparisons (so {@code a = 1 OR b = 2 AND c = 3} means
 * {@code a = 1 OR (b = 2 AND c = 3)}, not {@code (a = 1 OR b = 2) AND c = 3}). Recursive descent
 * gets this for free from how the parsing methods call each other, without any explicit
 * precedence table:
 *
 * <pre>
 * parseExpression → parseOr → parseAnd → parseComparison → parsePrimaryExpr
 * </pre>
 *
 * Each method first asks the method below it for a complete sub-expression, *then* looks for
 * its own operator. Since {@code parseOr} calls {@code parseAnd} (not the other way around),
 * an {@code AND} always ends up nested *inside* an {@code OR} it appears next to, never the
 * reverse — which is exactly the precedence we want. Parentheses (handled in
 * {@code parsePrimaryExpr}) let a query override this by recursing straight back to
 * {@code parseExpression}, resetting precedence from scratch inside the parens.
 *
 * <h2>Why the parser doesn't depend on the catalog</h2>
 * {@link ColumnType} deliberately duplicates {@link com.strata.catalog.DataType}'s four values
 * instead of reusing them. A parser's job is purely syntactic — it shouldn't need to know a
 * "system catalog" module even exists to do its job, and keeping it catalog-free means it can
 * be built, tested, and trusted in complete isolation (see the test suite: not one test here
 * touches disk). Translating {@code ast.ColumnType} into {@code catalog.DataType} is semantic
 * work that belongs to whatever consumes the AST next (the executor, Phase 5).
 *
 * <h2>What's deliberately not supported yet</h2>
 * <ul>
 *   <li>No JOINs, subqueries, or {@code GROUP BY}/aggregates.</li>
 *   <li>No {@code NOT} in expressions, even though the lexer already recognizes the keyword.</li>
 *   <li>No arithmetic beyond unary minus on an integer literal (see {@link #parseLiteral}).</li>
 *   <li>One statement per {@link #parse(String)} call — trailing text after the closing
 *       {@code ;} is rejected rather than treated as a second statement.</li>
 * </ul>
 */
public final class Parser {

    private static final Set<TokenType> COMPARISON_OPERATORS = EnumSet.of(
        TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.LESS_THAN,
        TokenType.GREATER_THAN, TokenType.LESS_EQUALS, TokenType.GREATER_EQUALS
    );

    private final List<Token> tokens;
    private int current = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Lexes and parses {@code sql} in one shot. This is the entry point most callers want. */
    public static Statement parse(String sql) {
        return parse(Lexer.tokenize(sql));
    }

    /** Parses an already-tokenized statement. */
    public static Statement parse(List<Token> tokens) {
        Parser parser = new Parser(tokens);
        Statement statement = parser.parseStatement();
        parser.expect(TokenType.SEMICOLON, "Expected ';' at the end of the statement");
        if (!parser.isAtEnd()) {
            throw parser.error("Unexpected extra input after the statement's ';'", parser.peek());
        }
        return statement;
    }

    private Statement parseStatement() {
        return switch (peek().getType()) {
            case CREATE -> parseCreate();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case SELECT -> parseSelect();
            case BEGIN -> parseBegin();
            case COMMIT -> parseCommit();
            case ROLLBACK -> parseRollback();
            default -> throw error("Expected a statement (CREATE, INSERT, UPDATE, DELETE, SELECT, BEGIN, COMMIT, or ROLLBACK)", peek());
        };
    }

    // ---- BEGIN / COMMIT / ROLLBACK ----

    private Statement.Begin parseBegin() {
        expect(TokenType.BEGIN);
        return new Statement.Begin();
    }

    private Statement.Commit parseCommit() {
        expect(TokenType.COMMIT);
        return new Statement.Commit();
    }

    private Statement.Rollback parseRollback() {
        expect(TokenType.ROLLBACK);
        return new Statement.Rollback();
    }

    // ---- CREATE TABLE / CREATE INDEX ----

    /** {@code CREATE} is shared by two statement kinds; the token right after it decides which. */
    private Statement parseCreate() {
        expect(TokenType.CREATE);
        return switch (peek().getType()) {
            case TABLE -> parseCreateTableBody();
            case INDEX -> parseCreateIndexBody();
            default -> throw error("Expected 'TABLE' or 'INDEX' after 'CREATE'", peek());
        };
    }

    private Statement.CreateTable parseCreateTableBody() {
        expect(TokenType.TABLE);
        String tableName = expectIdentifier("a table name");
        expect(TokenType.LPAREN);

        List<ColumnDef> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (match(TokenType.COMMA)) {
            columns.add(parseColumnDef());
        }
        expect(TokenType.RPAREN);
        return new Statement.CreateTable(tableName, columns);
    }

    private Statement.CreateIndex parseCreateIndexBody() {
        expect(TokenType.INDEX);
        String indexName = expectIdentifier("an index name");
        expect(TokenType.ON);
        String tableName = expectIdentifier("a table name");
        expect(TokenType.LPAREN);
        String columnName = expectIdentifier("a column name");
        expect(TokenType.RPAREN);
        return new Statement.CreateIndex(indexName, tableName, columnName);
    }

    private ColumnDef parseColumnDef() {
        String name = expectIdentifier("a column name");
        ColumnType type = parseColumnType();
        return new ColumnDef(name, type);
    }

    private ColumnType parseColumnType() {
        Token token = advance();
        return switch (token.getType()) {
            case INTEGER -> ColumnType.INTEGER;
            case BIGINT -> ColumnType.BIGINT;
            case VARCHAR -> ColumnType.VARCHAR;
            case BOOLEAN -> ColumnType.BOOLEAN;
            default -> throw error("Expected a data type (INTEGER, BIGINT, VARCHAR, or BOOLEAN)", token);
        };
    }

    // ---- INSERT ----

    private Statement.Insert parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String tableName = expectIdentifier("a table name");

        Optional<List<String>> columnNames = Optional.empty();
        if (match(TokenType.LPAREN)) {
            List<String> names = new ArrayList<>();
            names.add(expectIdentifier("a column name"));
            while (match(TokenType.COMMA)) {
                names.add(expectIdentifier("a column name"));
            }
            expect(TokenType.RPAREN);
            columnNames = Optional.of(names);
        }

        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<Expr.Literal> values = new ArrayList<>();
        values.add(parseLiteral());
        while (match(TokenType.COMMA)) {
            values.add(parseLiteral());
        }
        expect(TokenType.RPAREN);

        return new Statement.Insert(tableName, columnNames, values);
    }

    // ---- UPDATE ----

    private Statement.Update parseUpdate() {
        expect(TokenType.UPDATE);
        String tableName = expectIdentifier("a table name");
        expect(TokenType.SET);

        List<Statement.Assignment> assignments = new ArrayList<>();
        assignments.add(parseAssignment());
        while (match(TokenType.COMMA)) {
            assignments.add(parseAssignment());
        }

        Optional<Expr> where = parseOptionalWhere();
        return new Statement.Update(tableName, assignments, where);
    }

    private Statement.Assignment parseAssignment() {
        String columnName = expectIdentifier("a column name");
        expect(TokenType.EQUALS, "Expected '=' in SET clause");
        Expr.Literal value = parseLiteral();
        return new Statement.Assignment(columnName, value);
    }

    // ---- DELETE ----

    private Statement.Delete parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        String tableName = expectIdentifier("a table name");
        Optional<Expr> where = parseOptionalWhere();
        return new Statement.Delete(tableName, where);
    }

    // ---- SELECT ----

    private Statement.Select parseSelect() {
        expect(TokenType.SELECT);
        List<Statement.SelectItem> items = parseSelectItems();
        expect(TokenType.FROM);
        String tableName = expectIdentifier("a table name");
        Optional<Expr> where = parseOptionalWhere();
        List<Statement.OrderByItem> orderBy = parseOptionalOrderBy();
        Optional<Long> limit = parseOptionalLimit();
        return new Statement.Select(items, tableName, where, orderBy, limit);
    }

    private List<Statement.SelectItem> parseSelectItems() {
        if (match(TokenType.STAR)) {
            return List.of(new Statement.SelectItem.Star());
        }
        List<Statement.SelectItem> items = new ArrayList<>();
        items.add(new Statement.SelectItem.Column(expectIdentifier("a column name")));
        while (match(TokenType.COMMA)) {
            items.add(new Statement.SelectItem.Column(expectIdentifier("a column name")));
        }
        return items;
    }

    private List<Statement.OrderByItem> parseOptionalOrderBy() {
        if (!match(TokenType.ORDER)) {
            return List.of();
        }
        expect(TokenType.BY, "Expected 'BY' after 'ORDER'");
        List<Statement.OrderByItem> items = new ArrayList<>();
        items.add(parseOrderByItem());
        while (match(TokenType.COMMA)) {
            items.add(parseOrderByItem());
        }
        return items;
    }

    private Statement.OrderByItem parseOrderByItem() {
        String columnName = expectIdentifier("a column name");
        Statement.SortDirection direction = Statement.SortDirection.ASC;
        if (match(TokenType.DESC)) {
            direction = Statement.SortDirection.DESC;
        } else {
            match(TokenType.ASC); // optional and the default anyway, but consume it if present
        }
        return new Statement.OrderByItem(columnName, direction);
    }

    private Optional<Long> parseOptionalLimit() {
        if (!match(TokenType.LIMIT)) {
            return Optional.empty();
        }
        Token token = expect(TokenType.INTEGER_LITERAL, "Expected an integer after 'LIMIT'");
        return Optional.of((Long) token.getLiteral());
    }

    // ---- shared: WHERE-clause expressions (see class docs for the precedence explanation) ----

    private Optional<Expr> parseOptionalWhere() {
        if (match(TokenType.WHERE)) {
            return Optional.of(parseExpression());
        }
        return Optional.empty();
    }

    private Expr parseExpression() {
        return parseOr();
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (match(TokenType.OR)) {
            Expr right = parseAnd();
            left = new Expr.Binary(left, Expr.Operator.OR, right);
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseComparison();
        while (match(TokenType.AND)) {
            Expr right = parseComparison();
            left = new Expr.Binary(left, Expr.Operator.AND, right);
        }
        return left;
    }

    private Expr parseComparison() {
        Expr left = parsePrimaryExpr();
        if (COMPARISON_OPERATORS.contains(peek().getType())) {
            Token operatorToken = advance();
            Expr right = parsePrimaryExpr();
            return new Expr.Binary(left, toOperator(operatorToken.getType()), right);
        }
        return left;
    }

    private Expr parsePrimaryExpr() {
        if (match(TokenType.LPAREN)) {
            Expr inner = parseExpression();
            expect(TokenType.RPAREN);
            return inner;
        }
        if (check(TokenType.IDENTIFIER)) {
            return new Expr.ColumnRef(advance().getText());
        }
        return parseLiteral();
    }

    private Expr.Operator toOperator(TokenType type) {
        return switch (type) {
            case EQUALS -> Expr.Operator.EQUALS;
            case NOT_EQUALS -> Expr.Operator.NOT_EQUALS;
            case LESS_THAN -> Expr.Operator.LESS_THAN;
            case GREATER_THAN -> Expr.Operator.GREATER_THAN;
            case LESS_EQUALS -> Expr.Operator.LESS_EQUALS;
            case GREATER_EQUALS -> Expr.Operator.GREATER_EQUALS;
            default -> throw new IllegalStateException("Not a comparison operator token: " + type);
        };
    }

    // ---- shared: literals (INSERT VALUES, UPDATE SET, and expression primaries all use this) ----

    private Expr.Literal parseLiteral() {
        Token token = advance();
        return switch (token.getType()) {
            case INTEGER_LITERAL -> new Expr.Literal.IntegerLiteral((Long) token.getLiteral());
            case STRING_LITERAL -> new Expr.Literal.StringLiteral((String) token.getLiteral());
            case TRUE -> new Expr.Literal.BooleanLiteral(true);
            case FALSE -> new Expr.Literal.BooleanLiteral(false);
            case NULL -> new Expr.Literal.NullLiteral();
            case MINUS -> {
                Token next = expect(TokenType.INTEGER_LITERAL, "Expected an integer literal after unary '-'");
                yield new Expr.Literal.IntegerLiteral(-(Long) next.getLiteral());
            }
            default -> throw error("Expected a literal value (number, string, TRUE, FALSE, or NULL)", token);
        };
    }

    // ---- low-level token stream helpers ----

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return peek().getType() == TokenType.EOF;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && peek().getType() == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type) {
        return expect(type, "Expected '" + type + "'");
    }

    private Token expect(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw error(message, peek());
    }

    private String expectIdentifier(String what) {
        return expect(TokenType.IDENTIFIER, "Expected " + what).getText();
    }

    private ParseException error(String message, Token token) {
        String found = token.getType() == TokenType.EOF ? "end of input" : "'" + token.getText() + "'";
        return new ParseException(message + ", but found " + found, token.getLine(), token.getColumn());
    }
}
