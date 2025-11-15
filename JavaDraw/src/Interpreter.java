import java.util.*;
import java.util.function.Consumer;

public final class Interpreter {

    // ---------- External dependencies ----------
    private final TokenRegistry reg;
    private final Consumer<String> log;

    // ---------- Runtime environment ----------
    public enum Type { INT, STRING, DOUBLE}
    public static final class Value {
        public final Type t;
        public final int i;
        public final String s;
        public final double d;
        private Value(Type t, int i, String s, double d){ this.t=t; this.i=i; this.s=s; this.d=d;}
        public static Value ofInt(int v){ return new Value(Type.INT, v, null, 0.0); }
        public static Value ofString(String v){ return new Value(Type.STRING, 0, v, 0.0); }
        public static Value ofDouble(double d){ return new Value(Type.DOUBLE, 0, null, d); }
        @Override public String toString(){
            switch (t) {
                case INT:    return Integer.toString(i);
                case DOUBLE: return Double.toString(d);
                case STRING: return "\"" + s + "\"";
                default:     return "<undef>";
            }
        }
        private static String asString(Value v) {
            switch (v.t) {
                case STRING: return v.s;
                case INT:    return Integer.toString(v.i);
                case DOUBLE: return Double.toString(v.d);
                default:     return "<undef>";
            }
        }
    }

    private final Map<String,Value> symbols = new HashMap<>();

    // ---------- States (CONO rows) ----------
    private enum State { S0, DECL_TYPE, DECL_NAME, ASSIGN_LHS, AFTER_EQ }

    // ---------- Columns (CONO cols) ----------
    private enum Col { KW_INT, KW_STRING, KW_DOUBLE, SYMBOL, LITERAL, EQ, PLUS, LPAREN, RPAREN, SEMI, MINUS, MULT, DIV, EXP, OTHER }

    // codes we care about (from registry)
    private final int codeKW_Int, codeKW_String, codeKW_Double;
    private final int opEq, opPlus, opLParen, opRParen, opSemi, opMinus, opMult, opDiv, opExp;

    // pending declaration / assignment
    private String pendingName = null;
    private Type pendingType = null;
    private boolean lastWasDecl = false; // distinguishes decl vs assign at commit
    private boolean check = false; // false = int | true = double
    private Type expectType = null;

    // expression stacks
    private final Deque<Value> vals = new ArrayDeque<>();
    private final Deque<Integer> ops = new ArrayDeque<>();
    // simple precedence (easy to extend)
    private final Map<Integer,Integer> prec = new HashMap<>();

    public Interpreter(TokenRegistry reg, Consumer<String> logger) {
        this.reg = reg;
        this.log = (logger == null) ? (_s)->{} : logger;

        // pull codes from registry
        codeKW_Int    = opt(reg.keywords().get("int"));
        codeKW_String = opt(reg.keywords().get("string"));
        codeKW_Double = opt(reg.keywords().get("double"));

        opEq    = opt(reg.operators().get("="));
        opPlus  = opt(reg.operators().get("+"));
        opLParen= opt(reg.operators().get("("));
        opRParen= opt(reg.operators().get(")"));
        opSemi  = opt(reg.operators().get(";"));
        opMinus = opt(reg.operators().get("-"));
        opMult = opt(reg.operators().get("*"));
        opDiv = opt(reg.operators().get("/"));
        opExp = opt(reg.operators().get("^"));

        prec.put(opPlus, 10);
        prec.put(opMinus, 10);
        prec.put(opMult, 10);
        prec.put(opDiv, 10);
        prec.put(opExp, 10);
    }

