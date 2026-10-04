package com.strata.lexer;

import static com.strata.testutil.Assert.*;

import java.util.List;

public class LexerTest {

    public void testEmptyInputProducesOnlyEof() {
        List<Token> tokens = Lexer.tokenize("");
        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tokens.get(0).getType());
    }

    /** The exact example from the project plan: SELECT * FROM Student; */
    public void testSelectStarFromMatchesThePlanExample() {
        List<Token> tokens = Lexer.tokenize("SELECT * FROM Student;");

        assertEquals(6, tokens.size(), "5 real tokens + EOF");
        assertEquals(TokenType.SELECT, tokens.get(0).getType());
        assertEquals(TokenType.STAR, tokens.get(1).getType());
        assertEquals(TokenType.FROM, tokens.get(2).getType());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).getType());
        assertEquals("Student", tokens.get(3).getText());
        assertEquals(TokenType.SEMICOLON, tokens.get(4).getType());
        assertEquals(TokenType.EOF, tokens.get(5).getType());
    }

    public void testKeywordsAreCaseInsensitive() {
        List<Token> upper = Lexer.tokenize("SELECT * FROM Student;");
        List<Token> mixed = Lexer.tokenize("Select * From Student;");
        List<Token> lower = Lexer.tokenize("select * from Student;");

        List<TokenType> upperTypes = upper.stream().map(Token::getType).toList();
        assertEquals(upperTypes, mixed.stream().map(Token::getType).toList(),
            "keyword casing must not affect the resulting token types");
        assertEquals(upperTypes, lower.stream().map(Token::getType).toList());
    }

    public void testIdentifierPreservesOriginalCase() {
        List<Token> tokens = Lexer.tokenize("SELECT Name FROM Users;");
        assertEquals("Name", tokens.get(1).getText(), "identifier text must keep the case the user typed");
    }

    public void testIntegerLiteralParsesValue() {
        List<Token> tokens = Lexer.tokenize("42");
        Token token = tokens.get(0);
        assertEquals(TokenType.INTEGER_LITERAL, token.getType());
        assertEquals("42", token.getText());
        assertEquals(42L, token.getLiteral());
    }

    public void testStringLiteralUnwrapsQuotes() {
        List<Token> tokens = Lexer.tokenize("'hello'");
        Token token = tokens.get(0);
        assertEquals(TokenType.STRING_LITERAL, token.getType());
        assertEquals("hello", token.getLiteral());
    }

    public void testStringLiteralWithEscapedQuote() {
        List<Token> tokens = Lexer.tokenize("'it''s'");
        Token token = tokens.get(0);
        assertEquals(TokenType.STRING_LITERAL, token.getType());
        assertEquals("it's", token.getLiteral(), "a doubled single quote must decode to one literal quote");
    }

    public void testUnterminatedStringThrows() {
        assertThrows(LexException.class, () -> Lexer.tokenize("'abc"),
            "a string literal missing its closing quote must be rejected, not silently truncated");
    }

    public void testComparisonOperators() {
        List<Token> tokens = Lexer.tokenize("= <> < > <= >= !=");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.LESS_THAN, TokenType.GREATER_THAN,
            TokenType.LESS_EQUALS, TokenType.GREATER_EQUALS, TokenType.NOT_EQUALS, TokenType.EOF
        ), types, "<> and != must both be recognized as NOT_EQUALS");
    }

    public void testLineCommentIsSkipped() {
        List<Token> tokens = Lexer.tokenize("SELECT 1 -- this whole rest of the line is a comment\nFROM t;");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.SELECT, TokenType.INTEGER_LITERAL, TokenType.FROM, TokenType.IDENTIFIER,
            TokenType.SEMICOLON, TokenType.EOF
        ), types, "everything after -- up to the newline must be ignored");
    }

    public void testMinusAndNumberAreTwoSeparateTokens() {
        List<Token> tokens = Lexer.tokenize("-5");
        assertEquals(3, tokens.size(), "MINUS, INTEGER_LITERAL, EOF");
        assertEquals(TokenType.MINUS, tokens.get(0).getType());
        assertEquals(TokenType.INTEGER_LITERAL, tokens.get(1).getType());
        assertEquals(5L, tokens.get(1).getLiteral());
    }

    public void testUnknownCharacterThrowsWithPosition() {
        try {
            Lexer.tokenize("SELECT @ FROM t;");
            throw new AssertionError("expected a LexException but tokenizing succeeded");
        } catch (LexException e) {
            assertEquals(1, e.getLine());
            assertEquals(8, e.getColumn(), "the '@' is the 8th character on the line");
        }
    }

    public void testDataTypeKeywordsAreRecognizedNotAsIdentifiers() {
        List<Token> tokens = Lexer.tokenize("CREATE TABLE t (id INTEGER, name VARCHAR, big BIGINT, flag BOOLEAN);");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertTrue(types.contains(TokenType.INTEGER));
        assertTrue(types.contains(TokenType.VARCHAR));
        assertTrue(types.contains(TokenType.BIGINT));
        assertTrue(types.contains(TokenType.BOOLEAN));
    }

    public void testTransactionKeywordsAreRecognized() {
        List<Token> tokens = Lexer.tokenize("BEGIN; COMMIT; ROLLBACK;");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.BEGIN, TokenType.SEMICOLON, TokenType.COMMIT, TokenType.SEMICOLON,
            TokenType.ROLLBACK, TokenType.SEMICOLON, TokenType.EOF
        ), types);
    }

    public void testCreateIndexKeywordsAreRecognized() {
        List<Token> tokens = Lexer.tokenize("CREATE INDEX idx ON t (id);");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.CREATE, TokenType.INDEX, TokenType.IDENTIFIER, TokenType.ON,
            TokenType.IDENTIFIER, TokenType.LPAREN, TokenType.IDENTIFIER, TokenType.RPAREN,
            TokenType.SEMICOLON, TokenType.EOF
        ), types);
    }

    public void testFullInsertStatementTokenizes() {
        List<Token> tokens = Lexer.tokenize("INSERT INTO users VALUES (1, 'Alice', TRUE);");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.INSERT, TokenType.INTO, TokenType.IDENTIFIER, TokenType.VALUES, TokenType.LPAREN,
            TokenType.INTEGER_LITERAL, TokenType.COMMA, TokenType.STRING_LITERAL, TokenType.COMMA,
            TokenType.TRUE, TokenType.RPAREN, TokenType.SEMICOLON, TokenType.EOF
        ), types);
    }

    public void testWhitespaceVariantsAreAllSkipped() {
        List<Token> tokens = Lexer.tokenize("SELECT\t*\n  FROM   t;");
        List<TokenType> types = tokens.stream().map(Token::getType).toList();

        assertEquals(List.of(
            TokenType.SELECT, TokenType.STAR, TokenType.FROM, TokenType.IDENTIFIER, TokenType.SEMICOLON, TokenType.EOF
        ), types);
    }

    public void testLineAndColumnTrackingAcrossMultipleLines() {
        List<Token> tokens = Lexer.tokenize("SELECT *\nFROM t;");

        Token select = tokens.get(0);
        Token star = tokens.get(1);
        Token from = tokens.get(2);
        Token t = tokens.get(3);
        Token semicolon = tokens.get(4);

        assertEquals(1, select.getLine());
        assertEquals(1, select.getColumn());

        assertEquals(1, star.getLine());
        assertEquals(8, star.getColumn(), "'*' is the 8th character of line 1");

        assertEquals(2, from.getLine());
        assertEquals(1, from.getColumn(), "FROM starts a fresh line, so its column resets to 1");

        assertEquals(2, t.getLine());
        assertEquals(6, t.getColumn(), "'t' is the 6th character of line 2");

        assertEquals(2, semicolon.getLine());
        assertEquals(7, semicolon.getColumn());
    }
}
