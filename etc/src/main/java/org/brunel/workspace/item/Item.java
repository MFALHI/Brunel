/*
 * Copyright (c) 2016 IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.brunel.workspace.item;

import org.brunel.workspace.db.Storable;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A storeable thing
 */
public abstract class Item implements Storable {

    public final ItemDefinition definition;

    public String id;
    protected String label;
    private Representation representation;

    protected Item(ItemDefinition definition) {
        this.definition = definition;
    }

    public abstract Item defineByUserInput();

    public String getLabel() {
        return label;
    }

    public Representation getRepresentation() {
        if (representation == null) representation = makeRepresentation();
        return representation;
    }

    public void setSelected(boolean selected) {
        if (representation != null) representation.setSelected(selected);
    }

    protected abstract Representation makeRepresentation();

    protected Object[] makeStorableObjects(Object... additional) {
        Object[] objects = new Object[2 + additional.length];
        objects[0] = id;
        objects[1] = label;
        System.arraycopy(additional, 0, objects, 2, additional.length);
        return objects;
    }

    protected void readCommonFields(ResultSet rs) throws SQLException {
        id = rs.getString(1);
        label = rs.getString(2);
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return id.equals(((Item) o).id);
    }

    public int hashCode() {
        return id.hashCode();
    }

    public String toString() {
        return label + " (" + id + ")";
    }
}
