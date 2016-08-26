/*
 * Copyright (C) 2014 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.common.collect.Sets.immutableEnumSet;
import static dagger.internal.codegen.ContributionBinding.FactoryCreationStrategy.CLASS_CONSTRUCTOR;
import static dagger.internal.codegen.ContributionBinding.FactoryCreationStrategy.DELEGATE;
import static dagger.internal.codegen.ContributionBinding.FactoryCreationStrategy.ENUM_INSTANCE;
import static dagger.internal.codegen.MapKeys.unwrapValue;
import static dagger.internal.codegen.MoreAnnotationMirrors.unwrapOptionalEquivalence;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.STATIC;

import com.google.auto.common.MoreTypes;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dagger.Component;
import dagger.MapKey;
import dagger.Provides;
import dagger.internal.codegen.ContributionType.HasContributionType;
import dagger.producers.Produces;
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * An abstract class for a value object representing the mechanism by which a {@link Key} can be
 * contributed to a dependency graph.
 *
 * @author Jesse Beder
 * @since 2.0
 */
abstract class ContributionBinding extends Binding implements HasContributionType {

  /** Returns the type that specifies this' nullability, absent if not nullable. */
  abstract Optional<DeclaredType> nullableType();

  /**
   * A function that returns the kind of a binding.
   */
  static final Function<ContributionBinding, Kind> KIND =
      new Function<ContributionBinding, Kind>() {
        @Override
        public Kind apply(ContributionBinding binding) {
          return binding.bindingKind();
        }
      };

  abstract Optional<Equivalence.Wrapper<AnnotationMirror>> wrappedMapKey();

  final Optional<AnnotationMirror> mapKey() {
    return unwrapOptionalEquivalence(wrappedMapKey());
  }

  /**
   * The kind of contribution this binding represents. Defines which elements can specify this kind
   * of contribution.
   */
  enum Kind {
    /**
     * The synthetic binding for {@code Map<K, V>} that depends on either
     * {@code Map<K, Provider<V>>} or {@code Map<K, Producer<V>>}.
     */
    SYNTHETIC_MAP,

    /**
     * A synthetic binding for a multibound set that depends on the individual multibinding
     * {@link Provides @Provides} or {@link Produces @Produces} methods.
     */
    SYNTHETIC_MULTIBOUND_SET,

    /**
     * A synthetic binding for a multibound map that depends on the individual multibinding
     * {@link Provides @Provides} or {@link Produces @Produces} methods.
     */
    SYNTHETIC_MULTIBOUND_MAP,

    /**
     * A binding (provision or production) that delegates from requests for one key to another.
     * These are the bindings that satisfy {@code @Binds} declarations.
     */
    SYNTHETIC_DELEGATE_BINDING,

    // Provision kinds

    /** An {@link Inject}-annotated constructor. */
    INJECTION,

    /** A {@link Provides}-annotated method. */
    PROVISION,

    /** An implicit binding to a {@link Component @Component}-annotated type. */
    COMPONENT,

    /** A provision method on a component's {@linkplain Component#dependencies() dependency}. */
    COMPONENT_PROVISION,

    /**
     * A subcomponent builder method on a component or subcomponent.
     */
    SUBCOMPONENT_BUILDER,

    // Production kinds

    /** A {@link Produces}-annotated method that doesn't return a {@link ListenableFuture}. */
    IMMEDIATE,

    /** A {@link Produces}-annotated method that returns a {@link ListenableFuture}. */
    FUTURE_PRODUCTION,

    /**
     * A production method on a production component's {@linkplain
     * dagger.producers.ProductionComponent#dependencies()} dependency} that returns a
     * {@link ListenableFuture}. Methods on production component dependencies that don't return a
     * {@link ListenableFuture} are considered {@linkplain #PROVISION provision bindings}.
     */
    COMPONENT_PRODUCTION,
    ;

    /**
     * A predicate that tests whether a kind is for synthetic multibindings.
     */
    static final Predicate<Kind> IS_SYNTHETIC_MULTIBINDING_KIND =
        Predicates.in(immutableEnumSet(SYNTHETIC_MULTIBOUND_SET, SYNTHETIC_MULTIBOUND_MAP));

    /**
     * {@link #SYNTHETIC_MULTIBOUND_SET} or {@link #SYNTHETIC_MULTIBOUND_MAP}, depending on the key.
     */
    static Kind forMultibindingKey(Key key) {
      if (SetType.isSet(key)) {
        return SYNTHETIC_MULTIBOUND_SET;
      } else if (MapType.isMap(key)) {
        return SYNTHETIC_MULTIBOUND_MAP;
      } else {
        throw new IllegalArgumentException(String.format("key is not for a set or map: %s", key));
      }
    }
  }

  /**
   * The kind of this contribution binding.
   */
  protected abstract Kind bindingKind();

  /**
   * {@code true} if {@link #contributingModule()} is present and this is a nonabstract instance
   * method.
   */
  boolean requiresModuleInstance() {
    if (!bindingElement().isPresent() || !contributingModule().isPresent()) {
      return false;
    }
    Set<Modifier> modifiers = bindingElement().get().getModifiers();
    return !modifiers.contains(ABSTRACT) && !modifiers.contains(STATIC);
  }

