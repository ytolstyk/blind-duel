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
public final class MotionProvider_Factory implements Factory<MotionProvider> {
  private final Provider<Context> contextProvider;

  private MotionProvider_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public MotionProvider get() {
    return newInstance(contextProvider.get());
  }

  public static MotionProvider_Factory create(Provider<Context> contextProvider) {
    return new MotionProvider_Factory(contextProvider);
  }

  public static MotionProvider newInstance(Context context) {
    return new MotionProvider(context);
  }
}
