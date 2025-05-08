/*
 * Copyright (c) "Neo4j"
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
package org.neo4j.storageengine.api.schema;

import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexReference;
import org.neo4j.internal.kernel.api.IndexValueCapability;
import org.neo4j.internal.kernel.api.TokenNameLookup;
import org.neo4j.internal.kernel.api.schema.IndexProviderDescriptor;
import org.neo4j.internal.kernel.api.schema.SchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.SchemaDescriptorSupplier;
import org.neo4j.internal.kernel.api.schema.SchemaUtil;
import org.neo4j.values.storable.ValueCategory;

import java.util.Optional;

import static java.lang.String.format;

/**
 * Internal representation of a graph index, including the schema unit it targets (eg. label-property combination)
 * and the type of index. UNIQUE indexes are used to back uniqueness constraints.
 *
 * An IndexDescriptor might represent an index that has not yet been committed, and therefore carries an optional
 * user-supplied name. On commit the descriptor is upgraded to a {@link StoreIndexDescriptor} using
 * {@link IndexDescriptor#withId(long)} or {@link IndexDescriptor#withIds(long, long)}.
 */
public class IndexDescriptor implements SchemaDescriptorSupplier, IndexReference
{
    protected final SchemaDescriptor schema;
    protected final Type type;
    protected final Optional<String> userSuppliedName;
    protected final IndexProviderDescriptor providerDescriptor;

    IndexDescriptor( IndexDescriptor indexDescriptor )
    {
        this( indexDescriptor.schema,
              indexDescriptor.type,
              indexDescriptor.userSuppliedName,
              indexDescriptor.providerDescriptor );
    }

    public IndexDescriptor( SchemaDescriptor schema,
                            Type type,
                            Optional<String> userSuppliedName,
                            IndexProviderDescriptor providerDescriptor )
    {
        this.schema = schema;
        this.type = type;
        this.userSuppliedName = userSuppliedName;
        this.providerDescriptor = providerDescriptor;
    }

    // METHODS

    public Type type()
    {
        return type;
    }

    public IndexProviderDescriptor providerDescriptor()
    {
        return providerDescriptor;
    }

    @Override
    public SchemaDescriptor schema()
    {
        return schema;
    }

    @Override
    public boolean isUnique()
    {
        return type == Type.UNIQUE;
    }

    @Override
    public int label() {
        return 0;
    }

    @Override
    public int[] properties()
    {
        return schema.getPropertyIds();
    }


    /**
     * Returns a user friendly description of what this index indexes.
     *
     * @param tokenNameLookup used for looking up names for token ids.
     * @return a user friendly description of what this index indexes.
     */
    @Override
    public String userDescription( TokenNameLookup tokenNameLookup )
    {
        return format( "Index( %s, %s )", type.name(), schema.userDescription( tokenNameLookup ) );
    }

    @Override
    public boolean equals( Object o )
    {
        if ( o instanceof IndexDescriptor )
        {
            IndexDescriptor that = (IndexDescriptor)o;
            return this.type() == that.type() && this.schema().equals( that.schema() );
        }
        return false;
    }

    @Override
    public int hashCode()
    {
        return type.hashCode() & schema.hashCode();
    }

    @Override
    public String toString()
    {
        return userDescription( SchemaUtil.idTokenNameLookup );
    }

    public enum Type
    {
        GENERAL,
        UNIQUE
    }

    @Override
    public IndexOrder[] orderCapability( ValueCategory... valueCategories )
    {
        return ORDER_NONE;
    }

    @Override
    public IndexValueCapability valueCapability( ValueCategory... valueCategories )
    {
        return IndexValueCapability.NO;
    }

    @Override
    public boolean isFulltextIndex()
    {
        return false;
    }

    @Override
    public boolean isEventuallyConsistent()
    {
        return false;
    }

    void assertValidId( long id, String idName )
    {
        if ( id < 0 )
        {
            throw new IllegalArgumentException( "A " + getClass().getSimpleName() + " " + idName + " must be positive, got " + id );
        }
    }
}
