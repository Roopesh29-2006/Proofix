package com.proofix.runner.source;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny copy of the expressions we can evaluate (string literals, constant names and '+'
 * chains of them), so no syntax tree has to be kept after a file is processed.
 */
public sealed interface Expr permits Expr.Lit, Expr.Name, Expr.Concat, Expr.Other {

    /** Max length of the source text kept for an expression we cannot evaluate. */
    int MAX_TEXT = 200;

    /** A string literal (escapes already processed). */
    record Lit(String value) implements Expr {}

    /** BAR (qualifier null), Foo.BAR or com.x.Foo.BAR (qualifier "com.x.Foo"). */
    record Name(String qualifier, String name) implements Expr {
        public String text() {
            return qualifier == null ? name : qualifier + "." + name;
        }
    }

    /** a + b + c, flattened. */
    record Concat(List<Expr> parts) implements Expr {
        public Concat {
            parts = List.copyOf(parts);
        }
    }

    /** Anything else; text is the printed expression, cleaned and cut to {@link #MAX_TEXT}. */
    record Other(String text) implements Expr {}

    static Expr of(Expression e) {
        if (e instanceof EnclosedExpr en) return of(en.getInner());
        if (e instanceof StringLiteralExpr s) return new Lit(s.asString());
        if (e instanceof TextBlockLiteralExpr t) return new Lit(t.asString());
        if (e instanceof NameExpr n) return new Name(null, n.getNameAsString());
        if (e instanceof FieldAccessExpr f && isNameChain(f.getScope())) {
            return new Name(f.getScope().toString(), f.getNameAsString());
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            List<Expr> parts = new ArrayList<>();
            flatten(of(b.getLeft()), parts);
            flatten(of(b.getRight()), parts);
            if (parts.stream().noneMatch(p -> p instanceof Other)) return new Concat(parts);
        }
        return new Other(clean(e.toString()));
    }

    private static void flatten(Expr e, List<Expr> out) {
        if (e instanceof Concat c) out.addAll(c.parts());
        else out.add(e);
    }

    private static boolean isNameChain(Expression e) {
        if (e instanceof NameExpr) return true;
        return e instanceof FieldAccessExpr f && isNameChain(f.getScope());
    }

    /** Collapses whitespace, drops control characters, cuts to MAX_TEXT. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                space = b.length() > 0;
                continue;
            }
            if (Character.isISOControl(c)) continue;
            if (space) {
                b.append(' ');
                space = false;
                if (b.length() >= MAX_TEXT) break;
            }
            b.append(c);
        }
        return b.toString();
    }
}
