package com.tolstykh.blindduel.ui.calibration;

import com.tolstykh.blindduel.connection.ActiveGameConnection;
import com.tolstykh.blindduel.game.GameSession;
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
public final class CalibrationViewModel_Factory implements Factory<CalibrationViewModel> {
  private final Provider<GameSession> gameSessionProvider;

  private final Provider<ActiveGameConnection> activeGameConnectionProvider;

  private final Provider<MotionProvider> motionProvider;

  private CalibrationViewModel_Factory(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider,
      Provider<MotionProvider> motionProvider) {
    this.gameSessionProvider = gameSessionProvider;
    this.activeGameConnectionProvider = activeGameConnectionProvider;
    this.motionProvider = motionProvider;
  }

  @Override
  public CalibrationViewModel get() {
    return newInstance(gameSessionProvider.get(), activeGameConnectionProvider.get(), motionProvider.get());
  }

  public static CalibrationViewModel_Factory create(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider,
      Provider<MotionProvider> motionProvider) {
    return new CalibrationViewModel_Factory(gameSessionProvider, activeGameConnectionProvider, motionProvider);
  }

  public static CalibrationViewModel newInstance(GameSession gameSession,
      ActiveGameConnection activeGameConnection, MotionProvider motionProvider) {
    return new CalibrationViewModel(gameSession, activeGameConnection, motionProvider);
  }
}
