package io.pulumi.serialization.internal;

import com.google.protobuf.Value;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.resources.Resource;

import java.util.Set;

public class Converter {

    private Converter() {
        throw new UnsupportedOperationException();
    }

    public static <T> InputOutputData<T> convertValue(String context, Value value) {
//        var (data, isKnown, isSecret) = convertValue(context, value, typeof(T));
//        return new InputOutputData<T>(Set.of(), (T)data!, isKnown, isSecret);
        throw new UnsupportedOperationException(); // TODO
    }

    public static InputOutputData</*@Nullable*/ Object> convertValue(String context, Value value, Class<?> targetType) {
//        return convertValue(context, value, targetType, Set.of());
        throw new UnsupportedOperationException(); // TODO
    }

    public static InputOutputData</*@Nullable*/ Object> ConvertValue(
            String context, Value value, Class<?> targetType, Set<Resource> resources) {
/*
        checkTargetType(context, targetType, new HashSet<Type>());

        var (deserialized, isKnown, isSecret) = Deserializer.deserialize(value);
        var converted = convertObject(context, deserialized, targetType);

        return new InputOutputData<>(resources, converted, isKnown, isSecret);
*/
        throw new UnsupportedOperationException(); // TODO
    }

    // TODO
/*
    public static void CheckTargetType(string context, Type targetType, HashSet<Type> seenTypes)
    {
        // types can be recursive.  So only dive into a type if it's the first time we're seeing it.
        if (!seenTypes.Add(targetType))
            return;

        if (targetType == typeof(bool) ||
                targetType == typeof(int) ||
        targetType == typeof(double) ||
        targetType == typeof(string) ||
                targetType == typeof(object) ||
                targetType == typeof(Asset) ||
                targetType == typeof(Archive) ||
                targetType == typeof(AssetOrArchive) ||
                targetType == typeof(JsonElement))
        {
            return;
        }

        if (targetType.IsSubclassOf(typeof(Resource)) || targetType == typeof(Resource))
        {
            return;
        }

        if (targetType == typeof(ImmutableDictionary<string, object>))
        {
            // This type is what is generated for things like azure/aws tags.  It's an untyped
            // map in our original schema.  This is the 1st out of 2 places that `object` should
            // appear as a legal value.
            return;
        }

        if (targetType == typeof(ImmutableArray<object>))
        {
            // This type is what is generated for things like YAML decode invocation response
            // in the Kubernetes provider. The elements of the array would typically be
            // immutable dictionaries.  This is the 2nd out of 2 places that `object` should
            // appear as a legal value.
            return;
        }

        if (targetType.IsEnum && targetType.GetEnumUnderlyingType() == typeof(int))
        {
            return;
        }

        if (targetType.IsValueType && targetType.GetCustomAttribute<EnumTypeAttribute>() != null)
        {
            if (CheckEnumType(targetType, typeof(string)) ||
                    CheckEnumType(targetType, typeof(double)))
            {
                return;
            }

            throw new InvalidOperationException(
                    $"{targetType.FullName} had [{nameof(EnumTypeAttribute)}], but did not contain constructor with a single String or Double parameter.");

            static bool CheckEnumType(Type targetType, Type underlyingType)
            {
                var constructor = targetType.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, new[] { underlyingType }, null);
                if (constructor == null)
                {
                    return false;
                }

                var op = targetType.GetMethod("op_Explicit", BindingFlags.Public | BindingFlags.Static, null, new[] { targetType }, null);
                if (op != null && op.ReturnType == underlyingType)
                {
                    return true;
                }

                throw new InvalidOperationException(
                        $"{targetType.FullName} had [{nameof(EnumTypeAttribute)}], but did not contain an explicit conversion operator to {underlyingType.FullName}.");
            }
        }

        if (targetType.IsConstructedGenericType)
        {
            if (targetType.GetGenericTypeDefinition() == typeof(Nullable<>))
            {
                CheckTargetType(context, targetType.GenericTypeArguments.Single(), seenTypes);
                return;
            }
            if (targetType.GetGenericTypeDefinition() == typeof(Union<,>))
            {
                CheckTargetType(context, targetType.GenericTypeArguments[0], seenTypes);
                CheckTargetType(context, targetType.GenericTypeArguments[1], seenTypes);
                return;
            }
            if (targetType.GetGenericTypeDefinition() == typeof(ImmutableArray<>))
            {
                CheckTargetType(context, targetType.GenericTypeArguments.Single(), seenTypes);
                return;
            }
            if (targetType.GetGenericTypeDefinition() == typeof(ImmutableDictionary<,>))
            {
                var dictTypeArgs = targetType.GenericTypeArguments;
                if (dictTypeArgs[0] != typeof(string))
                {
                    throw new InvalidOperationException($@"{context} contains invalid type {targetType.FullName}:
                    The only allowed ImmutableDictionary 'TKey' type is 'String'.");
                }

                CheckTargetType(context, dictTypeArgs[1], seenTypes);
                return;
            }
            throw new InvalidOperationException($@"{context} contains invalid type {targetType.FullName}:
            The only generic types allowed are ImmutableArray<...> and ImmutableDictionary<string, ...>");
        }

        var propertyTypeAttribute = targetType.GetCustomAttribute<OutputTypeAttribute>();
        if (propertyTypeAttribute == null)
        {
            throw new InvalidOperationException(
                    $@"{context} contains invalid type {targetType.FullName}. Allowed types are:
            String, Boolean, Int32, Double,
                    Nullable<...>, ImmutableArray<...> and ImmutableDictionary<string, ...> or
            a class explicitly marked with the [{nameof(OutputTypeAttribute)}].");
        }

        var constructor = GetPropertyConstructor(targetType);
        if (constructor == null)
        {
            throw new InvalidOperationException(
                    $@"{targetType.FullName} had [{nameof(OutputTypeAttribute)}], but did not contain constructor marked with [{nameof(OutputConstructorAttribute)}].");
        }

        foreach (var param in constructor.GetParameters())
        {
            CheckTargetType($@"{targetType.FullName}({param.Name})", param.ParameterType, seenTypes);
        }
    }*/
}
