package com.tolstykh.blindduel.ui.settings;

import com.tolstykh.blindduel.data.AppPreferencesRepository;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<AppPreferencesRepository> appPrefsProvider;

  private SettingsViewModel_Factory(Provider<AppPreferencesRepository> appPrefsProvider) {
    this.appPrefsProvider = appPrefsProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(appPrefsProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<AppPreferencesRepository> appPrefsProvider) {
    return new SettingsViewModel_Factory(appPrefsProvider);
  }

  public static SettingsViewModel newInstance(AppPreferencesRepository appPrefs) {
    return new SettingsViewModel(appPrefs);
  }
}
