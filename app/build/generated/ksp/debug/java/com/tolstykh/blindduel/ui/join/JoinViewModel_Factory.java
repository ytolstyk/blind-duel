package com.tolstykh.blindduel.ui.join;

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
public final class JoinViewModel_Factory implements Factory<JoinViewModel> {
  private final Provider<GameSession> gameSessionProvider;

  private final Provider<ActiveGameConnection> activeGameConnectionProvider;

  private JoinViewModel_Factory(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    this.gameSessionProvider = gameSessionProvider;
    this.activeGameConnectionProvider = activeGameConnectionProvider;
  }

  @Override
  public JoinViewModel get() {
    return newInstance(gameSessionProvider.get(), activeGameConnectionProvider.get());
  }

  public static JoinViewModel_Factory create(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    return new JoinViewModel_Factory(gameSessionProvider, activeGameConnectionProvider);
  }

  public static JoinViewModel newInstance(GameSession gameSession,
      ActiveGameConnection activeGameConnection) {
    return new JoinViewModel(gameSession, activeGameConnection);
  }
}