    private static int opt(Integer x){ return x==null ? Integer.MIN_VALUE : x; }

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    /** Execute one statement (tokens from a single line, with // already stripped). */
    public boolean execTokens(String[] toks){
        // encode once
        List<TokenRegistry.EncodedToken> ts = new ArrayList<>();
        for (String t : toks) {
            if (t.isEmpty()) continue;
            ts.add(reg.encode(t));
        }
        if (ts.isEmpty()) return false;

        State st = State.S0;
        pendingName = null;
        pendingType = null;
        lastWasDecl = false;
        vals.clear(); ops.clear();

        for (int i=0;i<ts.size();i++){
            TokenRegistry.EncodedToken et = ts.get(i);
            Col col = classify(et);
            switch (st) {
                case S0:
                    if (col==Col.KW_INT)   { startDecl(Type.INT); check = false; st = State.DECL_TYPE; break; }
                    if (col==Col.KW_STRING){ startDecl(Type.STRING); st = State.DECL_TYPE; break; }
                    if (col==Col.KW_DOUBLE){ startDecl(Type.DOUBLE); check = true; st = State.DECL_TYPE; break; }
                    if (col==Col.SYMBOL)   { captureName(et.lexeme); lastWasDecl=false; st = State.ASSIGN_LHS; break; }
                    if (col==Col.SEMI)     { /* empty stmt */ break; }
                    return error("Expected declaration or assignment", et);
                case DECL_TYPE:
                    if (col==Col.SYMBOL)   { captureName(et.lexeme); st = State.DECL_NAME; break; }
                    return error("Expected name after type", et);
                case DECL_NAME:
                    if (col==Col.SEMI) { commitDecl(); st = State.S0; break; }
                    if (col==Col.EQ)   {
                        beginExpr();
                        lastWasDecl = true;
                        expectType = pendingType;
                        st = State.AFTER_EQ;
                        break;
                    }
                    return error("Expected '=' or ';' after name", et);
                case ASSIGN_LHS:
                    if (col==Col.EQ) {
                        beginExpr();
                        lastWasDecl = false;
                        Value old = symbols.get(pendingName);
                        if (old == null) throw new IllegalStateException("Undeclared identifier: " + pendingName);
                        expectType = old.t;
                        st = State.AFTER_EQ;
                        break;
                    }
                    return error("Expected '=' after identifier", et);
                case AFTER_EQ:
                    if (col==Col.LITERAL)  { pushLit(et.lexeme); break; }
                    if (col==Col.SYMBOL)   { pushSym(et.lexeme); break; }
                    if (col==Col.PLUS)     { pushOp(opPlus); break; }
                    if (col==Col.LPAREN)   { ops.push(opLParen); break; }
                    if (col==Col.RPAREN)   { reduceUntilLParen(); break; }
                    if (col==Col.SEMI)     { Value v = reduceAll(); commitValue(v); st = State.S0; break; }
                    if (col==Col.MINUS)    { pushOp(opMinus); break; }
                    if (col==Col.MULT)     { pushOp(opMult); break; }
                    if (col==Col.DIV)      { pushOp(opDiv); break; }
                    if (col==Col.EXP)      { pushOp(opExp); break; }
                    return error("Unexpected token in expression", et);
            }
        }
        // no trailing ';' soft error/recovery
        if (!ops.isEmpty() || !vals.isEmpty()) {
            try { Value v = reduceAll(); commitValue(v); }
            catch (Exception e){ log.accept("!   Incomplete statement (missing ';')\n"); }
        }
        return true;
    }

    public Map<String,Value> symbolsView(){
        return Collections.unmodifiableMap(symbols);
    }

    // ------------------------------------------------------------
    // Actions (code generators)
    // ------------------------------------------------------------
    private void startDecl(Type t){ pendingType = t; pendingName = null; lastWasDecl=true; }

    private void captureName(String name){
        if (pendingName != null) throw new IllegalStateException("Name already captured");
        pendingName = name;
    }

    private void beginExpr(){ vals.clear(); ops.clear(); }

