/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression;

import java.sql.SQLException;
import java.util.regex.Pattern;

import org.h2.constant.ErrorCode;
import org.h2.engine.Constants;
import org.h2.engine.Session;
import org.h2.index.IndexCondition;
import org.h2.message.Message;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.util.StringUtils;
import org.h2.value.CompareMode;
import org.h2.value.Value;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueNull;
import org.h2.value.ValueString;

/**
 * @author Thomas
 */

public class CompareLike extends Condition {

    private final CompareMode compareMode;
    private final boolean regexp;
    private Expression left;
    private Expression right;
    private Expression escape;

    private boolean isInit;
    private char[] pattern;
    private String patternString;
    private Pattern patternRegexp;
    private int[] types;
    private int patternLength;
    private static final int MATCH = 0, ONE = 1, ANY = 2;
    private boolean ignoreCase;

    public CompareLike(CompareMode compareMode, Expression left, Expression right, Expression escape, boolean regexp) {
        this.compareMode = compareMode;
        this.regexp = regexp;
        this.left = left;
        this.right = right;
        this.escape = escape;
    }

    public String getSQL() {
        String sql;
        if(regexp) {
            sql = left.getSQL() + " REGEXP " + right.getSQL();
        } else {
            sql = left.getSQL() + " LIKE " + right.getSQL();
            if (escape != null) {
                sql += " ESCAPE " + escape.getSQL();
            }
        }
        return "("+sql+")";
    }

    public Expression optimize(Session session) throws SQLException {
        left = left.optimize(session);
        right = right.optimize(session);
        if(left.getType() == Value.STRING_IGNORECASE) {
            ignoreCase = true;
        }
        if(left.isConstant()) {
            Value l = left.getValue(session);
            if (l == ValueNull.INSTANCE) {
                // NULL LIKE something > NULL
                return ValueExpression.NULL;
            }
        }
        if(escape != null) {
            escape = escape.optimize(session);
        }
        if(right.isConstant() && (escape == null || escape.isConstant())) {
            if(left.isConstant()) {
                return ValueExpression.get(getValue(session));
            }
            Value r = right.getValue(session);
            if (r == ValueNull.INSTANCE) {
                // something LIKE NULL > NULL
                return ValueExpression.NULL;
            }
            Value e = escape == null ? null : escape.getValue(session);
            if(e == ValueNull.INSTANCE) {
                return ValueExpression.NULL;
            }
            String pattern = r.getString();
            initPattern(pattern, getEscapeChar(e));
            isInit = true;
        }
        return this;
    }

    private char getEscapeChar(Value e) throws SQLException {
        if(e == null) {
            return Constants.DEFAULT_ESCAPE_CHAR;
        }
        String es = e.getString();
        char esc;
        if(es == null || es.length() == 0) {
            esc = Constants.DEFAULT_ESCAPE_CHAR;
        } else {
            esc = es.charAt(0);
        }
        return esc;
    }

    public void createIndexConditions(Session session, TableFilter filter) throws SQLException {
        if(regexp) {
            return;
        }
        if(!(left instanceof ExpressionColumn)) {
            return;
        }
        ExpressionColumn l = (ExpressionColumn)left;
        if(filter != l.getTableFilter()) {
            return;
        }
        // parameters are always evaluatable, but
        // we need to check the actual value now
        // (at prepare time)
        // otherwise we would need to prepare at execute time,
        // which is maybe slower (but maybe not in this case!)
        // TODO optimizer: like: check what other databases do!
        if(!right.isConstant()) {
            return;
        }
        if(escape != null && !escape.isConstant()) {
            return;
        }
        String p = right.getValue(session).getString();
        Value e = escape == null ? null : escape.getValue(session);
        if(e == ValueNull.INSTANCE) {
            // should already be optimized
            throw Message.getInternalError();
        }
        initPattern(p, getEscapeChar(e));
        if(patternLength <= 0 || types[0] != MATCH) {
            // can't use an index
            return;
        }
        int dataType = l.getColumn().getType();
        if(dataType != Value.STRING && dataType != Value.STRING_IGNORECASE  && dataType != Value.STRING_FIXED) {
            // column is not a varchar - can't use the index
            return;
        }
        int maxMatch = 0;
        StringBuffer buff = new StringBuffer();
        while(maxMatch < patternLength && types[maxMatch] == MATCH) {
            buff.append(pattern[maxMatch++]);
        }
        String begin = buff.toString();
        if(maxMatch == patternLength) {
            filter.addIndexCondition(new IndexCondition(Comparison.EQUAL, l, ValueExpression.get(ValueString.get(begin))));
        } else {
            // TODO check if this is correct according to Unicode rules (code points)
            String end;
            if(begin.length()>0) {
                filter.addIndexCondition(new IndexCondition(Comparison.BIGGER_EQUAL, l, ValueExpression.get(ValueString.get(begin))));
                char next = begin.charAt(begin.length()-1);
                // search the 'next' unicode character (or at least a character that is higher)
                for(int i=1; i<2000; i++) {
                    end = begin.substring(0, begin.length()-1) + (char)(next+i);
                    if(compareMode.compareString(begin, end, ignoreCase) == -1) {
                        filter.addIndexCondition(new IndexCondition(Comparison.SMALLER, l, ValueExpression.get(ValueString.get(end))));
                        break;
                    }
                }
            }
        }
    }

