/*
 * Copyright (c) 2018-2020 "Graph Foundation,"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * This file is part of ONgDB.
 *
 * ONgDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphdb.schema;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.index.IndexManager;

/**
 * Definition for an index
 *
 * NOTE: This is part of the index API introduced in Neo4j 2.0.
 * The explicit index API lives in {@link IndexManager}.
 */
public interface IndexDefinition
{
    /**
     * @return the {@link Label label} this index definition is associated with.
     */
    Label getLabel();

    /**
     * @return the property keys this index was created on.
     */
    Iterable<String> getPropertyKeys();

    /**
     * Drops this index. {@link Schema#getIndexes(Label)} will no longer include this index
     * and any related background jobs and files will be stopped and removed.
     */
    void drop();

    /**
     * @return {@code true} if this index is created as a side effect of the creation of a uniqueness constraint.
     */
    boolean isConstraintIndex();

    /**
     * Return the set of node labels (in no particular order) that this index applies to. This method works for both {@link #isMultiTokenIndex() multi-token}
     * indexes, and "single-token" indexes.
     * <p>
     * Note that this assumes that this is a node index (that {@link #isNodeIndex()} returns {@code true}). If this is not the case, then an
     * {@link IllegalStateException} is thrown.
     *
     * @return the set of {@link Label labels} this index definition is associated with.
     */
    Iterable<Label> getLabels();

    /**
     * @return {@code true} if this index is indexing nodes, otherwise {@code false}.
     */
    boolean isNodeIndex();

    /**
     * A multi-token index is an index that indexes nodes or relationships that have any or all of a given set of labels or relationship types, respectively.
     * <p>
     * For instance, a multi-token index could apply to all {@code Movie} and {@code Book} nodes that have a {@code description} property. A node or
     * relationship do not need to have all of the labels or relationship types for it to be indexed. A node that has any of the given labels, or a relationship
     * that has any of the given relationship types, will be a candidate for indexing, depending on their properties.
     *
     * @return {@code true} if this is a multi-token index.
     */
    boolean isMultiTokenIndex();
}
