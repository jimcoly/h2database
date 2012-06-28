/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.java;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * An expression.
 */
public interface Expr {

    String asString();
    Type getType();
    Expr cast(Type type);

}

/**
 * The base expression class.
 */
abstract class ExprBase implements Expr {
    public final String toString() {
        return "_" + asString() + "_";
    }
}

/**
 * A method call.
 */
class CallExpr extends ExprBase {

    final ArrayList<Expr> args = new ArrayList<Expr>();
    private final JavaParser context;
    private final String className;
    private final String name;
    private Expr expr;
    private ClassObj classObj;
    private MethodObj method;

    CallExpr(JavaParser context, Expr expr, String className, String name) {
        this.context = context;
        this.expr = expr;
        this.className = className;
        this.name = name;
    }

    private void initMethod() {
        if (method != null) {
            return;
        }
        if (className != null) {
            classObj = context.getClassObj(className);
        } else {
            classObj = expr.getType().classObj;
        }
        method = classObj.getMethod(name, args);
        if (method.isStatic) {
            expr = null;
        }
    }

    public String asString() {
        StringBuilder buff = new StringBuilder();
        initMethod();
        if (method.isIgnore) {
            if (args.size() == 0) {
                // ignore
            } else if (args.size() == 1) {
                buff.append(args.get(0));
            } else {
                throw new IllegalArgumentException(
                        "Cannot ignore method with multiple arguments: " + method);
            }
        } else {
            if (expr == null) {
                // static method
                buff.append(JavaParser.toC(classObj.toString() + "." + method.name));
            } else {
                buff.append(expr.asString()).append("->");
                buff.append(method.name);
            }
            buff.append("(");
            int i = 0;
            Iterator<FieldObj> paramIt = method.parameters.values().iterator();
            for (Expr a : args) {
                if (i > 0) {
                    buff.append(", ");
                }
                FieldObj f = paramIt.next();
                i++;
                buff.append(a.cast(f.type).asString());
            }
            buff.append(")");
        }
        return buff.toString();
    }

