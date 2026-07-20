package com.tolstykh.blindduel.ui.duel;

import com.tolstykh.blindduel.connection.ActiveGameConnection;
import com.tolstykh.blindduel.game.GameSession;
import com.tolstykh.blindduel.sensor.Haptics;
import com.tolstykh.blindduel.sensor.MotionProvider;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class DuelViewModel_Factory implements Factory<DuelViewModel> {
  private final Provider<GameSession> gameSessionProvider;

  private final Provider<ActiveGameConnection> activeGameConnectionProvider;

  private final Provider<MotionProvider> motionProvider;

  private final Provider<Haptics> hapticsProvider;

  private DuelViewModel_Factory(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider,
      Provider<MotionProvider> motionProvider, Provider<Haptics> hapticsProvider) {
    this.gameSessionProvider = gameSessionProvider;
    this.activeGameConnectionProvider = activeGameConnectionProvider;
    this.motionProvider = motionProvider;
    this.hapticsProvider = hapticsProvider;
  }

  @Override
  public DuelViewModel get() {
    return newInstance(gameSessionProvider.get(), activeGameConnectionProvider.get(), motionProvider.get(), hapticsProvider.get());
  }

  public static DuelViewModel_Factory create(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider,
      Provider<MotionProvider> motionProvider, Provider<Haptics> hapticsProvider) {
    return new DuelViewModel_Factory(gameSessionProvider, activeGameConnectionProvider, motionProvider, hapticsProvider);
  }

  public static DuelViewModel newInstance(GameSession gameSession,
      ActiveGameConnection activeGameConnection, MotionProvider motionProvider, Haptics haptics) {
    return new DuelViewModel(gameSession, activeGameConnection, motionProvider, haptics);
  }
}
