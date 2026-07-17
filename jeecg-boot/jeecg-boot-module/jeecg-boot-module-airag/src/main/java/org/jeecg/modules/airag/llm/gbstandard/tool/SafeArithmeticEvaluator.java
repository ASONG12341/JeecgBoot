//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】安全四则运算解析器（白名单 +−*/()，不 eval LLM 字符串）-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 安全四则运算解析器。
 * <p>仅支持 + - * / ( ) 和数字、变量代入。禁止函数调用、禁止非法字符。
 * 用于 GbCalculationTool 对公式做变量代入计算，绝不全信任 LLM 字符串 eval。
 * 失败返回 Optional.empty()，不抛异常到调用方（Tool 退化为返回静态 paramValue）。
 * </p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class SafeArithmeticEvaluator {

    /** 合法字符: digits, dot, + - * / ( ), whitespace, letters/digits/underscore (variable names) */
    private static final java.util.regex.Pattern SAFE_CHARS =
            java.util.regex.Pattern.compile("^[0-9.+\\-*/()\\sA-Za-z_]+$");

    public Optional<BigDecimal> evaluate(String expr, Map<String, BigDecimal> variables) {
        if (expr == null || expr.isBlank()) return Optional.empty();
        // 1. 字符白名单预筛（拒绝 ; 中文 关键字等）
        if (!SAFE_CHARS.matcher(expr).matches()) return Optional.empty();
        try {
            // 2. 变量代入（未知变量 → 整体失败）
            String substituted = substituteVariables(expr, variables);
            if (substituted == null) return Optional.empty(); // 含未知变量
            // 3. 递归下降解析
            java.text.ParsePosition pos = new java.text.ParsePosition(0);
            BigDecimal result = new Parser(substituted, pos).parseExpression();
            if (pos.getIndex() != substituted.length() || result == null) return Optional.empty();
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 把变量名替换为数值；遇未知变量返回 null（触发整体失败） */
    private String substituteVariables(String expr, Map<String, BigDecimal> variables) {
        if (variables == null || variables.isEmpty()) {
            // 无变量表时，表达式不应含字母（纯数字算式）
            if (expr.chars().anyMatch(Character::isLetter)) return null;
            return expr;
        }
        String result = expr;
        for (Map.Entry<String, BigDecimal> e : variables.entrySet()) {
            result = result.replace(e.getKey(), e.getValue().toPlainString());
        }
        // 代入后若仍有字母 → 未知变量
        if (result.chars().anyMatch(Character::isLetter)) return null;
        return result;
    }

    /** 简单递归下降解析器（expression: term (('+'|'-') term)*; term: factor (('*'|'/') factor)*; factor: number | '(' expression ')'） */
    private static class Parser {
        private final String s;
        private final java.text.ParsePosition pos;
        Parser(String s, java.text.ParsePosition pos) { this.s = s; this.pos = pos; }

        BigDecimal parseExpression() {
            BigDecimal left = parseTerm();
            while (left != null) {
                skipWs();
                char op = peek();
                if (op == '+') { pos.setIndex(pos.getIndex()+1); left = left.add(parseTerm()); }
                else if (op == '-') { pos.setIndex(pos.getIndex()+1); left = left.subtract(parseTerm()); }
                else break;
            }
            return left;
        }

        BigDecimal parseTerm() {
            BigDecimal left = parseFactor();
            while (left != null) {
                skipWs();
                char op = peek();
                if (op == '*') { pos.setIndex(pos.getIndex()+1); left = left.multiply(parseFactor()); }
                else if (op == '/') {
                    pos.setIndex(pos.getIndex()+1);
                    BigDecimal d = parseFactor();
                    if (d == null || d.signum() == 0) return null;
                    left = left.divide(d, 10, java.math.RoundingMode.HALF_UP);
                }
                else break;
            }
            return left;
        }

        BigDecimal parseFactor() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos.setIndex(pos.getIndex()+1);
                BigDecimal inner = parseExpression();
                skipWs();
                if (peek() == ')') pos.setIndex(pos.getIndex()+1);
                else return null;
                return inner;
            }
            if (c == '-') { pos.setIndex(pos.getIndex()+1); BigDecimal f = parseFactor(); return f == null ? null : f.negate(); }
            return parseNumber();
        }

        BigDecimal parseNumber() {
            skipWs();
            int start = pos.getIndex();
            while (pos.getIndex() < s.length()) {
                char c = s.charAt(pos.getIndex());
                if (Character.isDigit(c) || c == '.') pos.setIndex(pos.getIndex()+1); else break;
            }
            if (pos.getIndex() == start) return null;
            try { return new BigDecimal(s.substring(start, pos.getIndex())); }
            catch (NumberFormatException e) { return null; }
        }

        void skipWs() { while (pos.getIndex() < s.length() && Character.isWhitespace(s.charAt(pos.getIndex()))) pos.setIndex(pos.getIndex()+1); }
        char peek() { return pos.getIndex() < s.length() ? s.charAt(pos.getIndex()) : '\0'; }
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】安全四则运算解析器-----------
