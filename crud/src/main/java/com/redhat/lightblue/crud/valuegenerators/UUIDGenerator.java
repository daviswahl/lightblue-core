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
package com.redhat.lightblue.crud.valuegenerators;

import java.util.UUID;

import com.redhat.lightblue.extensions.valuegenerator.ValueGeneratorSupport;

import com.redhat.lightblue.metadata.ValueGenerator;
import com.redhat.lightblue.metadata.EntityMetadata;

public class UUIDGenerator implements ValueGeneratorSupport {

    public static final ValueGenerator.ValueGeneratorType[] TYPES={ValueGenerator.ValueGeneratorType.UUID};

    public static final UUIDGenerator instance=new UUIDGenerator();
    
    @Override
    public ValueGenerator.ValueGeneratorType[] getSupportedGeneratorTypes() {
        return TYPES;
    }

    @Override
    public Object generateValue(EntityMetadata md,ValueGenerator generator) {
        return UUID.randomUUID().toString();
    }
}
