{
  description = "Todosian debug APK build environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forEachSystem = f: nixpkgs.lib.genAttrs systems (system: f (mkParts system));

      mkParts = system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          buildToolsVersion = "36.0.0";

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37.1" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeNDK = false;
          };

          sdk = "${androidComposition.androidsdk}/libexec/android-sdk";

          env = ''
            export JAVA_HOME=${pkgs.jdk21}
            export ANDROID_HOME=${sdk}
            export ANDROID_SDK_ROOT=${sdk}
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2"
          '';
        in
        {
          devShell = pkgs.mkShell {
            packages = [ pkgs.jdk21 androidComposition.androidsdk ];
            shellHook = env;
          };

          buildApk = pkgs.writeShellScriptBin "build-apk" ''
            ${env}
            export PATH=${pkgs.jdk21}/bin:$PATH
            ./gradlew :app:assembleDebug "$@"
            echo "APK: $(pwd)/app/build/outputs/apk/debug/"
          '';
        };
    in
    {
      devShells = forEachSystem (p: { default = p.devShell; });

      apps = forEachSystem (p: {
        default = {
          type = "app";
          program = "${p.buildApk}/bin/build-apk";
        };
      });
    };
}
