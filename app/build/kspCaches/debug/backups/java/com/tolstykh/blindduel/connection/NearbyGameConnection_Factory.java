package com.tolstykh.blindduel.connection;

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
public final class NearbyGameConnection_Factory implements Factory<NearbyGameConnection> {
  private final Provider<Context> contextProvider;

  private NearbyGameConnection_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public NearbyGameConnection get() {
    return newInstance(contextProvider.get());
  }

  public static NearbyGameConnection_Factory create(Provider<Context> contextProvider) {
    return new NearbyGameConnection_Factory(contextProvider);
  }

  public static NearbyGameConnection newInstance(Context context) {
    return new NearbyGameConnection(context);
  }
}
