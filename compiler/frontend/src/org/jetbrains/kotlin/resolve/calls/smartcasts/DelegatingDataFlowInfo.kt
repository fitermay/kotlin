/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.smartcasts

import com.google.common.collect.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.resolve.calls.smartcasts.Nullability.NOT_NULL
import org.jetbrains.kotlin.resolve.calls.smartcasts.Nullability.UNKNOWN
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.isFlexible
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.pcollections.HashTreePMap
import org.pcollections.PMap
import java.util.*

internal class DelegatingDataFlowInfo private constructor(
        private val nullabilityInfo: PMap<DataFlowValue, Nullability>,
        private val typeInfo: PMap<DataFlowValue, Set<KotlinType>>
) : DataFlowInfo {

    constructor(): this(HashTreePMap.empty(), HashTreePMap.empty())

    override val completeNullabilityInfo: PMap<DataFlowValue, Nullability>
        get() = nullabilityInfo
    override val completeTypeInfo: PMap<DataFlowValue, Set<KotlinType>>
        get() = typeInfo

    override fun getCollectedNullability(key: DataFlowValue) = getNullability(key, false)

    override fun getStableNullability(key: DataFlowValue) = getNullability(key, true)

    private fun getNullability(key: DataFlowValue, stableOnly: Boolean) =
            if (stableOnly && !key.isStable) {
                key.immanentNullability
            }
            else {
                nullabilityInfo[key] ?: key.immanentNullability
            }

    private fun putNullability(map: MutableMap<DataFlowValue, Nullability>,
                               value: DataFlowValue,
                               nullability: Nullability,
                               languageVersionSettings: LanguageVersionSettings,
                               affectReceiver: Boolean = true): Boolean {
        map.put(value, nullability)

        val identifierInfo = value.identifierInfo
        if (affectReceiver && !nullability.canBeNull() &&
            languageVersionSettings.supportsFeature(LanguageFeature.SafeCallBoundSmartCasts)) {
            when (identifierInfo) {
                is IdentifierInfo.Qualified -> {
                    val receiverType = identifierInfo.receiverType
                    if (identifierInfo.safe && receiverType != null) {
                        putNullability(map, DataFlowValue(identifierInfo.receiverInfo, receiverType), nullability, languageVersionSettings)
                    }
                }
                is IdentifierInfo.Variable -> identifierInfo.bound?.let {
                    putNullability(map, it, nullability, languageVersionSettings)
                }
            }
        }

        return nullability != getCollectedNullability(value)
    }

    override fun getCollectedTypes(key: DataFlowValue) = getCollectedTypes(key, true)

    private fun getCollectedTypes(key: DataFlowValue, enrichWithNotNull: Boolean): Set<KotlinType> {
        val types = typeInfo[key].orEmpty()
        if (!enrichWithNotNull || getCollectedNullability(key).canBeNull()) {
            return types
        }

        val enrichedTypes = Sets.newHashSetWithExpectedSize<KotlinType>(types.size + 1)
        val originalType = key.type
        if (originalType.isMarkedNullable) {
            enrichedTypes.add(TypeUtils.makeNotNullable(originalType))
        }
        for (type in types) {
            enrichedTypes.add(TypeUtils.makeNotNullable(type))
        }

        return enrichedTypes
    }

    override fun getStableTypes(key: DataFlowValue) = getStableTypes(key, true)

    private fun getStableTypes(key: DataFlowValue, enrichWithNotNull: Boolean) =
            if (!key.isStable) LinkedHashSet() else getCollectedTypes(key, enrichWithNotNull)

    /**
     * Call this function to clear all data flow information about
     * the given data flow value.

     * @param value
     */
    override fun clearValueInfo(value: DataFlowValue, languageVersionSettings: LanguageVersionSettings): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        putNullability(builder, value, Nullability.UNKNOWN, languageVersionSettings)
        return DelegatingDataFlowInfo(nullabilityInfo.plus(value, UNKNOWN), typeInfo.plus(value, emptySet()))
    }

    override fun assign(a: DataFlowValue, b: DataFlowValue, languageVersionSettings: LanguageVersionSettings): DataFlowInfo {
        val nullability = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfB = getStableNullability(b)
        putNullability(nullability, a, nullabilityOfB, languageVersionSettings, affectReceiver = false)

        val newTypeInfo = newTypeInfo()
        var typesForB = getStableTypes(b)
        // Own type of B must be recorded separately, e.g. for a constant
        // But if its type is the same as A, there is no reason to do it
        // because own type is not saved in this set
        // Error types are also not saved
        if (!b.type.isError && a.type != b.type) {
            typesForB += b.type
        }
        newTypeInfo.putAll(a, typesForB)

        return create(this, nullability, if (newTypeInfo.isEmpty) EMPTY_TYPE_INFO else newTypeInfo.asMap())
    }

    override fun equate(
            a: DataFlowValue, b: DataFlowValue, sameTypes: Boolean, languageVersionSettings: LanguageVersionSettings
    ): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfA = getStableNullability(a)
        val nullabilityOfB = getStableNullability(b)

        var changed = putNullability(builder, a, nullabilityOfA.refine(nullabilityOfB), languageVersionSettings) or
                      putNullability(builder, b, nullabilityOfB.refine(nullabilityOfA), languageVersionSettings)

        // NB: == has no guarantees of type equality, see KT-11280 for the example
        val newTypeInfo = newTypeInfo()
        if (sameTypes) {
            newTypeInfo.putAll(a, getStableTypes(b, false))
            newTypeInfo.putAll(b, getStableTypes(a, false))
            if (a.type != b.type) {
                // To avoid recording base types of own type
                if (!a.type.isSubtypeOf(b.type)) {
                    newTypeInfo.put(a, b.type)
                }
                if (!b.type.isSubtypeOf(a.type)) {
                    newTypeInfo.put(b, a.type)
                }
            }
            changed = changed or !newTypeInfo.isEmpty
        }

        return if (!changed) {
            this
        }
        else {
            create(this, builder, if (newTypeInfo.isEmpty) EMPTY_TYPE_INFO else newTypeInfo.asMap())
        }
    }

    override fun disequate(
            a: DataFlowValue, b: DataFlowValue, languageVersionSettings: LanguageVersionSettings
    ): DataFlowInfo {
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        val nullabilityOfA = getStableNullability(a)
        val nullabilityOfB = getStableNullability(b)

        val changed = putNullability(builder, a, nullabilityOfA.refine(nullabilityOfB.invert()), languageVersionSettings) or
                      putNullability(builder, b, nullabilityOfB.refine(nullabilityOfA.invert()), languageVersionSettings)
        return if (changed) create(this, builder, EMPTY_TYPE_INFO) else this
    }

    override fun establishSubtyping(
            value: DataFlowValue, type: KotlinType, languageVersionSettings: LanguageVersionSettings
    ): DataFlowInfo {
        if (value.type == type) return this
        if (getCollectedTypes(value).contains(type)) return this
        if (!value.type.isFlexible() && value.type.isSubtypeOf(type)) return this
        val newTypeInfo = newTypeInfo()
        newTypeInfo.put(value, type)
        val builder = Maps.newHashMap<DataFlowValue, Nullability>()
        if (!type.isMarkedNullable) {
            putNullability(builder, value, NOT_NULL, languageVersionSettings)
        }
        val newNullabilityInfo = if (type.isMarkedNullable) EMPTY_NULLABILITY_INFO else ImmutableMap.copyOf(builder)
        return create(this, newNullabilityInfo, newTypeInfo.asMap())
    }

    override fun and(other: DataFlowInfo): DataFlowInfo {
        if (other === DataFlowInfo.EMPTY) return this
        if (this === DataFlowInfo.EMPTY) return other
        if (this === other) return this

        assert(other is DelegatingDataFlowInfo) { "Unknown DataFlowInfo type: " + other }

        val nullabilityMapBuilder = Maps.newHashMap<DataFlowValue, Nullability>()
        for ((key, otherFlags) in other.completeNullabilityInfo) {
            val thisFlags = getCollectedNullability(key)
            val flags = thisFlags.and(otherFlags)
            if (flags != thisFlags) {
                nullabilityMapBuilder.put(key, flags)
            }
        }

        val myTypeInfo = completeTypeInfo
        val otherTypeInfo = other.completeTypeInfo
        if (nullabilityMapBuilder.isEmpty() && containsAll(myTypeInfo, otherTypeInfo)) {
            return this
        }

        return DelegatingDataFlowInfo(
                nullabilityInfo.plusAll(nullabilityMapBuilder),
                myTypeInfo.plusAll(otherTypeInfo.mapValues { myTypeInfo[it.key].orEmpty() + it.value })
        )
    }

    private fun Set<KotlinType>?.containsNothing() = this?.any { KotlinBuiltIns.isNothing(it) } ?: false

    private fun Set<KotlinType>?.intersect(other: Set<KotlinType>?) =
            if (other.containsNothing()) this
            else if (this.containsNothing()) other
            else Sets.intersection(this.orEmpty(), other.orEmpty())

    override fun or(other: DataFlowInfo): DataFlowInfo {
        if (other === DataFlowInfo.EMPTY) return DataFlowInfo.EMPTY
        if (this === DataFlowInfo.EMPTY) return DataFlowInfo.EMPTY
        if (this === other) return this

        assert(other is DelegatingDataFlowInfo) { "Unknown DataFlowInfo type: " + other }

        val nullabilityMapBuilder = Maps.newHashMap<DataFlowValue, Nullability>()
        for ((key, otherFlags) in other.completeNullabilityInfo) {
            val thisFlags = getCollectedNullability(key)
            nullabilityMapBuilder.put(key, thisFlags.or(otherFlags))
        }

        val myTypeInfo = completeTypeInfo
        val otherTypeInfo = other.completeTypeInfo
        val newTypeInfo = newTypeInfo()

        for (key in Sets.intersection(myTypeInfo.keys, otherTypeInfo.keys)) {
            newTypeInfo.putAll(key, myTypeInfo[key].intersect(otherTypeInfo[key]))
        }

        return DelegatingDataFlowInfo(
                HashTreePMap.from(nullabilityMapBuilder), HashTreePMap.from(newTypeInfo.asMap()) as PMap<DataFlowValue, Set<KotlinType>>
        )
    }

    override fun toString() = if (typeInfo.isEmpty() && nullabilityInfo.isEmpty()) "EMPTY" else "Non-trivial DataFlowInfo"

    companion object {
        private val EMPTY_NULLABILITY_INFO = ImmutableMap.of<DataFlowValue, Nullability>()
        private val EMPTY_TYPE_INFO = mutableMapOf<DataFlowValue, MutableCollection<KotlinType>>()

        private fun containsAll(first: Map<DataFlowValue, Set<KotlinType>>, second: Map<DataFlowValue, Set<KotlinType>>) =
                second.entries.all { first[it.key]?.containsAll(it.value) ?: false }

        fun newTypeInfo(): SetMultimap<DataFlowValue, KotlinType> = LinkedHashMultimap.create<DataFlowValue, KotlinType>()

        private fun create(parent: DataFlowInfo?,
                           nullabilityInfo: Map<DataFlowValue, Nullability>,
                           // NB: typeInfo must be mutable here!
                           typeInfo: MutableMap<DataFlowValue, MutableCollection<KotlinType>>
        ): DataFlowInfo {
            val toDelete = newTypeInfo()
            for (value in typeInfo.keys) {
                for (type in typeInfo[value].orEmpty()) {
                    // Remove original type (see also KT-10666)
                    if (value.type == type) {
                        toDelete.put(value, type)
                    }
                }
            }
            for ((value, type) in toDelete.entries()) {
                typeInfo[value]?.remove(type)
            }

            if (nullabilityInfo.isEmpty() && typeInfo.isEmpty()) {
                return parent ?: DataFlowInfoFactory.EMPTY
            }

            val map1 = (parent as DelegatingDataFlowInfo?)?.nullabilityInfo ?: HashTreePMap.empty()
            val map2 = parent?.typeInfo ?: HashTreePMap.empty()

            return DelegatingDataFlowInfo(map1.plusAll(nullabilityInfo), map2.plusAll(typeInfo as Map<DataFlowValue, Set<KotlinType>>))
        }
    }
}
