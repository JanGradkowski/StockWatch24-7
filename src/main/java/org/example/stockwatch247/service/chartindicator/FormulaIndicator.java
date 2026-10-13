package org.example.stockwatch247.service.chartindicator;

import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Category;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.OutputSpec;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.Placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.example.stockwatch247.service.chartindicator.IndicatorDefinition.define;

/**
 * A user-written formula over bar series, e.g. {@code ema(close, 12) - ema(close, 26)}. The text is
 * parsed into a small expression tree over a fixed whitelist of names and functions and evaluated on
 * arrays; nothing the user types is ever executed as code.
 */
public final class FormulaIndicator {
    public static final int MAX_LENGTH = 240;
    private static final int MAX_NODES = 96;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_LOOKBACK = 1000;
    private static final List<String> SERIES = List.of("open", "high", "low", "close", "volume", "hl2", "hlc3", "ohlc4", "hlcc4");

    private FormulaIndicator() { }

    static void register(Consumer<IndicatorDefinition> catalog) {
        catalog.accept(define("FORMULA", "Formula", Category.STATISTICS, Placement.PANEL)
                .inputs(InputSpec.text("expression", "ema(close, 12) - ema(close, 26)", MAX_LENGTH))
                .outputs(OutputSpec.line("value", Palette.LIME))
                .validate(values -> parse(values.text("expression")))
                .label(values -> values.text("expression"))
                .compute(context -> context.output("value", parse(context.text("expression")).evaluate(context))));
        catalog.accept(define("FORMULA_OVERLAY", "Formula", Category.STATISTICS, Placement.PRICE)
                .inputs(InputSpec.text("expression", "(highest(high, 20) + lowest(low, 20)) / 2", MAX_LENGTH))
                .outputs(OutputSpec.line("value", Palette.LIME))
                .validate(values -> parse(values.text("expression")))
                .label(values -> values.text("expression"))
                .compute(context -> context.output("value", parse(context.text("expression")).evaluate(context))));
    }

    /** Parses and checks a formula; throws UserInputException naming the problem and its position. */
    public static Node parse(String text) {
        if (text == null || text.isBlank()) throw new UserInputException("Enter a formula.");
        if (text.length() > MAX_LENGTH) throw new UserInputException("A formula can be at most " + MAX_LENGTH + " characters.");
        Parser parser = new Parser(text);
        Node node = parser.expression(0);
        if (parser.peek().type() != TokenType.END) throw parser.error("Unexpected " + parser.peek().text());
        if (parser.nodes > MAX_NODES) throw new UserInputException("The formula is too long; use at most " + MAX_NODES + " terms.");
        return node;
    }

    /** A parsed formula term. */
    public sealed interface Node permits Constant, Series, Unary, Binary, Conditional, History, Call {
        double[] evaluate(IndicatorContext context);
    }

    record Constant(double value) implements Node {
        public double[] evaluate(IndicatorContext context) {
            double[] result = new double[context.size()];
            java.util.Arrays.fill(result, value);
            return result;
        }
    }

    record Series(String name) implements Node {
        public double[] evaluate(IndicatorContext context) {
            return switch (name) {
                case "volume" -> context.volume().clone();
                default -> context.price(name).clone();
            };
        }
    }

    record Unary(String operator, Node operand) implements Node {
        public double[] evaluate(IndicatorContext context) {
            double[] value = operand.evaluate(context);
            double[] result = new double[value.length];
            for (int index = 0; index < value.length; index++) {
                result[index] = switch (operator) {
                    case "-" -> -value[index];
                    case "not" -> Double.isNaN(value[index]) ? Double.NaN : (value[index] == 0 ? 1 : 0);
                    default -> value[index];
                };
            }
            return result;
        }
    }