    private void pushLit(String lex) {
        if (isQuoted(lex)) { // "yellow"
            vals.push(Value.ofString(unquote(lex)));
            return;
        }
        // choose numeric kind from LHS type
        if (expectType == Type.DOUBLE) {
            vals.push(Value.ofDouble(parseDoubleSafe(lex)));
        } else if (expectType == Type.INT) {
            vals.push(Value.ofInt(parseIntSafe(lex)));
        } else {
            // Fallback if no expectation: try double, then int
            if (lex.contains(".") || lex.contains("e") || lex.contains("E")) {
                vals.push(Value.ofDouble(parseDoubleSafe(lex)));
            } else {
                vals.push(Value.ofInt(parseIntSafe(lex)));
            }
        }
    }


    private void pushSym(String name){
        Value v = symbols.get(name);
        if (v==null) throw new IllegalStateException("Undeclared identifier: " + name);
        vals.push(v);
    }

    private void pushOp(int op){
        while (!ops.isEmpty() && ops.peek()!=opLParen && prec(ops.peek()) >= prec(op)){
            applyTop();
        }
        ops.push(op);
    }

    private void reduceUntilLParen(){
        while (!ops.isEmpty() && ops.peek()!=opLParen) applyTop();
        if (ops.isEmpty()) throw new IllegalStateException("Mismatched ')'");
        ops.pop(); // pop '('
    }

    private Value reduceAll(){
        while (!ops.isEmpty()){
            if (ops.peek()==opLParen) throw new IllegalStateException("Mismatched '('");
            applyTop();
        }
        if (vals.isEmpty()) throw new IllegalStateException("Empty expression");
        return vals.pop();
    }

    private void commitDecl(){
        if (pendingType==null || pendingName==null) throw new IllegalStateException("Bad declaration");
        if (symbols.containsKey(pendingName)) throw new IllegalStateException("Redeclare: " + pendingName);
        Value v;

        switch (pendingType) {
            case INT: v = Value.ofInt(0); break;
            case DOUBLE: v = Value.ofDouble(0); break;
            case STRING: v = Value.ofString(""); break;
            default:
                throw new IllegalStateException("Unrecognized type: " + pendingType);
        }

        symbols.put(pendingName, v);
        log.accept("    decl " + pendingName + ":" + pendingType + " = " + v + "\n");
        pendingName=null; pendingType=null; lastWasDecl=false;
    }

    private void commitValue(Value v){
        if (lastWasDecl){
            // int x = <v>;  string s = <v>;
            if (pendingType==Type.INT && v.t!=Type.INT)   throw new IllegalStateException("Type mismatch: need INT");
            if (pendingType==Type.STRING && v.t!=Type.STRING) throw new IllegalStateException("Type mismatch: need STRING");
            if (pendingType == Type.DOUBLE) {
                if (v.t == Type.INT) {
                    v = Value.ofDouble(v.i);
                } else if (v.t == Type.DOUBLE) {
                } else {
                    throw new IllegalStateException("Type mismatch: need DOUBLE/INT");
                }
            }
            if (symbols.containsKey(pendingName)) throw new IllegalStateException("Redeclare: " + pendingName);
            symbols.put(pendingName, v);
            log.accept("    decl-init " + pendingName + ":" + pendingType + " = " + v + "\n");
            pendingName=null; pendingType=null; lastWasDecl=false; expectType=null;
        } else {
            // x = <v>;
            if (pendingName==null) throw new IllegalStateException("Missing LHS for assignment");
            Value old = symbols.get(pendingName);
            if (old==null) throw new IllegalStateException("Undeclared identifier: " + pendingName);
            if (old.t==Type.DOUBLE && v.t==Type.INT) v = Value.ofDouble(v.i);
            if (old.t != v.t) throw new IllegalStateException("Type mismatch in assignment to " + pendingName);
            symbols.put(pendingName, v);
            log.accept("    assign " + pendingName + " = " + v + "\n");
            pendingName=null; expectType=null;
        }
    }