  /**
   * A predicate that passes for binding declarations for which {@link #requiresModuleInstance()} is
   * {@code true}.
   */
  static final Predicate<ContributionBinding> REQUIRES_MODULE_INSTANCE =
      new Predicate<ContributionBinding>() {
        @Override
        public boolean apply(ContributionBinding bindingDeclaration) {
          return bindingDeclaration.requiresModuleInstance();
        }
      };

  /**
   * The strategy for getting an instance of a factory for a {@link ContributionBinding}.
   */
  enum FactoryCreationStrategy {
    /** The factory class is an enum with one value named {@code INSTANCE}. */
    ENUM_INSTANCE,
    /** The factory must be created by calling the constructor. */
    CLASS_CONSTRUCTOR,
    /** The factory is simply delegated to another. */
    DELEGATE,
  }

  /**
   * Returns the {@link FactoryCreationStrategy} appropriate for a binding.
   *
   * <p>Delegate bindings use the {@link FactoryCreationStrategy#DELEGATE} strategy.
   *
   * <p>Bindings without dependencies that don't require a module instance use the {@link
   * FactoryCreationStrategy#ENUM_INSTANCE} strategy.
   *
   * <p>All other bindings use the {@link FactoryCreationStrategy#CLASS_CONSTRUCTOR} strategy.
   */
  FactoryCreationStrategy factoryCreationStrategy() {
    switch (bindingKind()) {
      case SYNTHETIC_DELEGATE_BINDING:
        return DELEGATE;
      case PROVISION:
        return implicitDependencies().isEmpty() && !requiresModuleInstance()
            ? ENUM_INSTANCE
            : CLASS_CONSTRUCTOR;
      case INJECTION:
      case SYNTHETIC_MULTIBOUND_SET:
      case SYNTHETIC_MULTIBOUND_MAP:
        return implicitDependencies().isEmpty() ? ENUM_INSTANCE : CLASS_CONSTRUCTOR;
      default:
        return CLASS_CONSTRUCTOR;
    }
  }

  /**
   * The {@link TypeMirror type} for the {@code Factory<T>} or {@code Producer<T>} which is created
   * for this binding. Uses the binding's key, V in the came of {@code Map<K, FrameworkClass<V>>>},
   * and E {@code Set<E>} for {@link dagger.multibindings.IntoSet @IntoSet} methods.
   */
  final TypeMirror factoryType() {
    switch (contributionType()) {
      case MAP:
        return MapType.from(key()).unwrappedValueType(bindingType().frameworkClass());
      case SET:
        return SetType.from(key()).elementType();
      case SET_VALUES:
      case UNIQUE:
        return key().type();
      default:
        throw new AssertionError();
    }
  }

  /**
   * Indexes map-multibindings by map key (the result of calling
   * {@link AnnotationValue#getValue()} on a single member or the whole {@link AnnotationMirror}
   * itself, depending on {@link MapKey#unwrapValue()}).
   */
  static ImmutableSetMultimap<Object, ContributionBinding> indexMapBindingsByMapKey(
      Set<ContributionBinding> mapBindings) {
    return ImmutableSetMultimap.copyOf(
        Multimaps.index(
            mapBindings,
            new Function<ContributionBinding, Object>() {
              @Override
              public Object apply(ContributionBinding mapBinding) {
                AnnotationMirror mapKey = mapBinding.mapKey().get();
                Optional<? extends AnnotationValue> unwrappedValue = unwrapValue(mapKey);
                return unwrappedValue.isPresent() ? unwrappedValue.get().getValue() : mapKey;
              }
            }));
  }

  /**
   * Indexes map-multibindings by map key annotation type.
   */
  static ImmutableSetMultimap<Wrapper<DeclaredType>, ContributionBinding>
      indexMapBindingsByAnnotationType(Set<ContributionBinding> mapBindings) {
    return ImmutableSetMultimap.copyOf(
        Multimaps.index(
            mapBindings,
            new Function<ContributionBinding, Equivalence.Wrapper<DeclaredType>>() {
              @Override
              public Equivalence.Wrapper<DeclaredType> apply(ContributionBinding mapBinding) {
                return MoreTypes.equivalence()
                    .wrap(mapBinding.mapKey().get().getAnnotationType());
              }
            }));
  }

  /**
   * Base builder for {@link com.google.auto.value.AutoValue @AutoValue} subclasses of
   * {@link ContributionBinding}.
   */
  @CanIgnoreReturnValue
  abstract static class Builder<B extends Builder<B>> {
    abstract B contributionType(ContributionType contributionType);

    abstract B bindingElement(Element bindingElement);

    abstract B contributingModule(TypeElement contributingModule);

    abstract B key(Key key);

    abstract B dependencies(Iterable<DependencyRequest> dependencies);

    abstract B dependencies(DependencyRequest... dependencies);

    abstract B nullableType(Optional<DeclaredType> nullableType);

    abstract B wrappedMapKey(Optional<Equivalence.Wrapper<AnnotationMirror>> wrappedMapKey);

    abstract B bindingKind(ContributionBinding.Kind kind);
  }
}
