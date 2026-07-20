package com.tolstykh.blindduel.game;

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
public final class GameSession_Factory implements Factory<GameSession> {
  @Override
  public GameSession get() {
    return newInstance();
  }

  public static GameSession_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static GameSession newInstance() {
    return new GameSession();
  }

  private static final class InstanceHolder {
    static final GameSession_Factory INSTANCE = new GameSession_Factory();
  }
}
