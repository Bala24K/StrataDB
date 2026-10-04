package com.strata.lexer;

import static com.strata.testutil.Assert.*;

public class TokenTest {

    public void testEqualTokensIgnorePosition() {
        Token a = new Token(TokenType.IDENTIFIER, "Student", null, 1, 1);
        Token b = new Token(TokenType.IDENTIFIER, "Student", null, 99, 42);

        assertEquals(a, b, "tokens with the same type/text/literal must be equal regardless of position");
        assertEquals(a.hashCode(), b.hashCode(), "equal tokens must have equal hash codes");
    }

    public void testDifferentTextMakesTokensUnequal() {
        Token a = new Token(TokenType.IDENTIFIER, "Student", null, 1, 1);
        Token b = new Token(TokenType.IDENTIFIER, "Teacher", null, 1, 1);

        assertFalse(a.equals(b), "tokens with different text must not be equal");
    }

    public void testDifferentTypeMakesTokensUnequal() {
        Token a = new Token(TokenType.IDENTIFIER, "id", null, 1, 1);
        Token b = new Token(TokenType.INTEGER, "id", null, 1, 1);

        assertFalse(a.equals(b), "tokens with different types must not be equal even with the same text");
    }

    public void testDifferentLiteralMakesTokensUnequal() {
        Token a = new Token(TokenType.INTEGER_LITERAL, "5", 5L, 1, 1);
        Token b = new Token(TokenType.INTEGER_LITERAL, "5", 6L, 1, 1);

        assertFalse(a.equals(b), "tokens with different literal values must not be equal");
    }
}
