/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.dialect.postgresql.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLArrayDataType;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.SQLCharacterDataType;
import com.alibaba.druid.sql.dialect.postgresql.ast.expr.*;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.util.FnvHash;

import java.util.Arrays;

public class PGExprParser extends SQLExprParser {

    public final static String[] AGGREGATE_FUNCTIONS;

    public final static long[] AGGREGATE_FUNCTIONS_CODES;

    static {
        String[] strings = { "AVG", "COUNT", "MAX", "MIN", "STDDEV", "SUM", "ROW_NUMBER","PERCENTILE_CONT", "PERCENTILE_DISC", "RANK", "DENSE_RANK","PERCENT_RANK","CUME_DIST" };

        AGGREGATE_FUNCTIONS_CODES = FnvHash.fnv1a_64_lower(strings, true);
        AGGREGATE_FUNCTIONS = new String[AGGREGATE_FUNCTIONS_CODES.length];
        for (String str : strings) {
            long hash = FnvHash.fnv1a_64_lower(str);
            int index = Arrays.binarySearch(AGGREGATE_FUNCTIONS_CODES, hash);
            AGGREGATE_FUNCTIONS[index] = str;
        }
    }

    public PGExprParser(String sql){
        this(new PGLexer(sql));
        this.lexer.nextToken();
        this.dbType = DbType.postgresql;
    }

    public PGExprParser(String sql, SQLParserFeature... features){
        this(new PGLexer(sql));
        this.lexer.nextToken();
        this.dbType = DbType.postgresql;
    }

    public PGExprParser(Lexer lexer){
        super(lexer);
        this.aggregateFunctions = AGGREGATE_FUNCTIONS;
        this.aggregateFunctionHashCodes = AGGREGATE_FUNCTIONS_CODES;
        this.dbType = DbType.postgresql;
    }
    
    @Override
    public SQLDataType parseDataType() {
        if (lexer.token() == Token.TYPE) {
            lexer.nextToken();
        }
        return super.parseDataType();
    }

    protected SQLDataType parseDataTypeRest(SQLDataType dataType) {
        dataType = super.parseDataTypeRest(dataType);

        if (lexer.token() == Token.LBRACKET) {
            lexer.nextToken();
            accept(Token.RBRACKET);
            dataType = new SQLArrayDataType(dataType);
        }

        return dataType;
    }
    
    public PGSelectParser createSelectParser() {
        return new PGSelectParser(this);
    }

    public SQLExpr primary() {
        if (lexer.token() == Token.ARRAY) {
            String ident = lexer.stringVal();
            lexer.nextToken();

            if (lexer.token() == Token.LPAREN) {
                SQLIdentifierExpr array = new SQLIdentifierExpr(ident);
                return this.methodRest(array, true);
            } else {
                SQLArrayExpr array = new SQLArrayExpr();
                array.setExpr(new SQLIdentifierExpr(ident));
                accept(Token.LBRACKET);
                this.exprList(array.getValues(), array);
                accept(Token.RBRACKET);
                return primaryRest(array);
            }
        } else if (lexer.token() == Token.POUND) {
            lexer.nextToken();
            if (lexer.token() == Token.LBRACE) {
                lexer.nextToken();
                String varName = lexer.stringVal();
                lexer.nextToken();
                accept(Token.RBRACE);
                SQLVariantRefExpr expr = new SQLVariantRefExpr("#{" + varName + "}");
                return primaryRest(expr);
            } else {
                SQLExpr value = this.primary();
                SQLUnaryExpr expr = new SQLUnaryExpr(SQLUnaryOperator.Pound, value);
                return primaryRest(expr);
            }
        } else if (lexer.token() == Token.VALUES) {
            lexer.nextToken();

            SQLValuesExpr values = new SQLValuesExpr();
            for (;;) {
                accept(Token.LPAREN);
                SQLListExpr listExpr = new SQLListExpr();
                exprList(listExpr.getItems(), listExpr);
                accept(Token.RPAREN);

                listExpr.setParent(values);

                values.getValues().add(listExpr);

                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }
                break;
            }
            return values;
        } else if (lexer.token() == Token.WITH) {
            SQLQueryExpr queryExpr = new SQLQueryExpr(
                    createSelectParser()
                            .select());
            return queryExpr;
        }
        
