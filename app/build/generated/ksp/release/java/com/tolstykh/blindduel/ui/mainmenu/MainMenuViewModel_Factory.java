package com.tolstykh.blindduel.ui.mainmenu;

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
public final class MainMenuViewModel_Factory implements Factory<MainMenuViewModel> {
  private final Provider<GameSession> gameSessionProvider;

  private final Provider<ActiveGameConnection> activeGameConnectionProvider;

  private MainMenuViewModel_Factory(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    this.gameSessionProvider = gameSessionProvider;
    this.activeGameConnectionProvider = activeGameConnectionProvider;
  }

  @Override
  public MainMenuViewModel get() {
    return newInstance(gameSessionProvider.get(), activeGameConnectionProvider.get());
  }

  public static MainMenuViewModel_Factory create(Provider<GameSession> gameSessionProvider,
      Provider<ActiveGameConnection> activeGameConnectionProvider) {
    return new MainMenuViewModel_Factory(gameSessionProvider, activeGameConnectionProvider);
  }

  public static MainMenuViewModel newInstance(GameSession gameSession,
      ActiveGameConnection activeGameConnection) {
    return new MainMenuViewModel(gameSession, activeGameConnection);
  }
}
