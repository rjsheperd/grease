{
  description = "grease iOS build environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # Xcode 14.3.1 — required for the iOS 16.4 SDK.
        # The build system (xcodebuild) must use this exact version;
        # the modern build daemon ignores DEVELOPER_DIR and uses whichever
        # Xcode xcode-select points at.  Run:
        #   sudo xcode-select -s ${xcodeApp}/Contents/Developer
        # once before building (or use `nix develop` which will warn you).
        xcodeApp = "/Volumes/MegaDisk2TB/XCode/AF56C562-4191-470B-AECA-8CBE68A2E188/Xcode.app";
        xcodeDeveloperDir = "${xcodeApp}/Contents/Developer";

        # Gluon GraalVM for iOS native-image — not in nixpkgs.
        # Downloaded separately and symlinked at graal/latest → the unpacked build.
        # scripts/build-ios defaults to graal/graalvm-java23-darwin-aarch64-gluon-23+25.1-dev/Contents/Home
        gluonGraalHome = "$PWD/graal/graalvm-java23-darwin-aarch64-gluon-23+25.1-dev/Contents/Home";
      in

      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Clojure toolchain — for uberjar compilation (clojure -T:build uberjar-basic)
            clojure
            # Build — required by scripts/build-ios-static-libs (./configure)
            autoconf
            # fastlane — for provisioning profile management via App Store Connect API.
            # nixpkgs ships fastlane with its own Ruby, avoiding the Ruby 4.0 / OpenSSL 3
            # incompatibility that breaks the Homebrew fastlane with Apple p8 API keys.
            fastlane
          ];

          shellHook = ''
            # Gluon GraalVM provides native-image with iOS arm64 support.
            # scripts/build-ios reads GRAALVM_HOME and falls back to the local path.
            if [ -d "${gluonGraalHome}" ]; then
              export JAVA_HOME="${gluonGraalHome}"
              export GRAALVM_HOME="${gluonGraalHome}"
            else
              echo "WARNING: Gluon GraalVM not found at:"
              echo "  ${gluonGraalHome}"
              echo "Download from: https://github.com/gluonhq/graal/releases"
              echo "Then unpack under graal/ in this directory."
            fi

            export DEVELOPER_DIR="${xcodeDeveloperDir}"

            # Warn if xcode-select doesn't point at the required Xcode.
            # The modern build daemon uses xcode-select, not DEVELOPER_DIR.
            _active_xcode=$(xcode-select -p 2>/dev/null)
            if [ "$_active_xcode" != "${xcodeDeveloperDir}" ]; then
              echo ""
              echo "WARNING: xcode-select is pointing at:"
              echo "  $_active_xcode"
              echo "This build requires Xcode 14.3.1 at:"
              echo "  ${xcodeDeveloperDir}"
              echo "Run the following to fix it, then re-enter the shell:"
              echo "  sudo xcode-select -s ${xcodeApp}"
              echo ""
            else
              echo "Environment loaded! Xcode 14.3.1 active."
            fi
            unset _active_xcode
          '';
        };
      }
    );
}