    private void applyTop(){
        int op = ops.pop();
        Value b = vals.pop();
        Value a = vals.pop();
        if (op==opPlus || op==opMinus || op==opMult || op==opDiv || op==opExp) {
            if (!isNumber(a) || !isNumber(b)) {
                if (op==opPlus && (a.t==Type.STRING || b.t==Type.STRING)) {
                    String sa = (a.t==Type.STRING)?a.s:String.valueOf(a.i);
                    String sb = (b.t==Type.STRING)?b.s:String.valueOf(b.i);
                    vals.push(Value.ofString(sa + sb));
                } else {
                    throw new IllegalStateException("Non-numeric operands");
                }
                return;
            }
            if (a.t == Type.DOUBLE) {
                double ad = asDouble(a), bd = asDouble(b), rd;
                if (op==opPlus) rd = ad + bd;
                else if (op==opMinus) rd = ad - bd;
                else if (op==opMult) rd = ad * bd;
                else if (op==opExp) rd = Math.pow(ad, bd);
                else rd = ad / bd;
                vals.push(Value.ofDouble(rd));
            } else {
                int ad = asInt(a), bd = asInt(b), rd;
                if (op==opPlus) rd = ad + bd;
                else if (op==opMinus) rd = ad - bd;
                else if (op==opMult) rd = ad * bd;
                else if (op==opExp) rd = (int) Math.pow(ad, bd);
                else rd = ad / bd;
                vals.push(Value.ofInt(rd));
            }

            return;
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    private int prec(int op){ return prec.getOrDefault(op, 0); }

    private boolean isQuoted(String s){ return s.length()>=2 && s.startsWith("\"") && s.endsWith("\""); }
    private String unquote(String s){ return s.substring(1, s.length()-1); }

    private static boolean isNumber(Value v) { return v.t==Type.INT || v.t==Type.DOUBLE; }
    private static double asDouble(Value v) { return v.t==Type.DOUBLE ? v.d : v.i; }
    private static int asInt(Value v) { return v.t==Type.INT ? v.i : v.i; }

    private int parseIntSafe(String s){
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e){ throw new IllegalStateException("Not an int: " + s); }
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new IllegalStateException("Not a double: " + s); }
    }

    private boolean error(String msg, TokenRegistry.EncodedToken et){
        log.accept("!   " + msg + " near '" + et.lexeme + "'\n");
        // simple recovery: clear stacks so we don't cascade
        vals.clear(); ops.clear();
        pendingName=null; pendingType=null; lastWasDecl=false;
        return false;
    }

    private Col classify(TokenRegistry.EncodedToken et){
        String lex = et.lexeme;
        if (lex.length() >= 2 && lex.startsWith("\"") && lex.endsWith("\"")) {
            return Col.LITERAL;
        }

        int c = et.code;
        if (c==codeKW_Int)    return Col.KW_INT;
        if (c==codeKW_String) return Col.KW_STRING;
        if (c==codeKW_Double) return Col.KW_DOUBLE;
        if (c==opEq)          return Col.EQ;
        if (c==opPlus)        return Col.PLUS;
        if (c==opLParen)      return Col.LPAREN;
        if (c==opRParen)      return Col.RPAREN;
        if (c==opSemi)        return Col.SEMI;
        if (c==opMinus)       return Col.MINUS;
        if (c==opMult)        return Col.MULT;
        if (c==opDiv)         return Col.DIV;
        if (c==opExp)         return Col.EXP;

        switch (et.category){
            case SYMBOL:  return Col.SYMBOL;
            case LITERAL: return Col.LITERAL;
            default:      return Col.OTHER;
        }
    }

    private void nonInt(Value a, Value b) {
        String sa = (a.t==Type.STRING) ? a.s : Integer.toString(a.i);
        String sb = (b.t==Type.STRING) ? b.s : Integer.toString(b.i);
        vals.push(Value.ofString(sa + sb));
    }

    // nice for the log:
    public String dumpSymbols() {
        StringBuilder sb = new StringBuilder("Symbols: ");
        boolean first=true;
        for (var e : symbols.entrySet()){
            if (!first) sb.append(", "); first=false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }
}
