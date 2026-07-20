package com.tolstykh.blindduel.sensor;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class Haptics_Factory implements Factory<Haptics> {
  private final Provider<Context> contextProvider;

  private Haptics_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public Haptics get() {
    return newInstance(contextProvider.get());
  }

  public static Haptics_Factory create(Provider<Context> contextProvider) {
    return new Haptics_Factory(contextProvider);
  }

  public static Haptics newInstance(Context context) {
    return new Haptics(context);
  }
}
