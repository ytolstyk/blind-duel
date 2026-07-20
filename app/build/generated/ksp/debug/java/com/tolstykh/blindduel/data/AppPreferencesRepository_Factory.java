package com.tolstykh.blindduel.data;

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
public final class AppPreferencesRepository_Factory implements Factory<AppPreferencesRepository> {
  private final Provider<Context> contextProvider;

  private AppPreferencesRepository_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public AppPreferencesRepository get() {
    return newInstance(contextProvider.get());
  }

  public static AppPreferencesRepository_Factory create(Provider<Context> contextProvider) {
    return new AppPreferencesRepository_Factory(contextProvider);
  }

  public static AppPreferencesRepository newInstance(Context context) {
    return new AppPreferencesRepository(context);
  }
}