    public Value getValue(Session session) throws SQLException {
        Value l = left.getValue(session);
        if (l == ValueNull.INSTANCE) {
            return l;
        }
        if(!isInit) {
            Value r = right.getValue(session);
            if (r == ValueNull.INSTANCE) {
                return r;
            }
            String pattern = r.getString();
            Value e = escape == null ? null : escape.getValue(session);
            if(e == ValueNull.INSTANCE) {
                return ValueNull.INSTANCE;
            }
            initPattern(pattern, getEscapeChar(e));
        }
        String value = l.getString();
        boolean result;
        if(regexp) {
            result = patternRegexp.matcher(value).matches();
        } else {
            result = compareAt(value, 0, 0, value.length());
        }
        return ValueBoolean.get(result);
    }

    private boolean compare(String s, int pi, int si) {
        // TODO check if this is correct according to Unicode rules (code points)
        return compareMode.compareString(patternString.substring(pi, pi+1), s.substring(si, si+1), ignoreCase) == 0;
    }

    private boolean compareAt(String s, int pi, int si, int sLen) {
        for (; pi < patternLength; pi++) {
            int type = types[pi];
            switch (type) {
            case MATCH:
                if ((si >= sLen) || !compare(s, pi, si++)) {
                    return false;
                }
                break;
            case ONE:
                if (si++ >= sLen) {
                    return false;
                }
                break;
            case ANY:
                if (++pi >= patternLength) {
                    return true;
                }
                while (si < sLen) {
                    if (compare(s, pi, si) && compareAt(s, pi, si, sLen)) {
                        return true;
                    }
                    si++;
                }
                return false;
            default:
                throw Message.getInternalError("type="+type);
            }
        }
        return si==sLen;
    }

    public boolean test(String pattern, String value, char escape) throws SQLException {
        initPattern(pattern, escape);
        return compareAt(value, 0, 0, value.length());
    }

    private void initPattern(String p, char escape) throws SQLException {
        if(regexp) {
            patternString = p;
            if(ignoreCase) {
                patternRegexp = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
            } else {
                patternRegexp = Pattern.compile(p);
            }
            return;
        }
        patternLength = 0;
        if(p == null) {
            types = null;
            pattern = null;
            return;
        }
        int len = p.length();
        pattern = new char[len];
        types = new int[len];
        boolean lastAny = false;
        for (int i = 0; i < len; i++) {
            char c = p.charAt(i);
            int type;
            if (escape == c) {
                if (i >= len - 1) {
                    throw Message.getSQLException(ErrorCode.LIKE_ESCAPE_ERROR_1, StringUtils.addAsterisk(p, i));
                }
                c = p.charAt(++i);
                if(c != '_' && c != '%' && c != escape) {
                    throw Message.getSQLException(ErrorCode.LIKE_ESCAPE_ERROR_1, StringUtils.addAsterisk(p, i));
                }
                type = MATCH;
                lastAny = false;
            } else if (c == '%') {
                if(lastAny) {
                    continue;
                }
                type = ANY;
                lastAny = true;
            } else if (c == '_') {
                type = ONE;
            } else {
                type = MATCH;
                lastAny = false;
            }
            types[patternLength] = type;
            pattern[patternLength++] = c;
        }
        for (int i = 0; i < patternLength - 1; i++) {
            if ((types[i] == ANY) && (types[i + 1] == ONE)) {
                types[i]     = ONE;
                types[i + 1] = ANY;
            }
        }
        patternString = new String(pattern);
    }

    public void mapColumns(ColumnResolver resolver, int level) throws SQLException {
        left.mapColumns(resolver, level);
        right.mapColumns(resolver, level);
        if(escape != null) {
            escape.mapColumns(resolver, level);
        }
    }

    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
        right.setEvaluatable(tableFilter, b);
        if(escape != null) {
            escape.setEvaluatable(tableFilter, b);
        }
    }

    public void updateAggregate(Session session) throws SQLException {
        left.updateAggregate(session);
        right.updateAggregate(session);
        if(escape != null) {
            escape.updateAggregate(session);
        }
    }

    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && right.isEverything(visitor) && (escape== null || escape.isEverything(visitor));
    }
    
    public int getCost() {
        return left.getCost() + right.getCost() + 3;
    }
    
}
