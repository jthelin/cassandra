/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Objects;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.serializers.*;
import org.apache.cassandra.utils.ByteBufferUtil;

/**
 * This is essentially like a CompositeType, but it's not primarily meant for comparison, just
 * to pack multiple values together so has a more friendly encoding.
 */
public class TupleType extends AbstractType<ByteBuffer>
{
    protected final List<AbstractType<?>> types;

    private final TupleSerializer serializer;

    public TupleType(List<AbstractType<?>> types)
    {
        for (int i = 0; i < types.size(); i++)
            types.set(i, types.get(i).freeze());
        this.types = types;
        this.serializer = new TupleSerializer(fieldSerializers(types));
    }

    private static List<TypeSerializer<?>> fieldSerializers(List<AbstractType<?>> types)
    {
        int size = types.size();
        List<TypeSerializer<?>> serializers = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            serializers.add(types.get(i).getSerializer());
        return serializers;
    }

    public static TupleType getInstance(TypeParser parser) throws ConfigurationException, SyntaxException
    {
        List<AbstractType<?>> types = parser.getTypeParameters();
        for (int i = 0; i < types.size(); i++)
            types.set(i, types.get(i).freeze());
        return new TupleType(types);
    }

    @Override
    public boolean references(AbstractType<?> check)
    {
        if (super.references(check))
            return true;
        for (AbstractType<?> type : types)
            if (type.references(check))
                return true;
        return false;
    }

    public AbstractType<?> type(int i)
    {
        return types.get(i);
    }

    public int size()
    {
        return types.size();
    }

    public List<AbstractType<?>> allTypes()
    {
        return types;
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        if (!o1.hasRemaining() || !o2.hasRemaining())
            return o1.hasRemaining() ? 1 : o2.hasRemaining() ? -1 : 0;

        ByteBuffer bb1 = o1.duplicate();
        ByteBuffer bb2 = o2.duplicate();

        for (int i = 0; bb1.remaining() > 0 && bb2.remaining() > 0 && i < types.size(); i++)
        {
            AbstractType<?> comparator = types.get(i);

            int size1 = bb1.getInt();
            int size2 = bb2.getInt();

            // Handle nulls
            if (size1 < 0)
            {
                if (size2 < 0)
                    continue;
                return -1;
            }
            if (size2 < 0)
                return 1;

            ByteBuffer value1 = ByteBufferUtil.readBytes(bb1, size1);
            ByteBuffer value2 = ByteBufferUtil.readBytes(bb2, size2);
            int cmp = comparator.compare(value1, value2);
            if (cmp != 0)
                return cmp;
        }

        if (bb1.remaining() == 0 && bb2.remaining() == 0)
            return 0;

        if (bb1.remaining() == 0)
            return allRemainingComponentsAreNull(bb2) ? 0 : -1;

        // bb1.remaining() > 0 && bb2.remaining() == 0
        return allRemainingComponentsAreNull(bb1) ? 0 : 1;
    }

    // checks if all remaining components are null (e.g., their size is -1)
    private static boolean allRemainingComponentsAreNull(ByteBuffer bb)
    {
        while (bb.hasRemaining())
        {
            int size = bb.getInt();
            if (size >= 0)
                return false;
        }
        return true;
    }

    /**
     * Split a tuple value into its component values.
     */
    public ByteBuffer[] split(ByteBuffer value)
    {
        ByteBuffer[] components = new ByteBuffer[size()];
        ByteBuffer input = value.duplicate();
        for (int i = 0; i < size(); i++)
        {
            if (!input.hasRemaining())
                return Arrays.copyOfRange(components, 0, i);

            int size = input.getInt();
            components[i] = size < 0 ? null : ByteBufferUtil.readBytes(input, size);
        }
        return components;
    }

    public static ByteBuffer buildValue(ByteBuffer[] components)
    {
        int totalLength = 0;
        for (ByteBuffer component : components)
            totalLength += 4 + (component == null ? 0 : component.remaining());

        ByteBuffer result = ByteBuffer.allocate(totalLength);
        for (ByteBuffer component : components)
        {
            if (component == null)
            {
                result.putInt(-1);
            }
            else
            {
                result.putInt(component.remaining());
                result.put(component.duplicate());
            }
        }
        result.rewind();
        return result;
    }

