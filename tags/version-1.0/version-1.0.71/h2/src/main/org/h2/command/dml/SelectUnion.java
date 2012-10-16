/*
 * Copyright 2004-2008 H2 Group. Multiple-Licensed under the H2 License, 
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.sql.SQLException;
import java.util.HashSet;

import org.h2.constant.ErrorCode;
import org.h2.constant.SysProperties;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.expression.ValueExpression;
import org.h2.message.Message;
import org.h2.result.LocalResult;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.util.ObjectArray;
import org.h2.util.StringUtils;
import org.h2.value.Value;
import org.h2.value.ValueInt;

/**
 * Represents a union SELECT statement.
 */
public class SelectUnion extends Query {

    /**
     * The type of a UNION statement.
     */
    public static final int UNION = 0;

    /**
     * The type of a UNION ALL statement.
     */
    public static final int UNION_ALL = 1;
    
    /**
     * The type of an EXCEPT statement.
     */
    public static final int EXCEPT = 2;
    
    /**
     * The type of an INTERSECT statement.
     */
    public static final int INTERSECT = 3;
    
    private int unionType;
    private Query left, right;
    private ObjectArray expressions;
    private ObjectArray orderList;
    private SortOrder sort;
    private boolean distinct;
    private boolean isPrepared, checkInit;
    private boolean isForUpdate;

    public SelectUnion(Session session, Query query) {
        super(session);
        this.left = query;
    }

    public void setUnionType(int type) {
        this.unionType = type;
    }

    public void setRight(Query select) throws SQLException {
        right = select;
    }

    public void setSQL(String sql) {
        this.sql = sql;
    }

    public void setOrder(ObjectArray order) {
        orderList = order;
    }

    private Value[] convert(Value[] values, int columnCount) throws SQLException {
        for (int i = 0; i < columnCount; i++) {
            Expression e = (Expression) expressions.get(i);
            values[i] = values[i].convertTo(e.getType());
        }
        return values;
    }

    public LocalResult queryMeta() throws SQLException {
        ObjectArray expressions = left.getExpressions();
        int columnCount = left.getColumnCount();
        LocalResult result = new LocalResult(session, expressions, columnCount);
        result.done();
        return result;
    }

    protected LocalResult queryWithoutCache(int maxrows) throws SQLException {
        if (maxrows != 0) {
            if (limit != null) {
                maxrows = Math.min(limit.getValue(session).getInt(), maxrows);
            }
            limit = ValueExpression.get(ValueInt.get(maxrows));
        }
        int columnCount = left.getColumnCount();
        LocalResult result = new LocalResult(session, expressions, columnCount);
        result.setSortOrder(sort);
        if (distinct) {
            left.setDistinct(true);
            right.setDistinct(true);
            result.setDistinct();
        }
        switch (unionType) {
        case UNION:
        case EXCEPT:
            left.setDistinct(true);
            right.setDistinct(true);
            result.setDistinct();
            break;
        case UNION_ALL:
            break;
        case INTERSECT:
            left.setDistinct(true);
            right.setDistinct(true);
            break;
        default:
            throw Message.getInternalError("type=" + unionType);
        }
        LocalResult l = left.query(0);
        LocalResult r = right.query(0);
        l.reset();
        r.reset();
        switch (unionType) {
        case UNION_ALL:
        case UNION: {
            while (l.next()) {
                result.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                result.addRow(convert(r.currentRow(), columnCount));
            }
            break;
        }
        case EXCEPT: {
            while (l.next()) {
                result.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                result.removeDistinct(convert(r.currentRow(), columnCount));
            }
            break;
        }
        case INTERSECT: {
            LocalResult temp = new LocalResult(session, expressions, columnCount);
            temp.setDistinct();
            while (l.next()) {
                temp.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                Value[] values = convert(r.currentRow(), columnCount);
                if (temp.containsDistinct(values)) {
                    result.addRow(values);
                }
            }
            break;
        }
        default:
            throw Message.getInternalError("type=" + unionType);
        }
        if (offset != null) {
            result.setOffset(offset.getValue(session).getInt());
        }
        if (limit != null) {
            result.setLimit(limit.getValue(session).getInt());
        }
        result.done();
        return result;
    }

