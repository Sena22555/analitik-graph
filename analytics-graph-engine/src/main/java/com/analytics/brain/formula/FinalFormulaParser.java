package com.analytics.brain.formula;

import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FinalFormulaParser {

    private static final String[] COMPARISON_TOKENS = {">=", "<=", "!=", "==", "<>", ">", "<", "="};
    private static final EnumFormulaOperator[] COMPARISON_OPERATORS = {
            EnumFormulaOperator.GREATER_OR_EQUAL, EnumFormulaOperator.LESS_OR_EQUAL, EnumFormulaOperator.NOT_EQUAL,
            EnumFormulaOperator.EQUAL, EnumFormulaOperator.NOT_EQUAL, EnumFormulaOperator.GREATER,
            EnumFormulaOperator.LESS, EnumFormulaOperator.EQUAL
    };

    private final String src;
    private final FinalDataset ds;
    private int pos;

    private FinalFormulaParser(String src, FinalDataset ds) {
        this.src = src == null ? "" : src;
        this.ds = ds;
    }

    public static InterfaceFormulaNode parse(String src, FinalDataset ds) {
        FinalFormulaParser parser = new FinalFormulaParser(src, ds);
        InterfaceFormulaNode root = parser.or();
        parser.skipSpaces();
        if (parser.pos < parser.src.length()) {
            throw new ExceptionAnalitikData("Ошибка в формуле около: «" + parser.src.substring(parser.pos) + "»");
        }
        return root;
    }

    private InterfaceFormulaNode or() {
        InterfaceFormulaNode node = and();
        while (eat("||") || eatWord("or") || eatWord("или")) {
            node = new FinalBinaryNode(EnumFormulaOperator.OR, node, and());
        }
        return node;
    }

    private InterfaceFormulaNode and() {
        InterfaceFormulaNode node = comparison();
        while (eat("&&") || eatWord("and") || eatWord("и")) {
            node = new FinalBinaryNode(EnumFormulaOperator.AND, node, comparison());
        }
        return node;
    }

    private InterfaceFormulaNode comparison() {
        InterfaceFormulaNode left = sum();
        for (int k = 0; k < COMPARISON_TOKENS.length; k++) {
            if (eat(COMPARISON_TOKENS[k])) return new FinalBinaryNode(COMPARISON_OPERATORS[k], left, sum());
        }
        return left;
    }

    private InterfaceFormulaNode sum() {
        InterfaceFormulaNode node = product();
        while (true) {
            if (eat("+")) {
                node = new FinalBinaryNode(EnumFormulaOperator.PLUS, node, product());
            } else if (eat("-")) {
                node = new FinalBinaryNode(EnumFormulaOperator.MINUS, node, product());
            } else {
                return node;
            }
        }
    }

    private InterfaceFormulaNode product() {
        InterfaceFormulaNode node = unary();
        while (true) {
            if (eat("*")) {
                node = new FinalBinaryNode(EnumFormulaOperator.MULTIPLY, node, unary());
            } else if (eat("/")) {
                node = new FinalBinaryNode(EnumFormulaOperator.DIVIDE, node, unary());
            } else if (eat("%")) {
                node = new FinalBinaryNode(EnumFormulaOperator.MODULO, node, unary());
            } else {
                return node;
            }
        }
    }

    private InterfaceFormulaNode unary() {
        if (eat("-")) return new FinalUnaryNode(EnumFormulaOperator.NEGATE, unary());
        if (eat("!") || eatWord("not") || eatWord("не")) return new FinalUnaryNode(EnumFormulaOperator.NOT, unary());
        return atom();
    }

    private InterfaceFormulaNode atom() {
        skipSpaces();
        if (pos >= src.length()) throw new ExceptionAnalitikData("Формула оборвалась");
        char c = src.charAt(pos);

        if (c == '(') {
            pos++;
            InterfaceFormulaNode inner = or();
            if (!eat(")")) throw new ExceptionAnalitikData("Не хватает «)»");
            return inner;
        }
        if (c == '[') {
            int end = src.indexOf(']', pos);
            if (end < 0) throw new ExceptionAnalitikData("Не закрыта «]»");
            String name = src.substring(pos + 1, end);
            pos = end + 1;
            return new FinalCellNode(ds, name);
        }
        if (c == '"' || c == '\'' || c == '«') {
            char close = c == '«' ? '»' : c;
            int end = src.indexOf(close, pos + 1);
            if (end < 0) throw new ExceptionAnalitikData("Не закрыта строка");
            String text = src.substring(pos + 1, end);
            pos = end + 1;
            return new FinalConstantNode(text);
        }
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            return new FinalConstantNode(Double.parseDouble(src.substring(start, pos)));
        }
        if (Character.isLetter(c) || c == '_') {
            return wordOrFunction();
        }
        throw new ExceptionAnalitikData("Непонятный символ «" + c + "» в формуле");
    }

    private InterfaceFormulaNode wordOrFunction() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        String word = src.substring(start, pos);
        String name = word.toLowerCase(Locale.ROOT);

        if (name.equals("true") || name.equals("истина")) return new FinalConstantNode(true);
        if (name.equals("false") || name.equals("ложь")) return new FinalConstantNode(false);

        if (!eat("(")) {
            FinalColumn column = ds.col(word);
            if (column != null) return new FinalCellNode(ds, column.name);
            throw new ExceptionAnalitikData("Неизвестное имя «" + word + "» — колонки пишите в [квадратных скобках]");
        }

        List<InterfaceFormulaNode> args = new ArrayList<>();
        if (!eat(")")) {
            do {
                args.add(or());
            } while (eat(",") || eat(";"));
            if (!eat(")")) throw new ExceptionAnalitikData("Не хватает «)» в " + name);
        }
        return new FinalFunctionNode(EnumFormulaFunction.byName(name), args);
    }

    private void skipSpaces() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean eat(String token) {
        skipSpaces();
        if (src.regionMatches(true, pos, token, 0, token.length())) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private boolean eatWord(String word) {
        skipSpaces();
        int end = pos + word.length();
        boolean match = src.regionMatches(true, pos, word, 0, word.length());
        boolean wholeWord = end >= src.length() || !Character.isLetterOrDigit(src.charAt(end));
        if (match && wholeWord) {
            pos = end;
            return true;
        }
        return false;
    }
}
