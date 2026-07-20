package com.tolstykh.blindduel.connection;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class LoopbackGameConnection_Factory implements Factory<LoopbackGameConnection> {
  @Override
  public LoopbackGameConnection get() {
    return newInstance();
  }

  public static LoopbackGameConnection_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static LoopbackGameConnection newInstance() {
    return new LoopbackGameConnection();
  }

  private static final class InstanceHolder {
    static final LoopbackGameConnection_Factory INSTANCE = new LoopbackGameConnection_Factory();
  }
}