    public void init() throws SQLException {
        if (SysProperties.CHECK && checkInit) {
            throw Message.getInternalError();
        }
        checkInit = true;
        left.init();
        right.init();
        int len = left.getColumnCount();
        if (len != right.getColumnCount()) {
            throw Message.getSQLException(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
        }
        ObjectArray le = left.getExpressions();
        // set the expressions to get the right column count and names,
        // but can't validate at this time
        expressions = new ObjectArray();
        for (int i = 0; i < len; i++) {
            Expression l = (Expression) le.get(i);
            expressions.add(l);
        }
    }

    public void prepare() throws SQLException {
        if (isPrepared) {
            // sometimes a subquery is prepared twice (CREATE TABLE AS SELECT)
            return;
        }
        if (SysProperties.CHECK && !checkInit) {
            throw Message.getInternalError("not initialized");
        }
        isPrepared = true;
        left.prepare();
        right.prepare();
        int len = left.getColumnCount();
        // set the correct expressions now
        expressions = new ObjectArray();
        ObjectArray le = left.getExpressions();
        ObjectArray re = right.getExpressions();
        for (int i = 0; i < len; i++) {
            Expression l = (Expression) le.get(i);
            Expression r = (Expression) re.get(i);
            int type = Value.getHigherOrder(l.getType(), r.getType());
            long prec = Math.max(l.getPrecision(), r.getPrecision());
            int scale = Math.max(l.getScale(), r.getScale());
            int displaySize = Math.max(l.getDisplaySize(), r.getDisplaySize());
            Column col = new Column(l.getAlias(), type, prec, scale, displaySize);
            Expression e = new ExpressionColumn(session.getDatabase(), col);
            expressions.add(e);
        }
        if (orderList != null) {
            initOrder(expressions, null, orderList, getColumnCount(), true);
            sort = prepareOrder(orderList, expressions.size());
            orderList = null;
        }
    }
    
    public double getCost() {
        return left.getCost() + right.getCost();
    }

    public HashSet getTables() {
        HashSet set = left.getTables();
        set.addAll(right.getTables());
        return set;
    }

    public void setDistinct(boolean b) {
        distinct = b;
    }

    public ObjectArray getExpressions() {
        return expressions;
    }

    public void setForUpdate(boolean forUpdate) {
        left.setForUpdate(forUpdate);
        right.setForUpdate(forUpdate);
        isForUpdate = forUpdate;
    }

    public int getColumnCount() {
        return left.getColumnCount();
    }

    public void mapColumns(ColumnResolver resolver, int level) throws SQLException {
        left.mapColumns(resolver, level);
        right.mapColumns(resolver, level);
    }

    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
        right.setEvaluatable(tableFilter, b);
    }

    public void addGlobalCondition(Parameter param, int columnId, int comparisonType) throws SQLException {
        addParameter(param);
        switch (unionType) {
        case UNION_ALL:
        case UNION:
        case INTERSECT: {
            left.addGlobalCondition(param, columnId, comparisonType);
            right.addGlobalCondition(param, columnId, comparisonType);
            break;
        }
        case EXCEPT: {
            left.addGlobalCondition(param, columnId, comparisonType);
            break;
        }
        default:
            throw Message.getInternalError("type=" + unionType);
        }
    }

    public String getPlanSQL() {
        StringBuffer buff = new StringBuffer();
        buff.append('(');
        buff.append(left.getPlanSQL());
        buff.append(") ");
        switch (unionType) {
        case UNION_ALL:
            buff.append("UNION ALL ");
            break;
        case UNION:
            buff.append("UNION ");
            break;
        case INTERSECT:
            buff.append("INTERSECT ");
            break;
        case EXCEPT:
            buff.append("EXCEPT ");
            break;
        default:
            throw Message.getInternalError("type=" + unionType);
        }
        buff.append('(');
        buff.append(right.getPlanSQL());
        buff.append(')');
        Expression[] exprList = new Expression[expressions.size()];
        expressions.toArray(exprList);
        if (sort != null) {
            buff.append(" ORDER BY ");
            buff.append(sort.getSQL(exprList, exprList.length));
        }
        // TODO refactoring: limit and order by could be in Query (now in
        // SelectUnion and in Select)
        if (limit != null) {
            buff.append(" LIMIT ");
            buff.append(StringUtils.unEnclose(limit.getSQL()));
            if (offset != null) {
                buff.append(" OFFSET ");
                buff.append(StringUtils.unEnclose(offset.getSQL()));
            }
        }
        if (isForUpdate) {
            buff.append(" FOR UPDATE");
        }
        return buff.toString();
    }

    public LocalResult query(int limit) throws SQLException {
        // union doesn't always know the parameter list of the left and right queries
        return queryWithoutCache(limit);
    }

    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && right.isEverything(visitor);
    }

    public boolean isReadOnly() {
        return left.isReadOnly() && right.isReadOnly();
    }

    public void updateAggregate(Session session) throws SQLException {
        left.updateAggregate(session);
        right.updateAggregate(session);
    }

    public String getFirstColumnAlias(Session session) {
        return null;
    }

}