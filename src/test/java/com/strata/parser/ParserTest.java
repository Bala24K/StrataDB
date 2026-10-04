package com.strata.parser;

import static com.strata.testutil.Assert.*;

import com.strata.parser.ast.ColumnDef;
import com.strata.parser.ast.ColumnType;
import com.strata.parser.ast.Expr;
import com.strata.parser.ast.Statement;

import java.util.List;
import java.util.Optional;

public class ParserTest {

    // ---- CREATE TABLE ----

    public void testCreateTableSingleColumn() {
        Statement stmt = Parser.parse("CREATE TABLE users (id INTEGER);");
        assertEquals(new Statement.CreateTable("users", List.of(new ColumnDef("id", ColumnType.INTEGER))), stmt);
    }

    public void testCreateTableAllFourDataTypes() {
        Statement stmt = Parser.parse("CREATE TABLE users (id INTEGER, name VARCHAR, balance BIGINT, active BOOLEAN);");
        assertEquals(new Statement.CreateTable("users", List.of(
            new ColumnDef("id", ColumnType.INTEGER),
            new ColumnDef("name", ColumnType.VARCHAR),
            new ColumnDef("balance", ColumnType.BIGINT),
            new ColumnDef("active", ColumnType.BOOLEAN)
        )), stmt);
    }

    public void testCreateTableMissingOpenParenThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("CREATE TABLE users id INTEGER);"));
    }

    // ---- INSERT ----

    public void testInsertWithoutColumnList() {
        Statement stmt = Parser.parse("INSERT INTO users VALUES (1, 'Alice', TRUE);");
        assertEquals(new Statement.Insert("users", Optional.empty(), List.of(
            new Expr.Literal.IntegerLiteral(1),
            new Expr.Literal.StringLiteral("Alice"),
            new Expr.Literal.BooleanLiteral(true)
        )), stmt);
    }

    public void testInsertWithExplicitColumnList() {
        Statement stmt = Parser.parse("INSERT INTO users (id, name) VALUES (1, 'Alice');");
        assertEquals(new Statement.Insert("users", Optional.of(List.of("id", "name")), List.of(
            new Expr.Literal.IntegerLiteral(1), new Expr.Literal.StringLiteral("Alice")
        )), stmt);
    }

    public void testInsertWithNullAndNegativeLiteral() {
        Statement stmt = Parser.parse("INSERT INTO t VALUES (NULL, -5);");
        assertEquals(new Statement.Insert("t", Optional.empty(), List.of(
            new Expr.Literal.NullLiteral(), new Expr.Literal.IntegerLiteral(-5)
        )), stmt);
    }

    // ---- UPDATE ----

    public void testUpdateWithWhere() {
        Statement stmt = Parser.parse("UPDATE users SET name = 'Bob', active = FALSE WHERE id = 1;");
        assertEquals(new Statement.Update("users", List.of(
            new Statement.Assignment("name", new Expr.Literal.StringLiteral("Bob")),
            new Statement.Assignment("active", new Expr.Literal.BooleanLiteral(false))
        ), Optional.of(new Expr.Binary(
            new Expr.ColumnRef("id"), Expr.Operator.EQUALS, new Expr.Literal.IntegerLiteral(1)
        ))), stmt);
    }

    public void testUpdateWithoutWhere() {
        Statement stmt = Parser.parse("UPDATE users SET active = TRUE;");
        assertEquals(new Statement.Update("users",
            List.of(new Statement.Assignment("active", new Expr.Literal.BooleanLiteral(true))),
            Optional.empty()), stmt);
    }

    // ---- DELETE ----

    public void testDeleteWithWhere() {
        Statement stmt = Parser.parse("DELETE FROM users WHERE id = 5;");
        assertEquals(new Statement.Delete("users", Optional.of(new Expr.Binary(
            new Expr.ColumnRef("id"), Expr.Operator.EQUALS, new Expr.Literal.IntegerLiteral(5)
        ))), stmt);
    }

    public void testDeleteWithoutWhereMeansEveryRow() {
        Statement stmt = Parser.parse("DELETE FROM users;");
        assertEquals(new Statement.Delete("users", Optional.empty()), stmt);
    }

    // ---- SELECT ----

    public void testSelectStar() {
        Statement stmt = Parser.parse("SELECT * FROM users;");
        assertEquals(new Statement.Select(
            List.of(new Statement.SelectItem.Star()), "users", Optional.empty(), List.of(), Optional.empty()
        ), stmt);
    }

    public void testSelectSpecificColumns() {
        Statement stmt = Parser.parse("SELECT id, name FROM users;");
        assertEquals(new Statement.Select(
            List.of(new Statement.SelectItem.Column("id"), new Statement.SelectItem.Column("name")),
            "users", Optional.empty(), List.of(), Optional.empty()
        ), stmt);
    }

    public void testSelectWithWhereAnd() {
        Statement stmt = Parser.parse("SELECT * FROM users WHERE id = 1 AND active = TRUE;");
        Expr expectedWhere = new Expr.Binary(
            new Expr.Binary(new Expr.ColumnRef("id"), Expr.Operator.EQUALS, new Expr.Literal.IntegerLiteral(1)),
            Expr.Operator.AND,
            new Expr.Binary(new Expr.ColumnRef("active"), Expr.Operator.EQUALS, new Expr.Literal.BooleanLiteral(true))
        );
        assertEquals(new Statement.Select(
            List.of(new Statement.SelectItem.Star()), "users", Optional.of(expectedWhere), List.of(), Optional.empty()
        ), stmt);
    }

    public void testOrBindsLooserThanAnd() {
        // "a = 1 OR b = 2 AND c = 3" must parse as "a = 1 OR (b = 2 AND c = 3)"
        Statement.Select select = (Statement.Select) Parser.parse("SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3;");
        Expr.Binary top = (Expr.Binary) select.where().orElseThrow();

        assertEquals(Expr.Operator.OR, top.operator(), "OR must be the outermost, loosest-binding operator");
        assertTrue(top.right() instanceof Expr.Binary, "OR's right side must be the nested AND sub-expression");
        assertEquals(Expr.Operator.AND, ((Expr.Binary) top.right()).operator());
    }

    public void testParenthesesOverridePrecedence() {
        // "(a = 1 OR b = 2) AND c = 3" — parens force the OR to bind tighter than its default
        Statement.Select select = (Statement.Select) Parser.parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3;");
        Expr.Binary top = (Expr.Binary) select.where().orElseThrow();

        assertEquals(Expr.Operator.AND, top.operator(), "AND must now be the outermost operator");
        assertTrue(top.left() instanceof Expr.Binary, "AND's left side must be the parenthesized OR sub-expression");
        assertEquals(Expr.Operator.OR, ((Expr.Binary) top.left()).operator());
    }

    public void testSelectWithOrderByAndLimit() {
        Statement stmt = Parser.parse("SELECT * FROM users ORDER BY name DESC LIMIT 10;");
        assertEquals(new Statement.Select(
            List.of(new Statement.SelectItem.Star()), "users", Optional.empty(),
            List.of(new Statement.OrderByItem("name", Statement.SortDirection.DESC)),
            Optional.of(10L)
        ), stmt);
    }

    public void testOrderByDefaultsToAscending() {
        Statement.Select select = (Statement.Select) Parser.parse("SELECT * FROM users ORDER BY name;");
        assertEquals(Statement.SortDirection.ASC, select.orderBy().get(0).direction());
    }

    public void testOrderByMultipleColumns() {
        Statement.Select select = (Statement.Select) Parser.parse("SELECT * FROM users ORDER BY balance DESC, name ASC;");
        assertEquals(List.of(
            new Statement.OrderByItem("balance", Statement.SortDirection.DESC),
            new Statement.OrderByItem("name", Statement.SortDirection.ASC)
        ), select.orderBy());
    }

    public void testFullSelectWithWhereOrderByAndLimit() {
        Statement stmt = Parser.parse("SELECT id, name FROM users WHERE balance > 100 ORDER BY balance DESC LIMIT 5;");
        assertEquals(new Statement.Select(
            List.of(new Statement.SelectItem.Column("id"), new Statement.SelectItem.Column("name")),
            "users",
            Optional.of(new Expr.Binary(new Expr.ColumnRef("balance"), Expr.Operator.GREATER_THAN, new Expr.Literal.IntegerLiteral(100))),
            List.of(new Statement.OrderByItem("balance", Statement.SortDirection.DESC)),
            Optional.of(5L)
        ), stmt);
    }

    // ---- errors ----

    public void testMissingSemicolonThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("SELECT * FROM users"));
    }

    public void testTrailingContentAfterSemicolonThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("SELECT * FROM users; SELECT * FROM orders;"),
            "the parser only supports one statement per call");
    }

    // ---- BEGIN / COMMIT / ROLLBACK ----

    public void testBeginCommitRollbackParseAsTrivialStatements() {
        assertEquals(new Statement.Begin(), Parser.parse("BEGIN;"));
        assertEquals(new Statement.Commit(), Parser.parse("COMMIT;"));
        assertEquals(new Statement.Rollback(), Parser.parse("ROLLBACK;"));
    }

    public void testCreateIndexParses() {
        Statement stmt = Parser.parse("CREATE INDEX idx_users_id ON users (id);");
        assertEquals(new Statement.CreateIndex("idx_users_id", "users", "id"), stmt);
    }

    public void testCreateIndexWithoutOnThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("CREATE INDEX idx_users_id users (id);"));
    }

    public void testCreateWithUnknownFollowingKeywordThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("CREATE VIEW v AS SELECT 1;"));
    }

    public void testEmptyInputThrows() {
        assertThrows(ParseException.class, () -> Parser.parse(""));
    }

    public void testUnknownStatementKeywordThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("DROP TABLE users;"),
            "DROP isn't one of the five supported statement kinds");
    }

    public void testParseExceptionCarriesPosition() {
        try {
            Parser.parse("SELECT * FROM;");
            throw new AssertionError("expected a ParseException");
        } catch (ParseException e) {
            assertEquals(1, e.getLine());
            assertEquals(14, e.getColumn(), "should point at the ';' where a table name was expected");
        }
    }
}
