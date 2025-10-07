// TokenRegistry.java
import java.util.*;

public final class TokenRegistry {
    // ----- nested types -----
    public enum TokenCategory { KEYWORD, OPERATOR, SYMBOL, LITERAL, UNKNOWN }

    public static final class EncodingConfig {
        public final int kwStart, kwEnd, opStart, opEnd, symStart, symEnd, litStart, litEnd;
        public EncodingConfig(int kwStart, int kwEnd, int opStart, int opEnd,
                              int symStart, int symEnd, int litStart, int litEnd) {
            this.kwStart=kwStart; this.kwEnd=kwEnd;
            this.opStart=opStart; this.opEnd=opEnd;
            this.symStart=symStart; this.symEnd=symEnd;
            this.litStart=litStart; this.litEnd=litEnd;
            if (!(kwStart<=kwEnd && opStart<=opEnd && symStart<=symEnd && litStart<=litEnd))
                throw new IllegalArgumentException("Bad ranges");
        }
    }

    public static final class EncodedToken {
        public final String lexeme;
        public final TokenCategory category;
        public final int code;
        public EncodedToken(String lexeme, TokenCategory cat, int code) {
            this.lexeme = lexeme; this.category = cat; this.code = code;
        }
        @Override public String toString() { return lexeme + " -> " + category + " #" + code; }
    }

    // ----- registry fields -----
    private final EncodingConfig cfg;

    private final LinkedHashMap<String,Integer> keywordCodes = new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> operatorCodes = new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> symbolCodes  = new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> literalCodes = new LinkedHashMap<>();

    private final HashMap<Integer,String> codeToLexeme = new HashMap<>();
    private final HashMap<Integer,TokenCategory> codeToCategory = new HashMap<>();

    private int nextSym;
    private int nextLit;

    public TokenRegistry(EncodingConfig cfg) {
        this.cfg = cfg;
        this.nextSym = cfg.symStart;
        this.nextLit = cfg.litStart;
    }

    // ----- registration (fixed ranges) -----
    public void registerKeywords(String... kws) {
        int code = cfg.kwStart + keywordCodes.size();
        for (String kw : kws) {
            checkRange(code, cfg.kwEnd, "keyword");
            put(keywordCodes, kw, code, TokenCategory.KEYWORD);
            code++;
        }
    }

    public void registerOperators(String... ops) {
        int code = cfg.opStart + operatorCodes.size();
        for (String op : ops) {
            checkRange(code, cfg.opEnd, "operator");
            put(operatorCodes, op, code, TokenCategory.OPERATOR);
            code++;
        }
    }

    // ----- encoding -----
    public EncodedToken encode(String lexeme) {
        if (lexeme == null) return new EncodedToken("", TokenCategory.UNKNOWN, -1);

        Integer code = keywordCodes.get(lexeme);
        if (code != null) return new EncodedToken(lexeme, TokenCategory.KEYWORD, code);

        code = operatorCodes.get(lexeme);
        if (code != null) return new EncodedToken(lexeme, TokenCategory.OPERATOR, code);

        if (isNumeric(lexeme)) {
            Integer lc = literalCodes.get(lexeme);
            if (lc == null) {
                checkRange(nextLit, cfg.litEnd, "literal");
                lc = nextLit++;
                put(literalCodes, lexeme, lc, TokenCategory.LITERAL);
            }
            return new EncodedToken(lexeme, TokenCategory.LITERAL, lc);
        }

        Integer sc = symbolCodes.get(lexeme);
        if (sc == null) {
            checkRange(nextSym, cfg.symEnd, "symbol");
            sc = nextSym++;
            put(symbolCodes, lexeme, sc, TokenCategory.SYMBOL);
        }
        return new EncodedToken(lexeme, TokenCategory.SYMBOL, sc);
    }

    public String decode(int code) { return codeToLexeme.get(code); }
    public TokenCategory categoryOf(int code) { return codeToCategory.get(code); }

    // tables (read-only views)
    public Map<String,Integer> keywords()  { return Collections.unmodifiableMap(keywordCodes); }
    public Map<String,Integer> operators() { return Collections.unmodifiableMap(operatorCodes); }
    public Map<String,Integer> symbols()   { return Collections.unmodifiableMap(symbolCodes); }
    public Map<String,Integer> literals()  { return Collections.unmodifiableMap(literalCodes); }

    // ----- helpers -----
    private static boolean isNumeric(String s) {
        return s.matches("\\d+(\\.\\d+)?"); // simple; extend as needed
    }

    private void put(Map<String,Integer> map, String lexeme, int code, TokenCategory cat) {
        map.put(lexeme, code);
        codeToLexeme.put(code, lexeme);
        codeToCategory.put(code, cat);
    }

    private static void checkRange(int code, int end, String what) {
        if (code > end) throw new IllegalStateException("Out of " + what + " codes");
    }
}
