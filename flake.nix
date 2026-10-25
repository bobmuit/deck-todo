{
  description = "deck-todo: a Microsoft To Do-style Android client for Nextcloud Deck";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "36.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";

      # Separate, much larger SDK with an x86_64 system image, for testing on nixos-dell.
      androidEmu = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
        includeNDK = false;
      };
      emuSdk = "${androidEmu.androidsdk}/libexec/android-sdk";
    in
    {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [
            pkgs.jdk17
            android.androidsdk
            android.platform-tools
          ];
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          JAVA_HOME = pkgs.jdk17.home;
          # The aapt2 that AGP downloads from Maven is dynamically linked and does
          # not run on NixOS; point AGP at the patched one from androidenv.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
        };

        # nix develop .#emulator, then: scripts/emulator.sh
        emulator = pkgs.mkShell {
          packages = [
            pkgs.jdk17
            androidEmu.androidsdk
          ];
          ANDROID_HOME = emuSdk;
          ANDROID_SDK_ROOT = emuSdk;
          JAVA_HOME = pkgs.jdk17.home;
        };
      };
    };
}