        return super.primary();
    }

    @Override
    protected SQLExpr parseInterval() {
        accept(Token.INTERVAL);
        SQLIntervalExpr intervalExpr = new SQLIntervalExpr();
        if (lexer.token() != Token.LITERAL_CHARS && lexer.token() != Token.LITERAL_INT) {
            return new SQLIdentifierExpr("INTERVAL");
        }
        intervalExpr.setValue(new SQLCharExpr(lexer.stringVal()));
        lexer.nextToken();

        if (lexer.identifierEquals(FnvHash.Constants.DAY)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.DAY);
        } else if (lexer.identifierEquals(FnvHash.Constants.MONTH)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.MONTH);
        } else if (lexer.identifierEquals(FnvHash.Constants.YEAR)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.YEAR);
        } else if (lexer.identifierEquals(FnvHash.Constants.HOUR)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.HOUR);
        } else if (lexer.identifierEquals(FnvHash.Constants.MINUTE)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.MINUTE);
        } else if (lexer.identifierEquals(FnvHash.Constants.SECOND)) {
            lexer.nextToken();
            intervalExpr.setUnit(SQLIntervalUnit.SECOND);
        }

        return intervalExpr;
    }

    public SQLExpr primaryRest(SQLExpr expr) {
        if (lexer.token() == Token.COLONCOLON) {
            lexer.nextToken();
            SQLDataType dataType = this.parseDataType();
            
            PGTypeCastExpr castExpr = new PGTypeCastExpr();
            
            castExpr.setExpr(expr);
            castExpr.setDataType(dataType);

            return primaryRest(castExpr);
        }
        
        if (lexer.token() == Token.LBRACKET) {
            SQLArrayExpr array = new SQLArrayExpr();
            array.setExpr(expr);
            lexer.nextToken();
            this.exprList(array.getValues(), array);
            accept(Token.RBRACKET);
            return primaryRest(array);
        }
        
        if (expr.getClass() == SQLIdentifierExpr.class) {
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;
            String ident = identifierExpr.getName();
            long hash = identifierExpr.nameHashCode64();

            if (lexer.token() == Token.COMMA || lexer.token() == Token.RPAREN) {
                return super.primaryRest(expr);
            }

            if (FnvHash.Constants.TIMESTAMP == hash) {
                if (lexer.token() != Token.LITERAL_ALIAS //
                        && lexer.token() != Token.LITERAL_CHARS //
                        && lexer.token() != Token.WITH) {
                    return super.primaryRest(
                            new SQLIdentifierExpr(ident));
                }

                SQLTimestampExpr timestamp = new SQLTimestampExpr();

                if (lexer.token() == Token.WITH) {
                    lexer.nextToken();
                    acceptIdentifier("TIME");
                    acceptIdentifier("ZONE");
                    timestamp.setWithTimeZone(true);
                }

                String literal = lexer.stringVal();
                timestamp.setLiteral(literal);
                accept(Token.LITERAL_CHARS);

                if (lexer.identifierEquals("AT")) {
                    lexer.nextToken();
                    acceptIdentifier("TIME");
                    acceptIdentifier("ZONE");

                    String timezone = lexer.stringVal();
                    timestamp.setTimeZone(timezone);
                    accept(Token.LITERAL_CHARS);
                }


                return primaryRest(timestamp);
            } else if (FnvHash.Constants.TIMESTAMPTZ == hash) {
                if (lexer.token() != Token.LITERAL_ALIAS //
                        && lexer.token() != Token.LITERAL_CHARS //
                        && lexer.token() != Token.WITH) {
                    return super.primaryRest(
                            new SQLIdentifierExpr(ident));
                }

                SQLTimestampExpr timestamp = new SQLTimestampExpr();
                timestamp.setWithTimeZone(true);

                String literal = lexer.stringVal();
                timestamp.setLiteral(literal);
                accept(Token.LITERAL_CHARS);

                if (lexer.identifierEquals("AT")) {
                    lexer.nextToken();
                    acceptIdentifier("TIME");
                    acceptIdentifier("ZONE");

                    String timezone = lexer.stringVal();
                    timestamp.setTimeZone(timezone);
                    accept(Token.LITERAL_CHARS);
                }


                return primaryRest(timestamp);
            } else if (FnvHash.Constants.EXTRACT == hash) {
                accept(Token.LPAREN);
                
                PGExtractExpr extract = new PGExtractExpr();
                
                String fieldName = lexer.stringVal();
                PGDateField field = PGDateField.valueOf(fieldName.toUpperCase());
                lexer.nextToken();
                
                extract.setField(field);
                
                accept(Token.FROM);
                SQLExpr source = this.expr();
                
                extract.setSource(source);
                
                accept(Token.RPAREN);
                
                return primaryRest(extract);
            } else if (FnvHash.Constants.POINT == hash) {
                switch (lexer.token()) {
                    case DOT:
                    case EQ:
                    case LTGT:
                    case GT:
                    case GTEQ:
                    case LT:
                    case LTEQ:
                    case SUB:
                    case PLUS:
                    case SUBGT:
                        break;
                    default:
                        SQLExpr value = this.primary();
                        PGPointExpr point = new PGPointExpr();
                        point.setValue(value);
                        return primaryRest(point);
                }
            } else if (FnvHash.Constants.BOX == hash) {
                SQLExpr value = this.primary();
                PGBoxExpr box = new PGBoxExpr();
                box.setValue(value);
                return primaryRest(box);
            } else if (FnvHash.Constants.MACADDR == hash) {
                SQLExpr value = this.primary();
                PGMacAddrExpr macaddr = new PGMacAddrExpr();
                macaddr.setValue(value);
                return primaryRest(macaddr);
            } else if (FnvHash.Constants.INET == hash) {
                    SQLExpr value = this.primary();
                    PGInetExpr inet = new PGInetExpr();
                    inet.setValue(value);
                    return primaryRest(inet);
            } else if (FnvHash.Constants.CIDR == hash) {
                SQLExpr value = this.primary();
                PGCidrExpr cidr = new PGCidrExpr();
                cidr.setValue(value);
                return primaryRest(cidr);
            } else if (FnvHash.Constants.POLYGON == hash) {
                SQLExpr value = this.primary();
                PGPolygonExpr polygon = new PGPolygonExpr();
                polygon.setValue(value);
                return primaryRest(polygon);
            } else if (FnvHash.Constants.CIRCLE == hash) {
                SQLExpr value = this.primary();
                PGCircleExpr circle = new PGCircleExpr();
                circle.setValue(value);
                return primaryRest(circle);
            } else if (FnvHash.Constants.LSEG == hash) {
                SQLExpr value = this.primary();
                PGLineSegmentsExpr lseg = new PGLineSegmentsExpr();
                lseg.setValue(value);
                return primaryRest(lseg);
            } else if (ident.equalsIgnoreCase("b") && lexer.token() == Token.LITERAL_CHARS) {
                String charValue = lexer.stringVal();
                lexer.nextToken();
                expr = new SQLBinaryExpr(charValue);

                return primaryRest(expr);
            }
        }

        return super.primaryRest(expr);
    }

    @Override
    protected String alias() {
        String alias = super.alias();
        if (alias != null) {
            return alias;
        }
        // 某些关键字在alias时,不作为关键字,仍然是作用为别名
        switch (lexer.token()) {
        case INTERSECT:
            // 具体可以参考SQLParser::alias()的方法实现
            alias = lexer.stringVal();
            lexer.nextToken();
            return alias;
        // TODO other cases
        default:
            break;
        }
        return alias;
    }
}