    record Binary(String operator, Node left, Node right) implements Node {
        public double[] evaluate(IndicatorContext context) {
            double[] a = left.evaluate(context);
            double[] b = right.evaluate(context);
            double[] result = new double[a.length];
            for (int index = 0; index < a.length; index++) {
                double x = a[index];
                double y = b[index];
                result[index] = switch (operator) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "/" -> y == 0 ? Double.NaN : x / y;
                    case "%" -> y == 0 ? Double.NaN : x % y;
                    case "^" -> Math.pow(x, y);
                    case ">" -> truth(x, y, x > y);
                    case "<" -> truth(x, y, x < y);
                    case ">=" -> truth(x, y, x >= y);
                    case "<=" -> truth(x, y, x <= y);
                    case "==" -> truth(x, y, x == y);
                    case "!=" -> truth(x, y, x != y);
                    case "and" -> Double.isNaN(x) || Double.isNaN(y) ? Double.NaN : (x != 0 && y != 0 ? 1 : 0);
                    case "or" -> Double.isNaN(x) || Double.isNaN(y) ? Double.NaN : (x != 0 || y != 0 ? 1 : 0);
                    default -> Double.NaN;
                };
            }
            return result;
        }

        private static double truth(double x, double y, boolean value) {
            return Double.isNaN(x) || Double.isNaN(y) ? Double.NaN : (value ? 1 : 0);
        }
    }

    record Conditional(Node condition, Node whenTrue, Node whenFalse) implements Node {
        public double[] evaluate(IndicatorContext context) {
            double[] test = condition.evaluate(context);
            double[] a = whenTrue.evaluate(context);
            double[] b = whenFalse.evaluate(context);
            double[] result = new double[test.length];
            for (int index = 0; index < test.length; index++) {
                result[index] = Double.isNaN(test[index]) ? Double.NaN : (test[index] != 0 ? a[index] : b[index]);
            }
            return result;
        }
    }

    record History(Node series, int bars) implements Node {
        public double[] evaluate(IndicatorContext context) { return TaMath.previous(series.evaluate(context), bars); }
    }

    record Call(String function, List<Node> arguments, int length) implements Node {
        public double[] evaluate(IndicatorContext context) {
            List<double[]> values = new ArrayList<>();
            for (Node argument : arguments) values.add(argument.evaluate(context));
            double[] x = values.isEmpty() ? null : values.getFirst();
            return switch (function) {
                case "sma", "ema", "rma", "wma", "hma", "dema", "tema", "alma", "lsma", "zlema", "kama" ->
                        context.ma(function.equals("rma") ? "SMMA" : function.toUpperCase(Locale.ROOT), x, length);
                case "linreg" -> TaMath.linreg(x, length, 0);
                case "vwma" -> TaMath.vwma(x, context.volume(), length);
                case "rsi" -> TaMath.rsi(x, length);
                case "stdev" -> TaMath.stdev(x, length);
                case "highest" -> TaMath.highest(x, length);
                case "lowest" -> TaMath.lowest(x, length);
                case "sum" -> TaMath.sum(x, length);
                case "change" -> TaMath.change(x, length);
                case "roc" -> TaMath.roc(x, length);
                case "cci" -> TaMath.cci(x, length);
                case "cmo" -> TaMath.cmo(x, length);
                case "median" -> TaMath.median(x, length);
                case "correlation" -> TaMath.correlation(x, values.get(1), length);
                case "atr" -> TaMath.atr(context.high(), context.low(), context.close(), length);
                case "tr" -> TaMath.trueRange(context.high(), context.low(), context.close());
                case "stoch" -> TaMath.stoch(context.close(), context.high(), context.low(), length);
                case "cum" -> TaMath.cum(x);
                case "abs" -> map(x, Math::abs);
                case "sqrt" -> map(x, value -> value < 0 ? Double.NaN : Math.sqrt(value));
                case "log" -> map(x, value -> value <= 0 ? Double.NaN : Math.log(value));
                case "log10" -> map(x, value -> value <= 0 ? Double.NaN : Math.log10(value));
                case "exp" -> map(x, Math::exp);
                case "sign" -> map(x, Math::signum);
                case "round" -> map(x, value -> (double) Math.round(value));
                case "floor" -> map(x, Math::floor);
                case "ceil" -> map(x, Math::ceil);
                case "min" -> combine(values, Math::min);
                case "max" -> combine(values, Math::max);
                case "avg" -> {
                    double[] total = combine(values, Double::sum);
                    yield TaMath.scale(total, 1.0 / values.size());
                }
                case "pow" -> combine(values, Math::pow);
                case "nz" -> {
                    double[] fallback = values.size() > 1 ? values.get(1) : new double[x.length];
                    double[] result = new double[x.length];
                    for (int index = 0; index < x.length; index++) result[index] = Double.isFinite(x[index]) ? x[index] : fallback[index];
                    yield result;
                }
                case "crossover", "crossunder" -> {
                    double[] y = values.get(1);
                    double[] result = TaMath.nan(x.length);
                    for (int index = 1; index < x.length; index++) {
                        double before = x[index - 1] - y[index - 1];
                        double now = x[index] - y[index];
                        if (!Double.isFinite(before) || !Double.isFinite(now)) continue;
                        result[index] = function.equals("crossover") ? (before <= 0 && now > 0 ? 1 : 0) : (before >= 0 && now < 0 ? 1 : 0);
                    }
                    yield result;
                }
                default -> throw new IllegalArgumentException("Unknown function " + function);
            };
        }

        private static double[] map(double[] values, java.util.function.DoubleUnaryOperator operator) {
            double[] result = new double[values.length];
            for (int index = 0; index < values.length; index++) result[index] = operator.applyAsDouble(values[index]);
            return result;
        }

        private static double[] combine(List<double[]> values, java.util.function.DoubleBinaryOperator operator) {
            double[] result = values.getFirst().clone();
            for (int argument = 1; argument < values.size(); argument++) {
                double[] next = values.get(argument);
                for (int index = 0; index < result.length; index++) result[index] = operator.applyAsDouble(result[index], next[index]);
            }
            return result;
        }
    }

    /** Arity of each function: series arguments, then whether a constant length follows. */
    private record Signature(int series, boolean length, boolean variadic) { }

    private static final Map<String, Signature> FUNCTIONS = Map.ofEntries(
            Map.entry("sma", new Signature(1, true, false)), Map.entry("ema", new Signature(1, true, false)),
            Map.entry("rma", new Signature(1, true, false)), Map.entry("wma", new Signature(1, true, false)),
            Map.entry("hma", new Signature(1, true, false)), Map.entry("dema", new Signature(1, true, false)),
            Map.entry("tema", new Signature(1, true, false)), Map.entry("alma", new Signature(1, true, false)),
            Map.entry("lsma", new Signature(1, true, false)), Map.entry("zlema", new Signature(1, true, false)),
            Map.entry("kama", new Signature(1, true, false)), Map.entry("linreg", new Signature(1, true, false)),
            Map.entry("vwma", new Signature(1, true, false)), Map.entry("rsi", new Signature(1, true, false)),
            Map.entry("stdev", new Signature(1, true, false)), Map.entry("highest", new Signature(1, true, false)),
            Map.entry("lowest", new Signature(1, true, false)), Map.entry("sum", new Signature(1, true, false)),
            Map.entry("change", new Signature(1, true, false)), Map.entry("roc", new Signature(1, true, false)),
            Map.entry("cci", new Signature(1, true, false)), Map.entry("cmo", new Signature(1, true, false)),
            Map.entry("median", new Signature(1, true, false)), Map.entry("correlation", new Signature(2, true, false)),
            Map.entry("atr", new Signature(0, true, false)), Map.entry("stoch", new Signature(0, true, false)),
            Map.entry("tr", new Signature(0, false, false)), Map.entry("cum", new Signature(1, false, false)),
            Map.entry("abs", new Signature(1, false, false)), Map.entry("sqrt", new Signature(1, false, false)),
            Map.entry("log", new Signature(1, false, false)), Map.entry("log10", new Signature(1, false, false)),
            Map.entry("exp", new Signature(1, false, false)), Map.entry("sign", new Signature(1, false, false)),
            Map.entry("round", new Signature(1, false, false)), Map.entry("floor", new Signature(1, false, false)),
            Map.entry("ceil", new Signature(1, false, false)), Map.entry("min", new Signature(2, false, true)),
            Map.entry("max", new Signature(2, false, true)), Map.entry("avg", new Signature(2, false, true)),
            Map.entry("pow", new Signature(2, false, false)), Map.entry("nz", new Signature(1, false, true)),
            Map.entry("crossover", new Signature(2, false, false)), Map.entry("crossunder", new Signature(2, false, false)));

    /** Every function a formula may call, for the help text. */
    public static List<String> functions() { return FUNCTIONS.keySet().stream().sorted().toList(); }

    public static List<String> series() { return SERIES; }

    private enum TokenType { NUMBER, NAME, OPERATOR, LEFT, RIGHT, COMMA, LEFT_BRACKET, RIGHT_BRACKET, QUESTION, COLON, END }

    private record Token(TokenType type, String text, int position) { }

    private static final class Parser {
        private final List<Token> tokens = new ArrayList<>();
        private int position;
        private int nodes;

        Parser(String text) {
            int index = 0;
            while (index < text.length()) {
                char character = text.charAt(index);
                if (Character.isWhitespace(character)) { index++; continue; }
                int start = index;
                if (Character.isDigit(character) || (character == '.' && index + 1 < text.length() && Character.isDigit(text.charAt(index + 1)))) {
                    while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) index++;
                    tokens.add(new Token(TokenType.NUMBER, text.substring(start, index), start));
                } else if (Character.isLetter(character) || character == '_') {
                    while (index < text.length() && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) index++;
                    tokens.add(new Token(TokenType.NAME, text.substring(start, index).toLowerCase(Locale.ROOT), start));
                } else if (">=<!".indexOf(character) >= 0 && index + 1 < text.length() && text.charAt(index + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, text.substring(index, index + 2), start));
                    index += 2;
                } else {
                    TokenType type = switch (character) {
                        case '(' -> TokenType.LEFT;
                        case ')' -> TokenType.RIGHT;
                        case ',' -> TokenType.COMMA;
                        case '[' -> TokenType.LEFT_BRACKET;
                        case ']' -> TokenType.RIGHT_BRACKET;
                        case '?' -> TokenType.QUESTION;
                        case ':' -> TokenType.COLON;
                        case '+', '-', '*', '/', '%', '^', '<', '>' -> TokenType.OPERATOR;
                        default -> throw new UserInputException("Unexpected character '" + character + "' at position " + (index + 1) + ".");
                    };
                    tokens.add(new Token(type, String.valueOf(character), start));
                    index++;
                }
            }
            tokens.add(new Token(TokenType.END, "end of formula", text.length()));
        }

        Token peek() { return tokens.get(position); }

        Token next() { return tokens.get(position++); }

        UserInputException error(String message) {
            return new UserInputException(message + " at position " + (peek().position() + 1) + ".");
        }

        private Node count(Node node) {
            nodes++;
            return node;
        }

        private void expect(TokenType type, String text) {
            if (peek().type() != type) throw error("Expected " + text);
            next();
        }

        Node expression(int depth) {
            if (depth > MAX_DEPTH) throw error("The formula is nested too deeply");
            Node condition = or(depth);
            if (peek().type() == TokenType.QUESTION) {
                next();
                Node whenTrue = expression(depth + 1);
                expect(TokenType.COLON, "':'");
                Node whenFalse = expression(depth + 1);
                return count(new Conditional(condition, whenTrue, whenFalse));
            }
            return condition;
        }

        private Node or(int depth) {
            Node left = and(depth);
            while (peek().type() == TokenType.NAME && peek().text().equals("or")) {
                next();
                left = count(new Binary("or", left, and(depth)));
            }
            return left;
        }

        private Node and(int depth) {
            Node left = comparison(depth);
            while (peek().type() == TokenType.NAME && peek().text().equals("and")) {
                next();
                left = count(new Binary("and", left, comparison(depth)));
            }
            return left;
        }

        private Node comparison(int depth) {
            Node left = additive(depth);
            if (peek().type() == TokenType.OPERATOR && List.of(">", "<", ">=", "<=", "==", "!=").contains(peek().text())) {
                String operator = next().text();
                return count(new Binary(operator, left, additive(depth)));
            }
            return left;
        }

        private Node additive(int depth) {
            Node left = multiplicative(depth);
            while (peek().type() == TokenType.OPERATOR && (peek().text().equals("+") || peek().text().equals("-"))) {
                String operator = next().text();
                left = count(new Binary(operator, left, multiplicative(depth)));
            }
            return left;
        }

        private Node multiplicative(int depth) {
            Node left = unary(depth);
            while (peek().type() == TokenType.OPERATOR && "*/%".contains(peek().text())) {
                String operator = next().text();
                left = count(new Binary(operator, left, unary(depth)));
            }
            return left;
        }

        private Node unary(int depth) {
            if (peek().type() == TokenType.OPERATOR && (peek().text().equals("-") || peek().text().equals("+"))) {
                String operator = next().text();
                return count(new Unary(operator, unary(depth + 1)));
            }
            if (peek().type() == TokenType.NAME && peek().text().equals("not")) {
                next();
                return count(new Unary("not", unary(depth + 1)));
            }
            Node base = postfix(depth);
            if (peek().type() == TokenType.OPERATOR && peek().text().equals("^")) {
                next();
                return count(new Binary("^", base, unary(depth + 1)));
            }
            return base;
        }

        private Node postfix(int depth) {
            Node node = primary(depth);
            while (peek().type() == TokenType.LEFT_BRACKET) {
                next();
                int bars = constantInteger(depth, 0, MAX_LOOKBACK, "A history offset");
                expect(TokenType.RIGHT_BRACKET, "']'");
                node = count(new History(node, bars));
            }
            return node;
        }

        private Node primary(int depth) {
            Token token = peek();
            switch (token.type()) {
                case NUMBER -> {
                    next();
                    try {
                        return count(new Constant(Double.parseDouble(token.text())));
                    } catch (NumberFormatException invalid) {
                        throw new UserInputException("Invalid number " + token.text() + " at position " + (token.position() + 1) + ".");
                    }
                }
                case LEFT -> {
                    next();
                    Node inner = expression(depth + 1);
                    expect(TokenType.RIGHT, "')'");
                    return inner;
                }
                case NAME -> {
                    next();
                    if (peek().type() == TokenType.LEFT) return call(token, depth);
                    if (SERIES.contains(token.text())) return count(new Series(token.text()));
                    if (token.text().equals("pi")) return count(new Constant(Math.PI));
                    throw new UserInputException("Unknown name " + token.text() + " at position " + (token.position() + 1) + ".");
                }
                default -> throw error("Unexpected " + token.text());
            }
        }

        private Node call(Token name, int depth) {
            Signature signature = FUNCTIONS.get(name.text());
            if (signature == null) {
                throw new UserInputException("Unknown function " + name.text() + " at position " + (name.position() + 1) + ".");
            }
            expect(TokenType.LEFT, "'('");
            List<Node> arguments = new ArrayList<>();
            int length = 0;
            int expectedSeries = signature.series();
            for (int index = 0; index < expectedSeries; index++) {
                if (index > 0) expect(TokenType.COMMA, "','");
                arguments.add(expression(depth + 1));
            }
            if (signature.variadic()) {
                while (peek().type() == TokenType.COMMA) {
                    next();
                    arguments.add(expression(depth + 1));
                }
            }
            if (signature.length()) {
                if (expectedSeries > 0) expect(TokenType.COMMA, "',' and a length");
                length = constantInteger(depth, 1, 500, "The length of " + name.text());
            }
            expect(TokenType.RIGHT, "')'");
            return count(new Call(name.text(), List.copyOf(arguments), length));
        }

        private int constantInteger(int depth, int minimum, int maximum, String what) {
            Token token = peek();
            if (token.type() != TokenType.NUMBER) throw error(what + " must be a whole number");
            next();
            double value;
            try {
                value = Double.parseDouble(token.text());
            } catch (NumberFormatException invalid) {
                throw new UserInputException("Invalid number " + token.text() + " at position " + (token.position() + 1) + ".");
            }
            if (value != Math.rint(value) || value < minimum || value > maximum) {
                throw new UserInputException(what + " must be a whole number from " + minimum + " to " + maximum
                        + " (position " + (token.position() + 1) + ").");
            }
            return (int) value;
        }
    }
}
