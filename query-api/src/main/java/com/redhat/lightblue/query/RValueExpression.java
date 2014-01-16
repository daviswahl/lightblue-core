/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonObject;
import com.redhat.lightblue.util.Path;

/**
 * Expression that can be on the right side of an assignment
 * operator. It can be a value, a field reference, or an empty object
 * <pre>
 *   rvalue_expression := value | { $valueof : path } | {}
 */
public class RValueExpression extends JsonObject {

    public static final String ERR_INVALID_RVALUE_EXPRESSION="INVALID_RVALUE_EXPRESSION";

    public enum RValueType {
        _value,
        _dereference,
        _emptyObject
            }

    private final Value value;
    private final Path path;
    private final RValueType type;

    /**
     * Creates an rvalue expression that is a constant value
     */
    public RValueExpression(Value value) {
        this.value=value;
        this.path=null;
        this.type=RValueType._value;
    }

    /**
     * Creates an rvalue expression that references another field
     */
    public RValueExpression(Path p) {
        this.type=RValueType._dereference;
        this.path=p;
        this.value=null;
    }

    /**
     * Creates an rvalue expression that is an empty object
     */
    public RValueExpression() {
        this.type=RValueType._emptyObject;
        this.path=null;
        this.value=null;
    }

    /**
     * The constant value. Null if this rvalue references a field
     */
    public Value getValue() {
        return value;
    }

    /**
     * The referenced field. Null if this rvalue is a constant
     */
    public Path getPath() {
        return path;
    }

    /**
     * The reference type.
     */
    public RValueType getType() {
        return type;
    }

    @Override
    public JsonNode toJson() {
        switch(type) {
        case _value: return value.toJson();
        case _dereference: 
            ObjectNode node=getFactory().objectNode();
            node.put("$valueof",path.toString());
            return node;
        default:
            return getFactory().objectNode();
        }
    }

    /**
     * Parses an rvalue from a json node.
     */
    public static RValueExpression fromJson(JsonNode node) {
        if(node instanceof ObjectNode) {
            if(node.size()==1) {
                JsonNode path=node.get("$valueof");
                if(path!=null&&path.isValueNode()) {
                    return new RValueExpression(new Path(path.asText()));
                }
            } else
                return new RValueExpression();
        } else if(node.isValueNode()) {
            return new RValueExpression(Value.fromJson(node));
        } 
        throw Error.get(ERR_INVALID_RVALUE_EXPRESSION,node.toString());
    }
}