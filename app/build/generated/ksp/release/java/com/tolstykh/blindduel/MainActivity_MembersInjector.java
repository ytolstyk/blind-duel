package com.tolstykh.blindduel;

import com.tolstykh.blindduel.data.AppPreferencesRepository;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<AppPreferencesRepository> appPrefsProvider;

  private MainActivity_MembersInjector(Provider<AppPreferencesRepository> appPrefsProvider) {
    this.appPrefsProvider = appPrefsProvider;
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectAppPrefs(instance, appPrefsProvider.get());
  }

  public static MembersInjector<MainActivity> create(
      Provider<AppPreferencesRepository> appPrefsProvider) {
    return new MainActivity_MembersInjector(appPrefsProvider);
  }

  @InjectedFieldSignature("com.tolstykh.blindduel.MainActivity.appPrefs")
  public static void injectAppPrefs(MainActivity instance, AppPreferencesRepository appPrefs) {
    instance.appPrefs = appPrefs;
  }
}
