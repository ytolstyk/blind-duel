package com.tolstykh.blindduel.ui.result;

import com.tolstykh.blindduel.connection.ActiveGameConnection;
import com.tolstykh.blindduel.game.GameSession;
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
public final class ResultViewModel_Factory implements Factory<ResultViewModel> {
  private final Provider<GameSession> gameSessionProvider;

  private final Provider<ActiveGameConnection> activeGameConnectionProvider;

  private ResultViewModel_Factory(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    this.gameSessionProvider = gameSessionProvider;
    this.activeGameConnectionProvider = activeGameConnectionProvider;
  }

  @Override
  public ResultViewModel get() {
    return newInstance(gameSessionProvider.get(), activeGameConnectionProvider.get());
  }

  public static ResultViewModel_Factory create(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    return new ResultViewModel_Factory(gameSessionProvider, activeGameConnectionProvider);
  }

  public static ResultViewModel newInstance(GameSession gameSession,
      ActiveGameConnection activeGameConnection) {
    return new ResultViewModel(gameSession, activeGameConnection);
  }
}
