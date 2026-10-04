package com.strata.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns raw SQL text into a flat list of {@link Token}s — the first step of every SQL engine,
 * whether it's a database or a programming language compiler. Given
 *
 * <pre>SELECT * FROM Student;</pre>
 *
 * this produces exactly the token sequence {@code SELECT, STAR, FROM, IDENTIFIER("Student"),
 * SEMICOLON, EOF}.
 *
 * <h2>How it works</h2>
 * This is a classic hand-written scanner: two indices into the source string, {@code start}
 * (where the token currently being scanned began) and {@code current} (the next character to
 * consume). Each iteration of the main loop skips whitespace and comments, records where the
 * next real token begins, and dispatches on the first character to decide what kind of token
 * follows — a symbol, a number, a quoted string, or an identifier/keyword.
 *
 * <h2>What counts as a "word"</h2>
 * Keywords ({@code SELECT}, {@code FROM}, ...) and identifiers (table and column names) are
 * scanned by exactly the same rule — a letter or underscore, followed by any number of
 * letters, digits, or underscores — and only classified afterward, by looking the scanned text
 * up in a keyword table. This is why {@code SELECT}, {@code select}, and {@code SeLeCt} all
 * produce the same {@code TokenType.SELECT} token (SQL keywords are conventionally
 * case-insensitive) while an identifier's {@link Token#getText()} keeps whatever case the user
 * actually typed — StrataDB doesn't fold identifier case the way some real databases do; it's
 * simplest to preserve exactly what was written.
 *
 * <h2>What's deliberately not supported yet</h2>
 * <ul>
 *   <li>Only {@code --} line comments, not {@code /* ... *&#47;} block comments.</li>
 *   <li>No arithmetic beyond unary minus (a bare {@code MINUS} token) — {@code -5} lexes as
 *       two tokens, {@code MINUS} then {@code INTEGER_LITERAL(5)}; deciding whether that's a
 *       negative literal or a subtraction is the parser's job (Phase 4), not the lexer's.</li>
 *   <li>No floating-point literals — there's no {@code DOUBLE}/{@code FLOAT} type in the
 *       catalog yet (see {@link com.strata.catalog.DataType}), so there's nothing for one to
 *       mean.</li>
 * </ul>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("SELECT", TokenType.SELECT),
        Map.entry("FROM", TokenType.FROM),
        Map.entry("WHERE", TokenType.WHERE),
        Map.entry("INSERT", TokenType.INSERT),
        Map.entry("INTO", TokenType.INTO),
        Map.entry("VALUES", TokenType.VALUES),
        Map.entry("UPDATE", TokenType.UPDATE),
        Map.entry("SET", TokenType.SET),
        Map.entry("DELETE", TokenType.DELETE),
        Map.entry("CREATE", TokenType.CREATE),
        Map.entry("TABLE", TokenType.TABLE),
        Map.entry("INDEX", TokenType.INDEX),
        Map.entry("ON", TokenType.ON),
        Map.entry("BEGIN", TokenType.BEGIN),
        Map.entry("COMMIT", TokenType.COMMIT),
        Map.entry("ROLLBACK", TokenType.ROLLBACK),
        Map.entry("AND", TokenType.AND),
        Map.entry("OR", TokenType.OR),
        Map.entry("NOT", TokenType.NOT),
        Map.entry("ORDER", TokenType.ORDER),
        Map.entry("BY", TokenType.BY),
        Map.entry("LIMIT", TokenType.LIMIT),
        Map.entry("ASC", TokenType.ASC),
        Map.entry("DESC", TokenType.DESC),
        Map.entry("NULL", TokenType.NULL),
        Map.entry("TRUE", TokenType.TRUE),
        Map.entry("FALSE", TokenType.FALSE),
        Map.entry("INTEGER", TokenType.INTEGER),
        Map.entry("BIGINT", TokenType.BIGINT),
        Map.entry("VARCHAR", TokenType.VARCHAR),
        Map.entry("BOOLEAN", TokenType.BOOLEAN)
    );

    private final String source;
    private int start;
    private int current;
    private int line = 1;
    private int column = 1;

    private Lexer(String source) {
        this.source = source;
    }

    /** Tokenizes {@code source} in one shot. This is the only entry point most callers need. */
    public static List<Token> tokenize(String source) {
        return new Lexer(source).scanAll();
    }

    private List<Token> scanAll() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (isAtEnd()) break;

            start = current;
            int tokenLine = line;
            int tokenColumn = column;
            tokens.add(scanToken(tokenLine, tokenColumn));
        }
        tokens.add(new Token(TokenType.EOF, "", null, line, column));
        return tokens;
    }

    private Token scanToken(int tokenLine, int tokenColumn) {
        char c = advance();
        switch (c) {
            case '*': return finish(TokenType.STAR, tokenLine, tokenColumn);
            case ',': return finish(TokenType.COMMA, tokenLine, tokenColumn);
            case ';': return finish(TokenType.SEMICOLON, tokenLine, tokenColumn);
            case '(': return finish(TokenType.LPAREN, tokenLine, tokenColumn);
            case ')': return finish(TokenType.RPAREN, tokenLine, tokenColumn);
            case '.': return finish(TokenType.DOT, tokenLine, tokenColumn);
            case '-': return finish(TokenType.MINUS, tokenLine, tokenColumn);
            case '=': return finish(TokenType.EQUALS, tokenLine, tokenColumn);
            case '<':
                if (match('=')) return finish(TokenType.LESS_EQUALS, tokenLine, tokenColumn);
                if (match('>')) return finish(TokenType.NOT_EQUALS, tokenLine, tokenColumn);
                return finish(TokenType.LESS_THAN, tokenLine, tokenColumn);
            case '>':
                if (match('=')) return finish(TokenType.GREATER_EQUALS, tokenLine, tokenColumn);
                return finish(TokenType.GREATER_THAN, tokenLine, tokenColumn);
            case '!':
                if (match('=')) return finish(TokenType.NOT_EQUALS, tokenLine, tokenColumn);
                throw new LexException("Unexpected character '!' (did you mean '!='?)", tokenLine, tokenColumn);
            case '\'':
                return scanStringLiteral(tokenLine, tokenColumn);
            default:
                if (isDigit(c)) return scanNumber(tokenLine, tokenColumn);
                if (isIdentifierStart(c)) return scanIdentifierOrKeyword(tokenLine, tokenColumn);
                throw new LexException("Unexpected character '" + c + "'", tokenLine, tokenColumn);
        }
    }

    private Token scanNumber(int tokenLine, int tokenColumn) {
        while (isDigit(peek())) advance();
        String text = source.substring(start, current);
        long value = Long.parseLong(text);
        return new Token(TokenType.INTEGER_LITERAL, text, value, tokenLine, tokenColumn);
    }

    private Token scanIdentifierOrKeyword(int tokenLine, int tokenColumn) {
        while (isIdentifierPart(peek())) advance();
        String text = source.substring(start, current);
        TokenType keywordType = KEYWORDS.get(text.toUpperCase(Locale.ROOT));
        TokenType type = keywordType != null ? keywordType : TokenType.IDENTIFIER;
        return new Token(type, text, null, tokenLine, tokenColumn);
    }

    private Token scanStringLiteral(int tokenLine, int tokenColumn) {
        StringBuilder value = new StringBuilder();
        while (true) {
            if (isAtEnd()) {
                throw new LexException("Unterminated string literal", tokenLine, tokenColumn);
            }
            char c = advance();
            if (c == '\'') {
                if (peek() == '\'') {
                    // A doubled quote inside a string is the standard SQL escape for a literal quote.
                    advance();
                    value.append('\'');
                } else {
                    break; // un-doubled quote ends the string
                }
            } else {
                value.append(c);
            }
        }
        String text = source.substring(start, current);
        return new Token(TokenType.STRING_LITERAL, text, value.toString(), tokenLine, tokenColumn);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '-' && peekNext() == '-') {
                while (!isAtEnd() && peek() != '\n') advance();
            } else {
                break;
            }
        }
    }

    private Token finish(TokenType type, int tokenLine, int tokenColumn) {
        return new Token(type, source.substring(start, current), null, tokenLine, tokenColumn);
    }

    // ---- low-level character access, with line/column bookkeeping ----

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        char c = source.charAt(current);
        current++;
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        advance();
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