    public Type getType() {
        initMethod();
        return method.returnType;
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A assignment expression.
 */
class AssignExpr extends ExprBase {

    Expr left;
    String op;
    Expr right;

    public String asString() {
        return left.asString() + " " + op + " " + right.cast(left.getType()).asString();
    }

    public Type getType() {
        return left.getType();
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A conditional expression.
 */
class ConditionalExpr extends ExprBase {

    Expr condition;
    Expr ifTrue, ifFalse;

    public String asString() {
        return condition.asString() + " ? " + ifTrue.asString() + " : " + ifFalse.asString();
    }

    public Type getType() {
        return ifTrue.getType();
    }

    public Expr cast(Type type) {
        ConditionalExpr e2 = new ConditionalExpr();
        e2.condition = condition;
        e2.ifTrue = ifTrue.cast(type);
        e2.ifFalse = ifFalse.cast(type);
        return e2;
    }

}

/**
 * A literal.
 */
class LiteralExpr extends ExprBase {

    String literal;
    private final JavaParser context;
    private final String className;
    private Type type;

    public LiteralExpr(JavaParser context, String className) {
        this.context = context;
        this.className = className;
    }

    public String asString() {
        if ("null".equals(literal)) {
            return type.asString() + "()";
        }
        return literal;
    }

    public Type getType() {
        if (type == null) {
            type = new Type();
            type.classObj = context.getClassObj(className);
        }
        return type;
    }

    public Expr cast(Type type) {
        if ("null".equals(literal)) {
            // TODO should be immutable
            this.type = type;
        }
        return this;
    }

}

/**
 * An operation.
 */
class OpExpr extends ExprBase {

    Expr left;
    String op;
    Expr right;
    private final JavaParser context;

    OpExpr(JavaParser context) {
        this.context = context;
    }

    public String asString() {
        if (left == null) {
            return op + right.asString();
        } else if (right == null) {
            return left.asString() + op;
        }
        if (op.equals(">>>")) {
            // ujint / ujlong
            return "(((u" + left.getType() + ") " + left + ") >> " + right + ")";
        } else if (op.equals("+")) {
            if (left.getType().isObject() || right.getType().isObject()) {
                // TODO convert primitive to to String, call toString
                StringBuilder buff = new StringBuilder();
                if (JavaParser.REF_COUNT) {
                    buff.append("ptr<java_lang_StringBuilder>(new java_lang_StringBuilder(");
                } else {
                    buff.append("(new java_lang_StringBuilder(");
                }
                buff.append(convertToString(left));
                buff.append("))->append(");
                buff.append(convertToString(right));
                buff.append(")->toString()");
                return buff.toString();
            }
        }
        return "(" + left.asString() + " " + op + " " + right.asString() + ")";
    }

    private String convertToString(Expr e) {
        Type t = e.getType();
        if (t.arrayLevel > 0) {
            return e.toString() + "->toString()";
        }
        if (t.classObj.isPrimitive) {
            ClassObj wrapper = context.getWrapper(t.classObj);
            return JavaParser.toC(wrapper + ".toString") + "(" + e.asString() + ")";
        } else if (e.getType().asString().equals("java_lang_String*")) {
            return e.asString();
        }
        return e.asString() + "->toString()";
    }

    private static boolean isComparison(String op) {
        return op.equals("==") || op.equals(">") || op.equals("<") ||
                op.equals(">=") || op.equals("<=") || op.equals("!=");
    }

    public Type getType() {
        if (left == null) {
            return right.getType();
        }
        if (right == null) {
            return left.getType();
        }
        if (isComparison(op)) {
            Type t = new Type();
            t.classObj = JavaParser.getBuiltInClass("boolean");
            return t;
        }
        if (op.equals("+")) {
            if (left.getType().isObject() || right.getType().isObject()) {
                Type t = new Type();
                t.classObj = context.getClassObj("java.lang.String");
                return t;
            }
        }
        Type lt = left.getType();
        Type rt = right.getType();
        if (lt.classObj.primitiveType < rt.classObj.primitiveType) {
            return rt;
        }
        return lt;
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A "new" expression.
 */
class NewExpr extends ExprBase {

    ClassObj classObj;
    ArrayList<Expr> arrayInitExpr = new ArrayList<Expr>();
    ArrayList<Expr> args = new ArrayList<Expr>();
    final JavaParser context;

    NewExpr(JavaParser context) {
        this.context = context;
    }

    public String asString() {
        StringBuilder buff = new StringBuilder();
        if (arrayInitExpr.size() > 0) {
            if (JavaParser.REF_COUNT) {
                buff.append("ptr< array< " + classObj.toString() + " > >");
            }
            buff.append("(new array< " + classObj + " >(1 ");
            for (Expr e : arrayInitExpr) {
                buff.append("* ").append(e.asString());
            }
            buff.append("))");
        } else {
            if (JavaParser.REF_COUNT) {
                buff.append("ptr< " + classObj.toString() + " >");
            }
            buff.append("(new " + JavaParser.toC(classObj.toString()));
            buff.append("(");
            int i = 0;
            for (Expr a : args) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                buff.append(a.asString());
            }
            buff.append("))");
        }
        return buff.toString();
    }

    public Type getType() {
        Type t = new Type();
        t.classObj = classObj;
        t.arrayLevel = arrayInitExpr.size();
        return t;
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A String literal.
 */
class StringExpr extends ExprBase {

    /**
     * The constant name.
     */
    String constantName;

    /**
     * The literal.
     */
    String text;

    private final JavaParser context;
    private Type type;

    StringExpr(JavaParser context) {
        this.context = context;
    }

    public String asString() {
        return constantName;
    }

    public Type getType() {
        if (type == null) {
            type = new Type();
            type.classObj = context.getClassObj("java.lang.String");
        }
        return type;
    }

    /**
     * Encode the String to Java syntax.
     *
     * @param s the string
     * @return the encoded string
     */
    static String javaEncode(String s) {
        StringBuilder buff = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '\t':
                // HT horizontal tab
                buff.append("\\t");
                break;
            case '\n':
                // LF linefeed
                buff.append("\\n");
                break;
            case '\f':
                // FF form feed
                buff.append("\\f");
                break;
            case '\r':
                // CR carriage return
                buff.append("\\r");
                break;
            case '"':
                // double quote
                buff.append("\\\"");
                break;
            case '\\':
                // backslash
                buff.append("\\\\");
                break;
            default:
                int ch = c & 0xffff;
                if (ch >= ' ' && (ch < 0x80)) {
                    buff.append(c);
                // not supported in properties files
                // } else if(ch < 0xff) {
                // buff.append("\\");
                // // make sure it's three characters (0x200 is octal 1000)
                // buff.append(Integer.toOctalString(0x200 | ch).substring(1));
                } else {
                    buff.append("\\u");
                    // make sure it's four characters
                    buff.append(Integer.toHexString(0x10000 | ch).substring(1));
                }
            }
        }
        return buff.toString();
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A variable.
 */
class VariableExpr extends ExprBase {

    Expr base;
    FieldObj field;
    String name;
    private final JavaParser context;

    VariableExpr(JavaParser context) {
        this.context = context;
    }

    public String asString() {
        init();
        StringBuilder buff = new StringBuilder();
        if (base != null) {
            buff.append(base.asString()).append("->");
        }
        if (field != null) {
            if (field.isStatic) {
                buff.append(JavaParser.toC(field.declaredClass + "." + field.name));
            } else if (field.name != null) {
                buff.append(field.name);
            } else if ("length".equals(name) && base.getType().arrayLevel > 0) {
                buff.append("length()");
            }
        } else {
            buff.append(JavaParser.toC(name));
        }
        return buff.toString();
    }

    private void init() {
        if (field == null) {
            Type t = base.getType();
            if (t.arrayLevel > 0) {
                if ("length".equals(name)) {
                    field = new FieldObj();
                    field.type = context.getClassObj("int").baseType;
                } else {
                    throw new IllegalArgumentException("Unknown array method: " + name);
                }
            } else {
                field = t.classObj.getField(name);
            }
        }
    }

    public Type getType() {
        init();
        return field.type;
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A array access expression.
 */
class ArrayExpr extends ExprBase {

    Expr expr;
    ArrayList<Expr> indexes = new ArrayList<Expr>();

    public String asString() {
        StringBuilder buff = new StringBuilder();
        buff.append(expr.toString());
        for (Expr e : indexes) {
            buff.append('[').append(e.toString()).append(']');
        }
        return buff.toString();
    }

    public Type getType() {
        return expr.getType();
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * An array initializer expression.
 */
class ArrayInitExpr extends ExprBase {

    Type type;
    ArrayList<Expr> list = new ArrayList<Expr>();

    public Type getType() {
        return type;
    }

    public String asString() {
        StringBuilder buff = new StringBuilder("{ ");
        int i = 0;
        for (Expr e : list) {
            if (i++ > 0) {
                buff.append(", ");
            }
            buff.append(e.toString());
        }
        buff.append(" }");
        return buff.toString();
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * A type cast expression.
 */
class CastExpr extends ExprBase {

    Type type;
    Expr expr;

    public Type getType() {
        return type;
    }

    public String asString() {
        return "(" + type.asString() + ") " + expr.asString();
    }

    public Expr cast(Type type) {
        return this;
    }

}

/**
 * An array access expression (get or set).
 */
class ArrayAccessExpr extends ExprBase {

    Expr base;
    Expr index;

    public Type getType() {
        Type t = new Type();
        t.classObj = base.getType().classObj;
        t.arrayLevel = base.getType().arrayLevel - 1;
        return t;
    }

    public String asString() {
        return base.asString() + "->at(" + index.asString() + ")";
    }

    public Expr cast(Type type) {
        return this;
    }

}