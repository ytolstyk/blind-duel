package com.tolstykh.blindduel.connection;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
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
public final class ActiveGameConnection_Factory implements Factory<ActiveGameConnection> {
  private final Provider<NearbyGameConnection> nearbyProvider;

  private final Provider<LoopbackGameConnection> loopbackProvider;

  private ActiveGameConnection_Factory(Provider<NearbyGameConnection> nearbyProvider,
      Provider<LoopbackGameConnection> loopbackProvider) {
    this.nearbyProvider = nearbyProvider;
    this.loopbackProvider = loopbackProvider;
  }

  @Override
  public ActiveGameConnection get() {
    return newInstance(nearbyProvider.get(), loopbackProvider.get());
  }

  public static ActiveGameConnection_Factory create(Provider<NearbyGameConnection> nearbyProvider,
      Provider<LoopbackGameConnection> loopbackProvider) {
    return new ActiveGameConnection_Factory(nearbyProvider, loopbackProvider);
  }

  public static ActiveGameConnection newInstance(NearbyGameConnection nearby,
      LoopbackGameConnection loopback) {
    return new ActiveGameConnection(nearby, loopback);
  }
}