    @Override
    public String getString(ByteBuffer value)
    {
        StringBuilder sb = new StringBuilder();
        ByteBuffer input = value.duplicate();
        for (int i = 0; i < size(); i++)
        {
            if (!input.hasRemaining())
                return sb.toString();

            if (i > 0)
                sb.append(":");

            AbstractType<?> type = type(i);
            int size = input.getInt();
            if (size < 0)
            {
                sb.append("@");
                continue;
            }

            ByteBuffer field = ByteBufferUtil.readBytes(input, size);
            // We use ':' as delimiter, and @ to represent null, so escape them in the generated string
            sb.append(type.getString(field).replaceAll(":", "\\\\:").replaceAll("@", "\\\\@"));
        }
        return sb.toString();
    }

    public ByteBuffer fromString(String source)
    {
        // Split the input on non-escaped ':' characters
        List<String> fieldStrings = AbstractCompositeType.split(source);

        if (fieldStrings.size() > size())
            throw new MarshalException(String.format("Invalid tuple literal: too many elements. Type %s expects %d but got %d",
                                                     asCQL3Type(), size(), fieldStrings.size()));

        ByteBuffer[] fields = new ByteBuffer[fieldStrings.size()];
        for (int i = 0; i < fieldStrings.size(); i++)
        {
            String fieldString = fieldStrings.get(i);
            // We use @ to represent nulls
            if (fieldString.equals("@"))
                continue;

            AbstractType<?> type = type(i);
            fields[i] = type.fromString(fieldString.replaceAll("\\\\:", ":").replaceAll("\\\\@", "@"));
        }
        return buildValue(fields);
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
        if (parsed instanceof String)
            parsed = Json.decodeJson((String) parsed);

        if (!(parsed instanceof List))
            throw new MarshalException(String.format(
                    "Expected a list representation of a tuple, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

        List list = (List) parsed;

        if (list.size() > types.size())
            throw new MarshalException(String.format("Tuple contains extra items (expected %s): %s", types.size(), parsed));
        else if (types.size() > list.size())
            throw new MarshalException(String.format("Tuple is missing items (expected %s): %s", types.size(), parsed));

        List<Term> terms = new ArrayList<>(list.size());
        Iterator<AbstractType<?>> typeIterator = types.iterator();
        for (Object element : list)
        {
            if (element == null)
            {
                typeIterator.next();
                terms.add(Constants.NULL_VALUE);
            }
            else
            {
                terms.add(typeIterator.next().fromJSONObject(element));
            }
        }

        return new Tuples.DelayedValue(this, terms);
    }

    @Override
    public String toJSONString(ByteBuffer buffer, int protocolVersion)
    {
        ByteBuffer duplicated = buffer.duplicate();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < types.size(); i++)
        {
            if (i > 0)
                sb.append(", ");

            ByteBuffer value = CollectionSerializer.readValue(duplicated, protocolVersion);
            if (value == null)
                sb.append("null");
            else
                sb.append(types.get(i).toJSONString(value, protocolVersion));
        }
        return sb.append("]").toString();
    }

    public TypeSerializer<ByteBuffer> getSerializer()
    {
        return serializer;
    }

    @Override
    public boolean isCompatibleWith(AbstractType<?> previous)
    {
        if (!(previous instanceof TupleType))
            return false;

        // Extending with new components is fine, removing is not
        TupleType tt = (TupleType)previous;
        if (size() < tt.size())
            return false;

        for (int i = 0; i < tt.size(); i++)
        {
            AbstractType<?> tprev = tt.type(i);
            AbstractType<?> tnew = type(i);
            if (!tnew.isCompatibleWith(tprev))
                return false;
        }
        return true;
    }

    @Override
    public boolean isValueCompatibleWithInternal(AbstractType<?> otherType)
    {
        if (!(otherType instanceof TupleType))
            return false;

        // Extending with new components is fine, removing is not
        TupleType tt = (TupleType) otherType;
        if (size() < tt.size())
            return false;

        for (int i = 0; i < tt.size(); i++)
        {
            AbstractType<?> tprev = tt.type(i);
            AbstractType<?> tnew = type(i);
            if (!tnew.isValueCompatibleWith(tprev))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(types);
    }

    @Override
    public boolean equals(Object o)
    {
        if(!(o instanceof TupleType))
            return false;

        TupleType that = (TupleType)o;
        return types.equals(that.types);
    }

    @Override
    public CQL3Type asCQL3Type()
    {
        return CQL3Type.Tuple.create(this);
    }

    @Override
    public String toString()
    {
        return getClass().getName() + TypeParser.stringifyTypeParameters(types, true);
    }
}
